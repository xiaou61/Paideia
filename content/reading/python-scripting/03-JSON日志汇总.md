---
title: JSON 日志汇总
category: python-scripting
order: 3
minutes: 25
level: beginner
status: published
---

## 这一关要做什么

从标准输入读 JSON Lines（每行一个 JSON 对象），聚合成一份固定格式的摘要：

```text
total=<有效行数>
INFO=<count>
WARN=<count>
ERROR=<count>
max=<最大 duration>
```

规则：

- 每行 `json.loads`；解析失败的行跳过；
- 必须同时有字符串 `level` 与整数 `duration`，否则跳过；
- 只统计 `INFO` / `WARN` / `ERROR` 三个级别，其余级别忽略；
- 没有有效行时：`total=0`、三个计数为 0、`max=0`。

## 先想清楚

日志处理的默认策略应该是**逐行容错**：一行坏了，跳过它，继续下一条。真实日志里总有截断的行、夹杂的调试输出。

```python
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        row = json.loads(line)
    except json.JSONDecodeError:
        continue
    level = row.get('level')
    duration = row.get('duration')
    if level not in ('INFO', 'WARN', 'ERROR'):
        continue
    if not isinstance(duration, int) or isinstance(duration, bool):
        continue
    ...
```

`isinstance(duration, bool)` 这个判断容易被忘：Python 里 `True` 是 `int` 的子类，`isinstance(True, int)` 为真。

## 自己写

```code-challenge
{
  "id": "pyscript-3-run",
  "kind": "run",
  "title": "JSON 日志汇总",
  "language": "python",
  "starter": "import json\nimport sys\n\n\ndef main():\n    total = 0\n    counts = {'INFO': 0, 'WARN': 0, 'ERROR': 0}\n    longest = 0\n    for line in sys.stdin:\n        line = line.strip()\n        if not line:\n            continue\n        row = json.loads(line)\n        level = row['level']\n        duration = row['duration']\n        counts[level] += 1\n        total += 1\n        longest = max(longest, duration)\n\n    print(f'total={total}')\n    print(f\"INFO={counts['INFO']}\")\n    print(f\"WARN={counts['WARN']}\")\n    print(f\"ERROR={counts['ERROR']}\")\n    print(f'max={longest}')\n\n\nif __name__ == '__main__':\n    main()\n",
  "cases": [
    {
      "name": "正常日志",
      "stdin": "{\"level\":\"INFO\",\"duration\":12}\n{\"level\":\"ERROR\",\"duration\":30}\n",
      "expected": "total=2\nINFO=1\nWARN=0\nERROR=1\nmax=30\n"
    },
    {
      "name": "跳过坏 JSON",
      "stdin": "{\"level\":\"INFO\",\"duration\":5}\nnot json\n{\"level\":\"WARN\",\"duration\":8}\n",
      "expected": "total=2\nINFO=1\nWARN=1\nERROR=0\nmax=8\n"
    },
    {
      "name": "跳过缺字段与未知级别",
      "stdin": "{\"level\":\"INFO\"}\n{\"duration\":9}\n{\"level\":\"DEBUG\",\"duration\":3}\n{\"level\":\"WARN\",\"duration\":4}\n",
      "expected": "total=1\nINFO=0\nWARN=1\nERROR=0\nmax=4\n"
    },
    {
      "name": "duration 必须是整数",
      "stdin": "{\"level\":\"INFO\",\"duration\":\"7\"}\n{\"level\":\"INFO\",\"duration\":true}\n{\"level\":\"INFO\",\"duration\":2}\n",
      "expected": "total=1\nINFO=1\nWARN=0\nERROR=0\nmax=2\n"
    },
    {
      "name": "全空输入",
      "stdin": "\n\n",
      "expected": "total=0\nINFO=0\nWARN=0\nERROR=0\nmax=0\n"
    }
  ],
  "explain": "每行先 strip 再 loads，解析失败直接 continue；字段要显式校验类型：`isinstance(duration, int) and not isinstance(duration, bool)`。输出五行固定顺序，即使计数为 0 也要打印——下游按键读取时缺行会被当成缺失数据。"
}
```

## 常见误区

### 误区：`json.loads` 失败会让脚本退出

**你以为**：坏行只能报错。
**其实**：它抛的是 `JSONDecodeError`（`ValueError` 的子类），用 try/except 跳过即可。日志里坏行是常态。
**怎么验证**：在输入里加一行 `not json`，看是否继续输出后续统计。

### 误区：有 `level` 字段就能直接用

**你以为**：字段存在就合法。
**其实**：值可能是 `null`、小写、未知级别。`counts[level] += 1` 遇到没见过的键会 KeyError；应该先判断 `level in counts`。
**怎么验证**：输入 `{"level":"debug","duration":1}`，看脚本是否崩。

### 误区：`isinstance(x, int)` 足以判断整数

**你以为**：True 是布尔，不是整数。
**其实**：`bool` 是 `int` 的子类，`isinstance(True, int)` 为真。日志里 `"duration": true` 会被当成 1。
**怎么验证**：REPL 里跑 `isinstance(True, int)`。

## 带走一句

**逐行解析、逐行容错、字段显式校验**——日志脚本的第一要求是"不因为一行坏数据而停"。

```quiz
{"id":"pyscript-03-quiz","title":"日志容错","questions":[
 {"question":"`json.loads` 遇到 `not json` 会怎样？","options":["返回 None","抛 JSONDecodeError，可用 try/except 跳过","返回空字典","自动修复"],"answer":1,"explain":"它是 ValueError 的子类；日志脚本应逐行捕获并继续。"},
 {"question":"为什么 `isinstance(True, int)` 会影响日志统计？","options":["True 不是数字","bool 是 int 的子类，`duration: true` 会被当成 1","会影响性能","JSON 不支持布尔"],"answer":1,"explain":"要额外排除 bool，否则布尔值会被计入整数耗时。"},
 {"question":"计数为 0 的级别为什么仍要输出？","options":["为了好看","固定行数是下游解析的契约，缺行会被当成缺失数据","因为 print 必须有内容","避免除零"],"answer":1,"explain":"输出格式是接口；固定五行让按键读取稳定。"}
]}
```
