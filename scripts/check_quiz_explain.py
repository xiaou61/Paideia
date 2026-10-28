"""检查 `content/` 里每个 quiz 块的每道题都有解析（WORK-048）。

**为什么要一条守卫**：解析是题目唯一有价值的部分。没有它的题答完只告诉你"对了/错了"，
读者学不到任何东西。而 `explain` 在 schema 里是可选的——写漏了不会报错，只会在页面上
显示"这道题还没有解析"。

现状（2026-10-28）：139 个 quiz 块、**0 道题缺解析**。所以这条检查是严格模式而不是告警：
存量是干净的，没有理由让新增内容把它拖脏。

用法：python scripts/check_quiz_explain.py
退出码：0 = 全部有解析；1 = 有题目缺解析或 JSON 非法。
"""
import io
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'content')
BLOCK = re.compile(r'```quiz\n(.*?)\n```', re.S)


def main() -> int:
    total_blocks = 0
    total_questions = 0
    bad = []

    for dirpath, _, names in os.walk(ROOT):
        for name in sorted(names):
            if not name.endswith('.md'):
                continue
            path = os.path.join(dirpath, name)
            text = io.open(path, encoding='utf-8').read()
            for match in BLOCK.finditer(text):
                total_blocks += 1
                lineno = text[: match.start()].count('\n') + 1
                try:
                    data = json.loads(match.group(1))
                except Exception as failure:
                    bad.append((path, lineno, 'JSON 非法：%s' % failure))
                    continue
                questions = data.get('questions') or []
                if not questions:
                    bad.append((path, lineno, '没有题目'))
                    continue
                for index, question in enumerate(questions):
                    total_questions += 1
                    explain = question.get('explain')
                    if not isinstance(explain, str) or not explain.strip():
                        bad.append((path, lineno, '第 %d 题缺 explain' % (index + 1)))

    for path, lineno, message in bad:
        print('%s:%d  %s' % (os.path.relpath(path, os.path.dirname(ROOT)), lineno, message))
    print('%d 个 quiz 块、%d 道题，%d 处问题' % (total_blocks, total_questions, len(bad)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
