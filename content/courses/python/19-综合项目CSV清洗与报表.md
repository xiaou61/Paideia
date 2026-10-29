---
title: 19 · 综合项目：把 CSV 清洗成报表
category: python-from-zero
order: 19
chapter: 综合项目
level: advanced
minutes: 35
prerequisite: 18 · 小项目
status: published
---

# 19 · 综合项目：把 CSV 清洗成报表

## 这一课你要会

- 用标准库 `csv` 读带引号、逗号的表格，知道它为什么比 `split(",")` 可靠；
- 认出清洗里的四类坏数据：空值、重复、类型转换失败、日期不合法；
- 用 `collections.Counter` 计数、`defaultdict` 求和；
- 把读取、清洗、聚合、输出拆成能单独写 `assert` 的函数；
- 用 `argparse` 与 `pathlib` 给工具加一个命令行入口。

## 为什么

前面写的脚本大多是"数据刚好干净"的练习。真实 CSV 不是这样：导出的文件里会有空金额、重复导入、`"None"`、`2026-13-01`，还会因为 Excel 的编码和 BOM 让你在第一行就报错。

这一课做一个完整的小工具：**读 CSV → 跳过说不清的行 → 按分类聚合 → 打印汇总**。它只有一百来行，但包含真实数据脚本的四个边界：解析、清洗、聚合、输出。把这四层分开，你才知道出错时该改哪一层。

## 讲解

### 为什么用 csv 模块，而不是 split(",")

看起来一行 CSV 就是逗号分隔，`line.split(",")` 就够了。下面这一行会告诉你为什么不够：

```python
line = '2026-10-01,书籍,"Python 入门, 第 2 版",45.00'
print(line.split(","))
# ['2026-10-01', '书籍', '"Python 入门', ' 第 2 版"', '45.00']
```

书名里的逗号被当成了分隔符，一条记录变成五段。`csv` 模块还处理这些情况：

- 字段里的双引号写成 `""`；
- 字段里带换行（是的，一个单元格可以跨两行）；
- Windows 的 `\r\n` 换行；
- 表头到列的映射（`csv.DictReader`）。

读文件时要写 `newline=""`：

```python
with open("data.csv", newline="", encoding="utf-8-sig") as handle:
    rows = csv.DictReader(handle)
```

`newline=""` 是 `csv` 模块的要求，让它自己处理换行；不写的话，带引号换行的字段会多出空行。`utf-8-sig` 会吃掉 Excel 导出文件开头的 BOM，否则第一个列名会变成 `"\ufeffdate"`，你怎么取都取不到。

### 清洗的四条边界

先把"坏"分类，再决定每类怎么处理。最怕的是不分类就一句 `try/except` 全吞掉。

| 问题 | 典型样子 | 这一课的处理 | 不能怎么做 |
| --- | --- | --- | --- |
| 空值 | `amount` 为空、`"None"`、`category` 为空 | 跳过，并计入跳过数 | 当成 `0` 参与求和 |
| 重复 | 同一条记录被导入两次 | 定义 key，保留第一条，计入跳过数 | 用 `set(rows)` 一把梭 |
| 类型转换失败 | `"abc"`、`"1,200"`、全角数字 | 捕获 `ValueError`，跳过并计数 | `except: pass` |
| 日期不合法 | `2026-13-01`、`10/01/2026` | `strptime` 校验，跳过并计数 | 直接按字符串排序或比较 |

三个判断标准：

1. **空值不等于零。** 金额空着表示"不知道花了多少"，填 0 会让合计和均值都失真。要么跳过，要么单独归到"未分类"，但不能悄悄当 0。
2. **重复要先定义 key。** 整行完全相同是一类；同一天、同一分类、同一金额但备注不同，是另一类。key 选错，要么漏删，要么把两笔真实消费当成重复。
3. **转换失败要报告。** 跳过是对的，但必须让用户知道跳过了多少、为什么跳。静默跳过比崩溃更难查。

示例为了短，只统计"跳过了几行"。生产里建议再用一个 `Counter` 按原因计数，例如 `skipped["金额为空"] += 1`，最后一起打印。

### 把逻辑拆成可测函数

```text
read_rows(text)     用 csv 读成一行行 dict（只有这一层碰 CSV 格式）
parse_record(row)   校验并转换一条记录；坏行返回 None（纯函数）
load(rows)          逐行清洗、去重，返回 (records, skipped)
summarize(records)  按分类计数与求和（纯函数）
print_report(...)   只负责打印
run(text)           把上面几步串起来，测试用它代替文件
```

这样拆的好处很直接：`parse_record` 接收一个 dict，测试时不用造文件；`summarize` 接收一个列表，测试时不用碰 CSV。真正容易出错的转换和聚合，都能用一行 `assert` 盯住。

### Counter 与 defaultdict

```python
from collections import Counter, defaultdict

counts = Counter()
totals = defaultdict(float)

for record in records:
    counts[record["category"]] += 1
    totals[record["category"]] += record["amount"]

print(counts.most_common())
print(dict(totals))
```

- `Counter` 是"计数"的工具：`counts[key] += 1` 时 key 不存在会从 0 开始，`most_common()` 直接给出排行。
- `defaultdict(float)` 是"求和"的工具：第一次遇到某个分类时自动给 `0.0`，不用先判断 key 在不在。
- 普通 `dict` 用 `totals.get(category, 0) + amount` 也完全正确；`defaultdict` 只是把"默认值"写进了类型里。
- **不要用 `Counter` 求和，也不要用 `defaultdict(list)` 求和。** 工具用错不会立刻报错，但结果会是列表拼字符串或者类型错误。

`Counter` 和 `defaultdict` 都只解决"少写两行"，真正的价值是让意图一眼可见：看到 `Counter` 就知道在数个数。

### datetime 只在边界用一次

日期在 CSV 里是字符串，在程序里应该是 `date` 对象。转换发生在读入时：

```python
from datetime import datetime

day = datetime.strptime("2026-10-01", "%Y-%m-%d").date()
```

之后比较、排序、算间隔都用 `date`，不要再拿字符串比大小。输出时再格式化回字符串。

注意 `strptime` 只认你给的格式：`%Y-%m-%d` 不会接受 `10/01/2026`。格式不统一时，要么在入口统一，要么明确拒绝，不要写一串 `if` 猜格式。

### 命令行入口：argparse 与 pathlib

```python
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="按分类汇总 CSV")
    parser.add_argument("csv_file", type=Path)
    parser.add_argument("--encoding", default="utf-8-sig")
    args = parser.parse_args()

    with args.csv_file.open(newline="", encoding=args.encoding) as handle:
        records, skipped = load(csv.DictReader(handle))
    print_report(records, skipped)


if __name__ == "__main__":
    main()
```

三个细节：

- `type=Path` 让 `args.csv_file` 直接是路径对象，后面不用再拼字符串；
- `Path.open(newline="", encoding=...)` 和内置 `open` 参数一致，但路径处理更省心；
- `if __name__ == "__main__"` 让测试可以 `import` 这个文件而不执行 `main`。

**读取与打印分在不同函数**，是为了让 `load` / `summarize` 能在测试里跑，而不用真的执行命令行。

### 完整例子：内嵌一段 CSV

下面不读外部文件，用 `io.StringIO` 把样例 CSV 当成文件交给 `csv.DictReader`。样例里埋了三种坏行：金额为空、整行重复、金额不是数字。

<!-- run -->
```python
import csv
import io
from collections import Counter, defaultdict
from datetime import datetime

SAMPLE = """date,category,amount,note
2026-10-01,书籍,45.00,Python 入门
2026-10-01,餐饮,32.50,午饭
2026-10-02,书籍,,缺金额
2026-10-02,交通,18.00,
2026-10-01,餐饮,32.50,午饭
2026-10-03,书籍,None,坏金额
"""


def parse_amount(raw):
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None


def parse_record(row):
    date_text = (row.get("date") or "").strip()
    category = (row.get("category") or "").strip()
    amount_text = (row.get("amount") or "").strip()
    if not date_text or not category or not amount_text:
        return None
    try:
        day = datetime.strptime(date_text, "%Y-%m-%d").date()
    except ValueError:
        return None
    amount = parse_amount(amount_text)
    if amount is None:
        return None
    return {
        "day": day,
        "category": category,
        "amount": amount,
        "note": (row.get("note") or "").strip(),
    }


def load(rows):
    records = []
    skipped = 0
    seen = set()
    for row in rows:
        record = parse_record(row)
        if record is None:
            skipped += 1
            continue
        key = (record["day"], record["category"], record["amount"], record["note"])
        if key in seen:
            skipped += 1
            continue
        seen.add(key)
        records.append(record)
    return records, skipped


def summarize(records):
    totals = defaultdict(float)
    counts = Counter()
    for record in records:
        totals[record["category"]] += record["amount"]
        counts[record["category"]] += 1
    return totals, counts


def print_report(records, skipped):
    totals, counts = summarize(records)
    print(f"读取 {len(records) + skipped} 行，保留 {len(records)} 行，跳过 {skipped} 行")
    for category in sorted(totals):
        print(f"- {category}: {counts[category]} 笔，{totals[category]:.2f}")
    print(f"合计: {len(records)} 笔，{sum(totals.values()):.2f}")


def run(text):
    rows = csv.DictReader(io.StringIO(text))
    records, skipped = load(rows)
    print_report(records, skipped)
    return records, skipped


records, skipped = run(SAMPLE)
assert len(records) == 3 and skipped == 3
assert [record["category"] for record in records] == ["书籍", "餐饮", "交通"]
print("断言通过")
```
```text
读取 6 行，保留 3 行，跳过 3 行
- 书籍: 1 笔，45.00
- 交通: 1 笔，18.00
- 餐饮: 1 笔，32.50
合计: 3 笔，95.50
断言通过
```

读输出时注意三件事：

- **读取 6 行**是数据行，不含表头；`len(records) + skipped` 就是读到的总行数。
- **保留 3 行**：45.00、32.50、18.00。空金额、重复行、`"None"` 各跳过一条。
- **合计 95.50** 是三个分类之和。分类按字符排序，所以顺序是"书籍、交通、餐饮"，不是金额顺序。

这份实现把记录留在列表里，方便断言。文件很大时不必留全部记录：把清洗与聚合合成一次遍历，边读边累加，内存就只跟分类数有关。

## 动手

补上 `except` 里要捕获的异常。`float(None)` 抛 `TypeError`，`float("abc")` 抛 `ValueError`，两种都要接住。

```code-challenge
{
  "id": "py-19-fill",
  "kind": "fill",
  "title": "跳过转换失败的金额",
  "language": "python",
  "template": "def parse_amount(raw):\n    try:\n        return float(raw)\n    except {{0}}:\n        return None",
  "options": [["(TypeError, ValueError)", "Exception", "ValueError"]],
  "answers": [0],
  "explain": "float(None) 抛 TypeError，float(\"abc\") 抛 ValueError，两个都要接住。只写 ValueError 会在空值处崩；写 Exception 会把真正的代码 bug 也一起吞掉。"
}
```

## 检查一下

```quiz
{
  "id": "py-19-quiz",
  "title": "CSV 清洗三问",
  "questions": [
    {
      "question": "为什么读 CSV 应该用 csv 模块，而不是 split(\",\")？",
      "options": [
        "因为 csv 模块运行更快",
        "因为字段里可能有逗号、引号或换行，split 会把一条记录切错",
        "因为 split 只能处理英文",
        "因为 csv 模块会自动修正坏数据"
      ],
      "answer": 1,
      "explain": "split 不认识引号转义和字段内换行；csv 模块才按 CSV 规则解析。它不会替你修正业务数据。"
    },
    {
      "question": "某行 amount 为空，最合适的默认处理是什么？",
      "options": [
        "当成 0，继续求和",
        "跳过，并记录跳过原因和数量",
        "用上一行的金额补上",
        "让整个程序立刻崩溃，提醒用户"
      ],
      "answer": 1,
      "explain": "空值表示不知道，不是 0。跳过并报告既保住其余数据，又不会把缺失混进合计。"
    },
    {
      "question": "下面哪个函数最适合直接写 assert 测试？",
      "options": [
        "main",
        "print_report",
        "parse_record",
        "打开文件的代码"
      ],
      "answer": 2,
      "explain": "parse_record 只依赖一个 dict，不碰文件、不打印，输入输出都能直接断言。"
    }
  ]
}
```

## 常见误区

### 误区：空值填 0 最省事

**你以为**：0 不影响求和，还能少写分支。
**其实**：0 是"花了 0 元"，空值是"不知道花了多少"。填 0 会让合计、均值、笔数全部失真，而且错得很安静。
**怎么验证**：造一条空金额和一条 0 金额，跑同一个汇总；如果两者输出一样，说明把"缺失"和"零"混了。

### 误区：去重就是 `set(rows)`

**你以为**：每行是一个元素，放进 `set` 就去重了。
**其实**：`csv.DictReader` 产出的是 `dict`，不可哈希，直接 `set(rows)` 会抛 `TypeError`。而且"整行完全一样"和"业务上是同一笔"是两件事，前者能自动判断，后者要你先定义 key。
**怎么验证**：对 `DictReader` 的结果做 `set()`，看是否报错；再换成一个由字段组成的 tuple 作为 key，观察保留的是哪一条。

### 误区：转换失败就该让程序崩

**你以为**：崩了才知道数据有问题，跳过就是掩盖错误。
**其实**：一条坏行不该毁掉整批数据，但静默跳过同样糟糕。正确做法是**跳过并报告**：告诉用户跳过了多少行、分别因为什么。
**怎么验证**：在样例里加一条 `amount=abc`，看输出的跳过数是否加一；如果没有任何变化，就是被静默吞了。

### 误区：金额用 float 就够了

**你以为**：两位小数，`float` 足够精确。
**其实**：二进制浮点表示不了 0.1，累加会出现 `0.30000000000000004` 这种结果。展示到两位小数通常看不出来，但对账会差几分钱。
**怎么验证**：运行 `print(0.1 + 0.2)`。真实记账用 `decimal.Decimal`，或者把金额换成整数分。

## 小结

- 解析用 `csv`（配 `newline=""`、`utf-8-sig`），不要 `split(",")`；
- 清洗先分类：空值、重复、转换失败、日期不合法，每类都有明确的处理与计数；
- 计数用 `Counter`，求和用 `defaultdict(float)`，别把工具用反；
- 把 `parse_record`、`load`、`summarize` 写成不碰文件的函数，`print_report` 只负责输出；
- 用 `io.StringIO` 内嵌样例，测试就不需要外部文件。

下一课换一个方向：不写新功能，专门梳理那些"看起来对、跑起来错"的常见错误，以及拿到 traceback 之后该怎么查。
