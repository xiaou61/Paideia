"""检查 `content/` 里每道 Linux 判题练习的结构（WORK-056）。

**为什么要有这条守卫**：`judge` 是整道题唯一有判定价值的部分。写空、写成一条永远成功的
`true`、或者 seed 路径里带一个 `..`，在页面上都只是"点了就通过"或"点了就报错"，
而作者不会在自己的浏览器里逐题点一遍。这几条规则在导入前就能挡住。

检查项：

- `judge` 非空且不超 20000 字符（服务端与前端同一个上限）；
- `seed` 至多 20 个文件、单文件 ≤64000 字符、合计 ≤256000；
- seed 路径只允许 `[A-Za-z0-9_./-]`，不得以 `/` 开头、不得含 `..`
  （它会被拼进容器的写文件脚本）；
- `id` 全仓唯一（重复会让两块共用一份进度）。

用法：python scripts/check_shell_cases.py
退出码：0 = 全部通过；1 = 有问题。
"""
import io
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'content')
BLOCK = re.compile(r'```code-challenge\n(.*?)\n```', re.S)
PATH_OK = re.compile(r'^[A-Za-z0-9_./-]+$')

MAX_JUDGE = 20000
MAX_SEED_FILES = 20
MAX_SEED_FILE = 64000
MAX_SEED_TOTAL = 256000


def check_shell(data):
    """返回这道题的毛病列表；非 shell 题返回空列表。"""
    problems = []
    judge = data.get('judge')
    if not isinstance(judge, str) or not judge.strip():
        problems.append('judge 为空（这道题会永远判不出结果）')
    elif len(judge) > MAX_JUDGE:
        problems.append('judge 超过 %d 字符' % MAX_JUDGE)

    starter = data.get('starter')
    if starter is not None and len(starter) > MAX_JUDGE:
        problems.append('starter 超过 %d 字符' % MAX_JUDGE)

    seed = data.get('seed') or []
    if not isinstance(seed, list):
        problems.append('seed 必须是数组')
        return problems
    if len(seed) > MAX_SEED_FILES:
        problems.append('seed 超过 %d 个文件' % MAX_SEED_FILES)
    total = 0
    for index, item in enumerate(seed):
        if not isinstance(item, dict):
            problems.append('seed[%d] 不是对象' % index)
            continue
        path = item.get('path')
        if not isinstance(path, str) or not path:
            problems.append('seed[%d] 缺 path' % index)
            continue
        # 与后端 validateFiles 同一条信任边界：它会进容器的写文件脚本
        if not PATH_OK.match(path) or path.startswith('/') or '..' in path:
            problems.append('seed[%d] 路径不合法：%s' % (index, path))
        content = item.get('content') or ''
        if not isinstance(content, str):
            problems.append('seed[%d] content 必须是字符串' % index)
            continue
        if len(content) > MAX_SEED_FILE:
            problems.append('seed[%d] 内容超过 %d 字符' % (index, MAX_SEED_FILE))
        total += len(content)
    if total > MAX_SEED_TOTAL:
        problems.append('seed 合计 %d 字符，超过上限 %d' % (total, MAX_SEED_TOTAL))
    return problems


def main() -> int:
    total_blocks = 0
    shell_blocks = 0
    problems = []
    seen_ids = {}

    for dirpath, _, names in os.walk(ROOT):
        for name in sorted(names):
            if not name.endswith('.md'):
                continue
            path = os.path.join(dirpath, name)
            text = io.open(path, encoding='utf-8').read()
            for match in BLOCK.finditer(text):
                total_blocks += 1
                lineno = text[: match.start()].count('\n') + 1
                where = '%s:%d' % (os.path.relpath(path, os.path.dirname(ROOT)), lineno)
                try:
                    data = json.loads(match.group(1))
                except Exception as failure:
                    problems.append('%s  JSON 非法：%s' % (where, failure))
                    continue
                if not isinstance(data, dict) or data.get('kind') != 'shell':
                    continue
                shell_blocks += 1
                block_id = data.get('id')
                if not isinstance(block_id, str) or not block_id:
                    problems.append('%s  shell 题缺 id' % where)
                elif block_id in seen_ids:
                    problems.append('%s  id 重复：%s（首次出现于 %s）'
                                    % (where, block_id, seen_ids[block_id]))
                else:
                    seen_ids[block_id] = where
                for problem in check_shell(data):
                    problems.append('%s  %s' % (where, problem))

    for item in problems:
        print(item)
    print('%d 个动手块（其中 shell %d 道），%d 处问题'
          % (total_blocks, shell_blocks, len(problems)))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
