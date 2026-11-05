import io
import json
import re

p = 'backend/paideia-app/src/main/resources/db/devdata/V953__seed_source_reading.sql'
sql = io.open(p, encoding='utf-8').read()

start = sql.index('CONCAT(')
end = sql.index("'published'", start)
block = sql[start:end]
parts = re.findall(r"'((?:[^'\\]|\\.|'')*)'", block, re.S)

BACKSLASH = chr(92)
QUOTE = chr(39)

mapping = {'n': '\n', 't': '\t', 'r': '\r', '0': '\0', BACKSLASH: BACKSLASH, QUOTE: QUOTE}


def unescape(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == BACKSLASH and i + 1 < len(s):
            out.append(mapping.get(s[i + 1], s[i + 1]))
            i += 2
        elif c == QUOTE and i + 1 < len(s) and s[i + 1] == QUOTE:
            out.append(QUOTE)
            i += 2
        else:
            out.append(c)
            i += 1
    return ''.join(out)


body = ''.join(unescape(x) for x in parts)
print('body len', len(body))
fences = re.findall(r"```([\w-]+)\n(.*?)```", body, re.S)
print('fences:', [(name, len(content)) for name, content in fences])
for name, content in fences:
    if name in ('code-tour', 'code-map', 'code-flow', 'code-challenge', 'quiz', 'learning-path'):
        try:
            obj = json.loads(content)
            print('OK', name, 'keys=', list(obj)[:6])
        except Exception as exc:  # noqa: BLE001
            print('BAD', name, exc)
            print(content[:500])
