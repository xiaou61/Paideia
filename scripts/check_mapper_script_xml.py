"""检查 MyBatis 注解里 `<script>` 块与实体的方向对不对。

两个方向的错都会真踩过：

1. **`<script>` 块里写了裸 `<`**（2026-10-26）：`started_at < #{to}` 里的 `<` 让 MyBatis
   按 XML 解析注解时抛 `SAXParseException`，表现为服务一直 activating、健康检查超时——
   **应用根本起不来**，而单元测试全绿（它们不加载 MyBatis 上下文）。
2. **不带 `<script>` 的块里写了 `&lt;`**（2026-10-27）：修第 1 条时把全文件的 `<` 都换成了
   `&lt;`，但**只有 `<script>` 块会被当 XML 反转义**。于是 `countByTool` 这类普通 `@Select`
   把字面量 `&lt;` 发给 MySQL，`You have an error in your SQL syntax near ''2026-10-27 02:57:12'`
   —— 管理端「AI 调用」概览整页 500，而单测（不连库）照样绿。

用法：python scripts/check_mapper_script_xml.py
"""
import io
import os
import re
import sys

TAG = re.compile(r'</?(?:script|if|choose|when|otherwise|foreach|where|set|trim|bind|include)\b')
ENTITY = re.compile(r'&(?:lt|gt|amp|quot|apos|#\d+|#x[0-9a-fA-F]+);')
# 一个 @Select/@Update/... 注解的字符串体（文本块或单行）
ANNOTATION = re.compile(r'@(?:Select|Update|Insert|Delete)\(\s*"""(.*?)"""', re.S)

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'backend')


def check_plain_blocks(path: str, text: str, bad: list) -> int:
    """不带 <script> 的注解块里出现实体转义就是错的：MyBatis 不会反转义它们。"""
    checked = 0
    for m in ANNOTATION.finditer(text):
        body = m.group(1)
        if '<script>' in body:
            continue
        checked += 1
        for offset, line in enumerate(body.splitlines()):
            if ENTITY.search(line) is None:
                continue
            # 排除注释里写到实体的情形（说明性文字，不进 SQL）
            if line.strip().startswith('//') or line.strip().startswith('<!--'):
                continue
            lineno = text[: m.start(1)].count('\n') + 1 + offset
            bad.append((path, lineno, line.strip(), 'plain-block-entity'))
    return checked


def main() -> int:
    bad, checked = [], 0
    plain_checked = 0
    for dirpath, _, names in os.walk(ROOT):
        if os.sep + 'target' + os.sep in dirpath + os.sep:
            continue
        for name in sorted(names):
            if not name.endswith('.java'):
                continue
            path = os.path.join(dirpath, name)
            text = io.open(path, encoding='utf-8').read()
            plain_checked += check_plain_blocks(path, text, bad)
            # 行级状态机而不是正则：注释里会写到 `<script>` 这个字面量（EntryMapper 有一处），
            # 正则从那里配对会吞掉后面正常的普通注解，产生假阳性。
            inside = False
            for i, line in enumerate(text.splitlines(), 1):
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
                    bad.append((path, i, s, 'script-block-raw'))

    for path, line, text, kind in bad:
        print('%s:%d  [%s]  %s' % (os.path.relpath(path, os.path.dirname(ROOT)), line, kind, text[:110]))
    print('%d 个 script 块、%d 个普通块，%d 处问题' % (checked, plain_checked, len(bad)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
