#!/usr/bin/env python3
"""实战线 `spring-boot-project` 的本地交叉验证（WORK-055 TASK-006）。

只做一件事，但这件事没有别的办法自动做：**确认每一关的 starter 编译得过但跑不绿，
而它公布的参考实现能跑绿**。前者保证学习者拿到的是"能改的骨架"而不是一堆编译错误，
后者保证正文里 `<details>` 里的答案是真的。

与 `verify_handmade.py` 的区别：那一套是单文件 `javac`，这里是**真项目**——骨架取自
`scripts/spring-project-skeleton/`（服务器镜像 `/opt/skeleton` 的副本），跑的是
`mvn test`，与容器里的判分同一条路径。

用法：

    python scripts/verify_spring_project.py                 # 校验 content/courses/spring-boot-project
    python scripts/verify_spring_project.py --only sp-project-02
    python scripts/verify_spring_project.py --keep          # 保留临时目录（排查失败）

约定（与正文一致）：

- 每课的 `code-challenge` 是 `kind: run`、`language: spring`；
- `starter` 是学习者要改的那个文件的内容，路径是 `editablePath`；
- `files` 是只读文件（本线只有测试）；
- 参考实现放在 `<details>` 里，其后的第一个 ```java 块就是答案。
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "content" / "courses" / "spring-boot-project"
SKELETON = ROOT / "scripts" / "spring-project-skeleton"

FENCE = re.compile(r"^```([A-Za-z0-9_-]*)\s*$", re.MULTILINE)


def blocks(text: str) -> list[tuple[str, str]]:
    """把 ```lang ... ``` 切成 (lang, body)。与 verify_handmade.py 同一写法。"""
    lines = text.splitlines()
    out: list[tuple[str, str]] = []
    index = 0
    while index < len(lines):
        match = FENCE.match(lines[index])
        if match is None:
            index += 1
            continue
        language = match.group(1)
        index += 1
        body: list[str] = []
        while index < len(lines) and not lines[index].startswith("```"):
            body.append(lines[index])
            index += 1
        index += 1
        out.append((language, "\n".join(body)))
    return out


def java_after(text: str, marker: str) -> str | None:
    if marker not in text:
        return None
    for language, body in blocks(text[text.index(marker):]):
        if language == "java":
            return body
    return None


def levels(directory: pathlib.Path) -> list[dict]:
    found: list[dict] = []
    for path in sorted(directory.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        for language, body in blocks(text):
            if language != "code-challenge":
                continue
            spec = json.loads(body)
            if spec.get("kind") != "run" or spec.get("language") != "spring":
                continue
            spec["_path"] = path
            spec["_reference"] = java_after(text, "<details>")
            found.append(spec)
    return found


def project(spec: dict, content: str, workdir: pathlib.Path, suffix: str) -> pathlib.Path:
    """骨架 + 只读文件 + 一个主文件，拼成一个能 `mvn test` 的项目。"""
    target = workdir / f"{spec['id']}-{suffix}"
    shutil.copytree(SKELETON, target)
    for item in spec.get("files", []):
        path = target / "src" / item["path"]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(item["content"], encoding="utf-8")
    main = target / "src" / spec["editablePath"]
    main.parent.mkdir(parents=True, exist_ok=True)
    main.write_text(content, encoding="utf-8")
    return target


def mvn_command() -> list[str]:
    """Windows 上 `mvn` 是 `.cmd`，CreateProcess 不能直接执行，要经 cmd /c。"""
    exe = shutil.which("mvn")
    if exe is None:
        return []
    if os.name == "nt" and exe.lower().endswith((".cmd", ".bat")):
        # cmd /c 对带引号的程序路径会把后面的参数吃掉；没空格就不要引号
        return ["cmd", "/c", f'"{exe}"' if " " in exe else exe]
    return [exe]


def mvn_test(where: pathlib.Path) -> tuple[int, str]:
    result = subprocess.run(
        [*mvn_command(), "-B", "-Dstyle.color=never", "test"],
        cwd=where,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.returncode, result.stdout + result.stderr


def summarise(output: str) -> str:
    for line in output.splitlines():
        if "Tests run:" in line and "Time elapsed" not in line:
            return line.strip()
    if "COMPILATION ERROR" in output:
        return "编译失败（starter 必须是能编译的骨架）"
    return "没有产生测试结果"


def main() -> int:
    # Windows 控制台默认 GBK，✓/✗ 会直接抛 UnicodeEncodeError
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", default=str(DEFAULT_DIR))
    parser.add_argument("--only", default=None, help="只跑某一关（id，如 sp-project-02）")
    parser.add_argument("--keep", action="store_true", help="保留临时目录")
    args = parser.parse_args()

    directory = pathlib.Path(args.dir)
    if not SKELETON.is_dir():
        print(f"缺少骨架：{SKELETON}", file=sys.stderr)
        return 2
    if shutil.which("mvn") is None:
        print("找不到 mvn（这一套要在真项目上跑，不能用 javac 代替）", file=sys.stderr)
        return 2

    specs = [spec for spec in levels(directory) if args.only in (None, spec["id"])]
    if not specs:
        print("没有找到任何 spring 实战关卡", file=sys.stderr)
        return 2

    problems: list[str] = []
    workdir = pathlib.Path(tempfile.mkdtemp(prefix="spring-verify-"))
    print(f"临时目录：{workdir}")
    try:
        for spec in specs:
            reference = spec.get("_reference")
            label = f"{spec['_path'].name} · {spec['id']}"
            if reference is None:
                problems.append(f"{label}：<details> 里没有参考实现（```java）")
                print(f"✗ {label}：缺参考实现")
                continue

            starter_code, starter_output = mvn_test(project(spec, spec["starter"], workdir, "starter"))
            reference_code, reference_output = mvn_test(project(spec, reference, workdir, "reference"))

            starter_ok = starter_code != 0 and "COMPILATION ERROR" not in starter_output
            reference_ok = reference_code == 0
            print(
                f"{'✓' if starter_ok and reference_ok else '✗'} {label}\n"
                f"    starter  exit={starter_code}  {summarise(starter_output)}\n"
                f"    参考实现 exit={reference_code}  {summarise(reference_output)}"
            )
            if not starter_ok:
                problems.append(f"{label}：starter 必须编译通过但测试失败（exit={starter_code}）")
            if not reference_ok:
                problems.append(f"{label}：参考实现没有跑绿（exit={reference_code}）")
    finally:
        if args.keep:
            print(f"保留目录：{workdir}")
        else:
            shutil.rmtree(workdir, ignore_errors=True)

    if problems:
        print("\n失败：")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"\n全部通过：{len(specs)} 关")
    return 0


if __name__ == "__main__":
    sys.exit(main())
