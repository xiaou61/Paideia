#!/usr/bin/env python3
"""实战线（`line_mode: practice` + `verify: project`）的本地交叉验证。

只做一件事，但这件事没有别的办法自动做：**确认每一关的 starter 编译得过但跑不绿，
而它的参考实现能跑绿**。前者保证学习者拿到的是"能改的骨架"而不是一堆编译错误；
后者保证我们公布在正文里的答案是真的。

`verify_course.py` 覆盖不到它——那里的 `<!-- run -->` 示例是单文件 `Main`，
而实战关卡是 `Main.java` + 只读 `TestMain.java` 的多文件项目模式。

用法：

    python scripts/verify_handmade.py                      # 校验 content/courses/spring-handmade
    python scripts/verify_handmade.py --dir content/courses/xxx

约定（与正文一致）：

- 每课的 `code-challenge` 是 `kind: run`，`files` 里带 `TestMain.java`；
- 参考实现放在 `<details>` 里，其后的第一个 ```java 块就是答案；
- `<!-- run -->` 之后的第一个 ```java / ```text 是演示与它的期望输出。
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "content" / "courses" / "spring-handmade"

FENCE = re.compile(r"^```([A-Za-z0-9_-]*)\s*$", re.MULTILINE)


def blocks(text: str) -> list[tuple[str, str]]:
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
        out.append((language, "\n".join(body)))
        index += 1
    return out


def java_after(text: str, marker: str) -> str:
    for language, body in blocks(text[text.index(marker):]):
        if language == "java":
            return body
    raise SystemExit(f"{marker} 之后没有 java 块")


def javac(workdir: pathlib.Path, sources: dict[str, str]) -> tuple[int, str]:
    for name, content in sources.items():
        (workdir / name).write_text(content + "\n", encoding="utf-8")
    done = subprocess.run(
        ["javac", "--release", "21", "-encoding", "UTF-8", *sources],
        cwd=workdir, capture_output=True, text=True, encoding="gbk", errors="replace")
    return done.returncode, done.stderr


def run_main(workdir: pathlib.Path) -> tuple[int, str]:
    done = subprocess.run(["java", "-cp", ".", "TestMain"], cwd=workdir,
                          capture_output=True, text=True, encoding="gbk", errors="replace")
    return done.returncode, (done.stdout or "") + (done.stderr or "")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", default=str(DEFAULT_DIR))
    arguments = parser.parse_args()
    directory = pathlib.Path(arguments.dir)

    problems: list[str] = []
    checked = 0
    for path in sorted(directory.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        if not text.startswith("---"):
            continue
        if re.search(r"^order:\s*0\s*$", text.split("---")[1], re.MULTILINE):
            continue

        challenge = next(
            (json.loads(body) for language, body in blocks(text) if language == "code-challenge"),
            None)
        if challenge is None:
            problems.append(f"{path.name}: 没有 code-challenge")
            continue
        if challenge.get("kind") != "run":
            problems.append(f"{path.name}: 实战关卡必须是 kind=run，实际 {challenge.get('kind')}")
            continue

        test_main = next(
            (item["content"] for item in challenge.get("files", []) if item["path"] == "TestMain.java"),
            None)
        if test_main is None:
            problems.append(f"{path.name}: files 里没有 TestMain.java")
            continue

        # 判题输出必须是 ASCII。容器里没有 LANG，`System.out` 不保证是 UTF-8；
        # 中文的 FAIL 说明会变成 `?`，而这五关正是靠那行文字告诉学习者差在哪。
        # 既有 java-project 线的 expected 也全是 ASCII——同一条约定。源码里的中文注释不受影响
        # （那边 starter 一直带中文注释且在用）。
        if not test_main.isascii():
            problems.append(
                f"{path.name}: TestMain.java 含非 ASCII 字符；它会写 stdout，"
                f"容器 locale 不确定，失败原因会变成乱码")
        for case in challenge.get("cases", []):
            if not str(case.get("expected", "")).isascii():
                problems.append(f"{path.name}: 用例 {case.get('name')} 的 expected 含非 ASCII 字符")

        # starter：必须编译通过、但跑不出 ALL PASS
        with tempfile.TemporaryDirectory() as raw:
            work = pathlib.Path(raw)
            code, stderr = javac(work, {"Main.java": challenge["starter"], "TestMain.java": test_main})
            if code != 0:
                problems.append(f"{path.name}: starter 编译失败\n{stderr[:600]}")
            else:
                exit_code, output = run_main(work)
                if exit_code == 0 and output.strip().endswith("ALL PASS"):
                    problems.append(f"{path.name}: starter 竟然通过了——它没有留下要做的部分")

        # 答案：必须 ALL PASS
        with tempfile.TemporaryDirectory() as raw:
            work = pathlib.Path(raw)
            answer = java_after(text, "<summary>展开答案")
            code, stderr = javac(work, {"Main.java": answer, "TestMain.java": test_main})
            if code != 0:
                problems.append(f"{path.name}: 答案编译失败\n{stderr[:600]}")
            else:
                exit_code, output = run_main(work)
                if exit_code != 0 or not output.strip().endswith("ALL PASS"):
                    problems.append(f"{path.name}: 答案没有全部通过\n{output[:800]}")

        # 演示块：编译并逐字比对输出
        if "<!-- run -->" in text:
            index = text.index("<!-- run -->")
            demo = None
            expected = None
            for language, body in blocks(text[index:]):
                if language == "java" and demo is None:
                    demo = body
                elif language == "text" and demo is not None and expected is None:
                    expected = body
            if demo is None or expected is None:
                problems.append(f"{path.name}: 演示块缺少 java 或 text")
            else:
                with tempfile.TemporaryDirectory() as raw:
                    work = pathlib.Path(raw)
                    code, stderr = javac(work, {"Main.java": demo})
                    if code != 0:
                        problems.append(f"{path.name}: 演示编译失败\n{stderr[:400]}")
                    else:
                        done = subprocess.run(
                            ["java", "-cp", ".", "Main"], cwd=work, capture_output=True,
                            text=True, encoding="gbk", errors="replace")
                        actual = (done.stdout or "").replace("\r\n", "\n").strip()
                        if actual != expected.strip():
                            problems.append(f"{path.name}: 演示输出不一致\n期望：{expected!r}\n实际：{actual!r}")

        checked += 1
        print(f"{path.name}: OK")

    if problems:
        print(f"\n{len(problems)} 个问题：")
        for item in problems:
            print(" -", item)
        return 1
    print(f"\n通过：{checked} 个实战关卡（starter 编译通过但不绿，答案全绿，演示输出一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
