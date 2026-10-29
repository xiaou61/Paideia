#!/usr/bin/env python3
"""课程源文件的结构与代码校验（WORK-020 TASK-001/002）。

三件事，一件都不连数据库、不连网络：

1. **结构**：frontmatter 字段齐全、七个小节按序出现、误区至少两条；
2. **可运行示例**：`<!-- run -->` 后面的代码块真的执行一次，输出必须与紧跟的 ```text 块逐字相同；
3. **围栏块**：`code-challenge` / `quiz` / `learning-path` 必须是合法 JSON，且关键上限不超。

上限只复刻**会让人写错的那几个**（id 长度、走读步数、小测题数），不做 schema.ts 的全量镜像——
两份规则各写一遍必然会漂移，真正的权威仍是前端 schema；这里挡住的是"明显超出"。

用法：

    python scripts/verify_course.py                      # 全部课程
    python scripts/verify_course.py --dir content/courses/python
    python scripts/verify_course.py --no-run             # 只查结构，不执行代码
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "content" / "courses"

# 学习者代码在沙箱里的运行时基线，必须与 `application.yml` 的
# `paideia.sandbox.java-image` / `python-image` 一致。本机版本超前时，示例可能在这里跑过、
# 却在沙箱里编译不过——沙箱才是学习者真正执行的地方。
SANDBOX_JAVA_MAJOR = 21
SANDBOX_PYTHON = (3, 12)

FRONTMATTER_KEYS = ("title", "category", "order", "minutes", "status")
# 总览另外要声明这条线是什么（WORK-020）：名字、类别、在清单里的顺序。
# 放在内容里而不是代码里，是为了"新增一条线不改代码"。
OVERVIEW_KEYS = ("line_name", "line_kind", "line_sort")
LINE_KINDS = ("course", "reading")
SECTIONS = (
    "这一课你要会",
    "为什么",
    "讲解",
    "动手",
    "检查一下",
    "常见误区",
    "小结",
)

LIMITS = {
    "id": 64,
    "title": 120,
    "steps": 20,
    # 与渲染器 schema.ts 的 LIMITS.pathSteps 一致：超过 20 步前端会把整块判为不合法，
    # 而本脚本却能通过——两边不同步会让"校验通过但页面不渲染"静默发生。
    "pathSteps": 20,
    "quizQuestions": 10,
    "challengeLines": 60,
    "codeChars": 20_000,
}

FENCE = re.compile(r"^```([A-Za-z0-9_-]*)\s*$", re.MULTILINE)
H2 = re.compile(r"^##\s+(.+?)\s*$", re.MULTILINE)
MISCONCEPTION = re.compile(r"^###\s+误区[：:].+$", re.MULTILINE)

failures: list[str] = []


def fail(path: Path, message: str) -> None:
    failures.append(f"{path.relative_to(ROOT)}: {message}")


def parse_frontmatter(path: Path, text: str) -> tuple[dict[str, str], str]:
    if not text.startswith("---"):
        raise ValueError("缺少 frontmatter（文件开头应是 ---）")
    end = text.find("\n---", 3)
    if end < 0:
        raise ValueError("frontmatter 没有结束标记 ---")
    raw = text[3:end].strip("\n")
    body = text[end + 4 :]
    meta: dict[str, str] = {}
    for line in raw.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if ":" not in line:
            raise ValueError(f"frontmatter 行格式不对：{line!r}")
        key, value = line.split(":", 1)
        meta[key.strip()] = value.strip()
    return meta, body


def fenced_blocks(body: str) -> list[tuple[str, str, int]]:
    """返回 (语言, 内容, 起始行号)。行号以文件开头为 1，供报错定位。"""
    blocks: list[tuple[str, str, int]] = []
    lines = body.splitlines()
    index = 0
    while index < len(lines):
        match = FENCE.match(lines[index])
        if match is None:
            index += 1
            continue
        language = match.group(1)
        start = index + 1
        index += 1
        content: list[str] = []
        while index < len(lines) and not lines[index].startswith("```"):
            content.append(lines[index])
            index += 1
        blocks.append((language, "\n".join(content), start))
        index += 1
    return blocks


def check_structure(path: Path, meta: dict[str, str], body: str) -> None:
    for key in FRONTMATTER_KEYS:
        if key not in meta or meta[key] == "":
            fail(path, f"frontmatter 缺少 {key}")
    if "order" in meta and not meta["order"].lstrip("-").isdigit():
        fail(path, "order 必须是整数")
    if meta.get("order", "").strip() == "0":
        for key in OVERVIEW_KEYS:
            if key not in meta or meta[key] == "":
                fail(path, f"总览的 frontmatter 缺少 {key}")
        if meta.get("line_kind") not in (None, "") and meta["line_kind"] not in LINE_KINDS:
            fail(path, f"line_kind 只能是 {' / '.join(LINE_KINDS)}")
    if meta.get("status") not in (None, "published"):
        fail(path, "status 只允许 published（草稿不要放进课程目录）")

    headings = [match.group(1).strip() for match in H2.finditer(body)]
    missing = [name for name in SECTIONS if name not in headings]
    if missing:
        fail(path, "缺少小节：" + "、".join(missing))
        return
    positions = [headings.index(name) for name in SECTIONS]
    if positions != sorted(positions):
        fail(path, "小节顺序不对，应为：" + " → ".join(SECTIONS))

    if len(MISCONCEPTION.findall(body)) < 2:
        fail(path, "常见误区至少要两条（每条以 `### 误区：` 开头）")


def check_blocks(path: Path, body: str) -> None:
    blocks = fenced_blocks(body)
    challenges = [(content, line) for language, content, line in blocks if language == "code-challenge"]
    quizzes = [(content, line) for language, content, line in blocks if language == "quiz"]
    paths = [(content, line) for language, content, line in blocks if language == "learning-path"]

    if not challenges:
        fail(path, "缺少 code-challenge 块")
    if len(quizzes) != 1:
        fail(path, f"应当恰好有一个 quiz 块，实际 {len(quizzes)} 个")

    for content, line in challenges:
        data = parse_json(path, content, "动手", line)
        if data is None:
            continue
        block_id = data.get("id")
        if not isinstance(block_id, str) or not block_id or len(block_id) > LIMITS["id"]:
            fail(path, f"动手块第 {line} 行：id 缺失或超过 {LIMITS['id']} 字符")
        kind = data.get("kind")
        if kind not in ("fill", "order", "bug", "run", "sql-write", "sql-fix"):
            fail(path, f"动手块 {block_id}：kind 只能是 fill/order/bug/run/sql-write/sql-fix")
        if kind in ("sql-write", "sql-fix"):
            # SQL 题的结构校验在 scripts/sql_cases.py 里（它要真连数据库跑参考解）；
            # 这里只挡住"少了必填字段"这类明显错误，避免课程门禁依赖数据库。
            for key in ("dataset", "expectHash", "starter"):
                if key not in data:
                    fail(path, f"SQL 题 {block_id}：缺少 {key}")
        if kind == "order":
            lines = data.get("lines")
            answer = data.get("answer")
            if not isinstance(lines, list) or not isinstance(answer, list):
                fail(path, f"动手块 {block_id}：order 需要 lines 与 answer")
            elif len(lines) > LIMITS["challengeLines"]:
                fail(path, f"动手块 {block_id}：行数超过 {LIMITS['challengeLines']}")
            elif sorted(answer) != list(range(len(lines))):
                fail(path, f"动手块 {block_id}：answer 必须是 0…{len(lines) - 1} 的排列")
        if kind == "bug":
            code = data.get("code")
            bug_line = data.get("bugLine")
            if not isinstance(code, str) or not isinstance(bug_line, int):
                fail(path, f"动手块 {block_id}：bug 需要 code 与 bugLine")
            elif not 1 <= bug_line <= len(code.splitlines()):
                fail(path, f"动手块 {block_id}：bugLine 超出代码行数")
        if kind == "fill":
            template = data.get("template")
            options = data.get("options")
            answers = data.get("answers")
            if not isinstance(template, str) or not isinstance(options, list) or not isinstance(answers, list):
                fail(path, f"动手块 {block_id}：fill 需要 template/options/answers")
            else:
                blanks = len(re.findall(r"\{\{\d+\}\}", template))
                if blanks != len(options) or len(options) != len(answers):
                    fail(path, f"动手块 {block_id}：空位数 {blanks}、options {len(options)}、answers {len(answers)} 必须一致")
                for index, answer in enumerate(answers):
                    if not isinstance(answer, int) or not 0 <= answer < len(options[index]):
                        fail(path, f"动手块 {block_id}：answers[{index}] 越界")
        if kind == "run":
            if data.get("language") not in ("java", "python"):
                fail(path, f"动手块 {block_id}：run 的 language 只能是 java 或 python")
            cases = data.get("cases")
            if not isinstance(cases, list) or not cases:
                fail(path, f"动手块 {block_id}：run 至少要一个用例")

    for content, line in quizzes:
        data = parse_json(path, content, "小测", line)
        if data is None:
            continue
        questions = data.get("questions")
        if not isinstance(questions, list):
            fail(path, f"小测第 {line} 行：缺少 questions")
            continue
        if not 1 <= len(questions) <= LIMITS["quizQuestions"]:
            fail(path, f"小测题数必须在 1–{LIMITS['quizQuestions']}")
        for index, question in enumerate(questions):
            if not isinstance(question, dict):
                fail(path, f"小测第 {index + 1} 题不是对象")
                continue
            options = question.get("options")
            answer = question.get("answer")
            if not isinstance(options, list) or not 2 <= len(options) <= 6:
                fail(path, f"小测第 {index + 1} 题：options 需要 2–6 项")
            elif not isinstance(answer, int) or not 0 <= answer < len(options):
                fail(path, f"小测第 {index + 1} 题：answer 越界")

    for content, line in paths:
        data = parse_json(path, content, "学习路径", line)
        if data is None:
            continue
        steps = data.get("steps")
        if not isinstance(steps, list) or not 1 <= len(steps) <= LIMITS["pathSteps"]:
            fail(path, f"学习路径第 {line} 行：steps 需要 1–{LIMITS['pathSteps']} 项")


def parse_json(path: Path, content: str, label: str, line: int):
    try:
        data = json.loads(content)
    except json.JSONDecodeError as error:
        fail(path, f"{label}块（第 {line} 行）不是合法 JSON：{error}")
        return None
    if not isinstance(data, dict):
        fail(path, f"{label}块（第 {line} 行）必须是对象")
        return None
    return data


def check_runnable(path: Path, body: str) -> None:
    """执行每一个 `<!-- run -->` 后面的代码块，与紧随的 ```text 输出逐字比对。"""
    lines = body.splitlines()
    blocks: list[tuple[int, str, int]] = []
    index = 0
    while index < len(lines):
        match = FENCE.match(lines[index])
        if match is None:
            index += 1
            continue
        start = index + 1
        language = match.group(1)
        index += 1
        content: list[str] = []
        while index < len(lines) and not lines[index].startswith("```"):
            content.append(lines[index])
            index += 1
        blocks.append((start, language, 0))
        blocks[-1] = (start, language, index)
        index += 1

    # 先找标记位置，再取它之后的第一段代码与第一段 text
    marker = re.compile(r"<!--\s*run\s*-->")
    found = 0
    for match in marker.finditer(body):
        marker_line = body[: match.start()].count("\n") + 1
        after = [block for block in blocks if block[0] > marker_line]
        if not after:
            fail(path, f"第 {marker_line} 行的 <!-- run --> 后面没有代码块")
            continue
        code_start, language, _ = after[0]
        code = "\n".join(lines[code_start : after[0][2]])
        if language not in ("python", "java", "sql"):
            fail(path, f"第 {marker_line} 行的可运行示例语言必须是 python / java / sql，实际 {language!r}")
            continue
        expected = None
        for candidate in after[1:]:
            if candidate[1] == "text":
                expected = "\n".join(lines[candidate[0] : candidate[2]])
                break
        if expected is None:
            fail(path, f"第 {marker_line} 行的可运行示例后面缺少 ```text 期望输出块")
            continue
        found += 1
        actual = run_code(language, code)
        if actual is None:
            fail(path, f"第 {code_start} 行的示例没能运行（见上方 stderr）")
        elif actual.strip() != expected.strip():
            fail(path, f"第 {code_start} 行的示例输出不一致\n  期望：{expected.strip()!r}\n  实际：{actual.strip()!r}")

    if found == 0:
        fail(path, "讲解里没有可运行示例（需要 `<!-- run -->` + 代码块 + ```text 输出）")


def run_code(language: str, code: str) -> str | None:
    if language == "sql":
        return run_sql(code)
    with tempfile.TemporaryDirectory(prefix="paideia-course-") as directory:
        workdir = Path(directory)
        env = dict(os.environ)
        env.setdefault("PYTHONIOENCODING", "utf-8")
        try:
            if language == "python":
                script = workdir / "main.py"
                script.write_text(code + "\n", encoding="utf-8")
                command = [sys.executable, str(script)]
            else:
                source = workdir / "Main.java"
                source.write_text(code + "\n", encoding="utf-8")
                javac = shutil.which("javac")
                if javac is None:
                    print("  跳过：本机没有 javac", file=sys.stderr)
                    return None
                compile_result = subprocess.run(
                    [javac, "-encoding", "UTF-8", "Main.java"],
                    cwd=workdir, capture_output=True, timeout=120, env=env)
                if compile_result.returncode != 0:
                    print(decode(compile_result.stderr), file=sys.stderr)
                    return None
                command = ["java", "-Dfile.encoding=UTF-8",
                           "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "Main"]
            result = subprocess.run(command, cwd=workdir, capture_output=True,
                                    timeout=60, env=env)
        except subprocess.TimeoutExpired:
            print("  超时（60s）", file=sys.stderr)
            return None
        if result.returncode != 0:
            print(decode(result.stderr), file=sys.stderr)
            return None
        return decode(result.stdout)


def run_sql(code: str) -> str | None:
    """在一个一次性库里执行整段 SQL，把每个结果集按 `列=值 | 列=值` 打出来。

    用独立库而不是既有库，是因为示例里有 CREATE/DROP/INSERT：跑在 paideia_test 上
    会删掉别人的数据。库名带 pid，用完即删。
    """
    user = os.environ.get("PAIDEIA_TEST_DB_USER")
    password = os.environ.get("PAIDEIA_TEST_DB_PASSWORD")
    if not user or password is None:
        print("  跳过：没有 PAIDEIA_TEST_DB_USER / PAIDEIA_TEST_DB_PASSWORD", file=sys.stderr)
        return None
    try:
        import pymysql
        from pymysql.constants import CLIENT
    except ImportError:
        print("  跳过：本机 Python 没有 pymysql（pip install pymysql）", file=sys.stderr)
        return None
    host = os.environ.get("PAIDEIA_TEST_DB_HOST", "127.0.0.1")
    port = int(os.environ.get("PAIDEIA_TEST_DB_PORT", "3306"))
    schema = f"paideia_course_{os.getpid()}"
    connection = None
    try:
        connection = pymysql.connect(host=host, port=port, user=user, password=password,
                                     charset="utf8mb4", autocommit=True,
                                     client_flag=CLIENT.MULTI_STATEMENTS)
        cursor = connection.cursor()
        cursor.execute(f"DROP DATABASE IF EXISTS `{schema}`")
        cursor.execute(f"CREATE DATABASE `{schema}`")
        cursor.execute(f"USE `{schema}`")
        cursor.execute(code)
        rows: list[str] = []
        while True:
            if cursor.description is not None:
                columns = [column[0] for column in cursor.description]
                for row in cursor.fetchall():
                    rows.append(" | ".join(
                        f"{name}={value}" if value is not None else f"{name}=NULL"
                        for name, value in zip(columns, row)))
            if not cursor.nextset():
                break
        return "\n".join(rows)
    except Exception as error:  # noqa: BLE001 - 任何驱动层错误都算"示例跑不了"
        print(f"  SQL 执行失败：{error}", file=sys.stderr)
        return None
    finally:
        if connection is not None:
            try:
                with connection.cursor() as cleanup:
                    cleanup.execute(f"DROP DATABASE IF EXISTS `{schema}`")
            except Exception:  # noqa: BLE001
                pass
            connection.close()


def decode(raw: bytes) -> str:
    """子进程输出按 UTF-8 解；Windows 控制台/GBK 残留按替换字符处理，不让校验脚本自己崩。"""
    return raw.decode("utf-8", errors="replace").replace("\r\n", "\n")


def verify_file(path: Path, run: bool) -> None:
    text = path.read_text(encoding="utf-8")
    try:
        meta, body = parse_frontmatter(path, text)
    except ValueError as error:
        fail(path, str(error))
        return
    # 总览（order 0）的职责是给出整条线的站序，不承载单课的讲解与练习
    if meta.get("order", "").strip() == "0":
        check_overview(path, body)
        return
    check_structure(path, meta, body)
    check_blocks(path, body)
    if run:
        check_runnable(path, body)


def check_overview(path: Path, body: str) -> None:
    """总览：只要一个 learning-path，且站数在 1–20 之间。"""
    blocks = fenced_blocks(body)
    paths = [(content, line) for language, content, line in blocks if language == "learning-path"]
    if len(paths) != 1:
        fail(path, f"总览应当恰好有一个 learning-path 块，实际 {len(paths)} 个")
        return
    content, line = paths[0]
    data = parse_json(path, content, "学习路径", line)
    if data is None:
        return
    steps = data.get("steps")
    if not isinstance(steps, list) or not 1 <= len(steps) <= LIMITS["pathSteps"]:
        fail(path, f"学习路径第 {line} 行：steps 需要 1–{LIMITS['pathSteps']} 项")
        return
    for index, step in enumerate(steps):
        if not isinstance(step, dict) or not isinstance(step.get("title"), str):
            fail(path, f"学习路径第 {index + 1} 站缺少 title")


def warn_if_runtime_ahead() -> None:
    """本机运行时比沙箱基线新时提醒。只提醒不拦截：本机装得比沙箱新是常态。"""
    try:
        out = subprocess.run(["javac", "-version"], capture_output=True, text=True, timeout=20)
        match = re.search(r"javac (\d+)", out.stdout + out.stderr)
        if match and int(match.group(1)) > SANDBOX_JAVA_MAJOR:
            print(f"  提醒：本机 javac {match.group(1)}，沙箱基线是 Java {SANDBOX_JAVA_MAJOR}——"
                  f"用了更新版本的语法，示例在这里能过、在沙箱里会编译失败", file=sys.stderr)
    except (OSError, subprocess.SubprocessError):
        pass
    if sys.version_info[:2] > SANDBOX_PYTHON:
        print(f"  提醒：本机 Python {sys.version_info.major}.{sys.version_info.minor}，"
              f"沙箱基线是 {SANDBOX_PYTHON[0]}.{SANDBOX_PYTHON[1]}——同上", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description="校验课程源文件")
    parser.add_argument("--dir", default=str(DEFAULT_DIR), help="课程目录或某一门语言子目录")
    parser.add_argument("--no-run", action="store_true", help="只查结构，不执行代码")
    arguments = parser.parse_args()

    root = Path(arguments.dir)
    if not root.is_absolute():
        root = ROOT / root
    if not root.is_dir():
        print(f"目录不存在：{root}", file=sys.stderr)
        return 2

    files = sorted(path for path in root.rglob("*.md") if path.name != "README.md")
    if not files:
        print(f"没有找到课程文件（{root}）——空目录视为通过")
        return 0

    if not arguments.no_run:
        warn_if_runtime_ahead()
    for path in files:
        verify_file(path, run=not arguments.no_run)

    if failures:
        print(f"\n{len(failures)} 个问题：\n")
        for failure in failures:
            print(" - " + failure)
        return 1

    print(f"通过：{len(files)} 个课程文件（{'未执行代码' if arguments.no_run else '含代码执行'}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
