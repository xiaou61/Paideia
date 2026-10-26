"""检查 MyBatis 的 `<script>` 块里有没有裸 `<` 与裸 `&`。

2026-10-26 真踩过：把几个 `@Select` 包进 `<script>` 之后，`started_at < #{to}` 里的 `<`
让 MyBatis 按 XML 解析注解时抛 `SAXParseException`，表现为服务一直 activating、健康检查
超时——**应用根本起不来**，而单元测试全绿（它们不加载 MyBatis 上下文）。

用法：python scripts/check_mapper_script_xml.py
"""
import io
import os
import re
import sys

TAG = re.compile(r'</?(?:script|if|choose|when|otherwise|foreach|where|set|trim|bind|include)\b')
ENTITY = re.compile(r'&(?:lt|gt|amp|quot|apos|#\d+|#x[0-9a-fA-F]+);')

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'backend')


def main() -> int:
    bad, checked = [], 0
    for dirpath, _, names in os.walk(ROOT):
        if os.sep + 'target' + os.sep in dirpath + os.sep:
            continue
        for name in sorted(names):
            if not name.endswith('.java'):
                continue
            path = os.path.join(dirpath, name)
            # 行级状态机而不是正则：注释里会写到 `<script>` 这个字面量（EntryMapper 有一处），
            # 正则从那里配对会吞掉后面正常的普通注解，产生假阳性。
            inside = False
            for i, line in enumerate(io.open(path, encoding='utf-8').read().splitlines(), 1):
                s = line.strip()
                if s == '<script>':
                    inside = True
                    checked += 1
                    continue
                if s == '</script>':
                    inside = False
                    continue
                if not inside or s.startswith('<!--') or s.startswith('//'):
                    continue
                if '<' in ENTITY.sub('', TAG.sub('', s)) or '&' in ENTITY.sub('', TAG.sub('', s)):
                    bad.append((path, i, s))

    for path, line, text in bad:
        print('%s:%d  %s' % (os.path.relpath(path, os.path.dirname(ROOT)), line, text[:110]))
    print('%d 个 script 块，%d 处问题' % (checked, len(bad)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
