---
title: 19 · 综合项目：把 CSV 清洗成报表
category: python-from-zero
order: 19
chapter: 综合项目
level: advanced
minutes: 22
prerequisite: 18 · 小项目
status: published
---

# 19 · 综合项目：把 CSV 清洗成报表

## 第一列明明叫 date，程序说没有这个键

`expenses.csv` 是一份八行账目，照着记账软件导出的样子手写的（带 BOM、全角数字、重复行）。第一版脚本只想打印第一行：

```python
# first_try.py
import csv

with open("expenses.csv", encoding="utf-8", newline="") as handle:
    row = next(csv.DictReader(handle))
print(list(row))
print(row["date"])
```

```text
PS> python first_try.py
['\ufeffdate', 'category', 'amount', 'note']
Traceback (most recent call last):
  File "...\first_try.py", line 6, in <module>
    print(row["date"])
          ~~~^^^^^^^^
KeyError: 'date'
```

（临时目录的路径省成了 `...`，其余字符没动。）

报错里那个 `'date'` 和文件里的 `date` 看起来一模一样。但第一行打印出来是 `['\ufeffdate', ...]`：`\ufeff` 是 Python 把不可见字符转义出来给你看，而报错信息里的键名前面也粘着同一个字符，只是终端画不出来。

怀疑它，就去数字节：

```python
print(open("expenses.csv", "rb").read()[:8])
```

```text
b'\xef\xbb\xbfdate,'
```

文件头三个字节是 BOM（`EF BB BF`）。它不在 CSV 规范里——RFC 4180 没有这一节——是导出工具加的：Excel 里选「CSV UTF-8」另存会写，选普通的「CSV（逗号分隔）」就不写。所以拿到一份文件，开头有没有这三个字节只能自己看，不能假定。用 `encoding="utf-8"` 读，这三个字节被解码成一个 U+FEFF 字符，粘在第一个列名上，于是 `"\ufeffdate"` 和 `"date"` 是两个不同的键。

修法不是把键名写成 `row["\ufeffdate"]`：那等于把这一份文件的 BOM 抄进代码，换一份没 BOM 的导出又对不上，而且每个列名都得挨个改。修法是换编码：

```python
with open("expenses.csv", encoding="utf-8-sig", newline="") as handle:
    rows = list(csv.DictReader(handle))
print(len(rows))
print(rows[0])
print(rows[6])
```

```text
8
{'date': '2026-10-01', 'category': '书籍', 'amount': '45.00', 'note': 'Python 入门, 第 2 版'}
{'date': '2026-10-04', 'category': '餐饮', 'amount': '１２０.５０', 'note': '全角数字'}
```

`utf-8-sig` 不是另一种编码，就是 UTF-8 外加「开头有 BOM 就吃掉」。但别顺手把它当默认值：写文件时用 `utf-8-sig`，Python 会主动加一个 BOM 进去（实测三个字节 `b'\xef\xbb\xbf'` 出现在 `date` 前面），等于给下一个读文件的人埋同一个坑。**读用 `utf-8-sig`，写用 `utf-8`。**

## split(",") 把书名切成两半

BOM 修好之后我先偷了个懒，用 `split(",")` 切第一行数据：

```python
line = open("expenses.csv", encoding="utf-8-sig").read().splitlines()[1]
print(len(line.split(",")), line.split(","))
```

```text
5 ['2026-10-01', '书籍', '45.00', '"Python 入门', ' 第 2 版"']
```

一行四列被切成五段，还多了两个带引号的碎片。

`split` 没坏，是它根本不知道 CSV 规则：被引号包起来的逗号是字段内容，不是分隔符；字段里的 `""` 表示一个双引号。`csv` 模块知道这些，它还负责表头到字段名的映射（`DictReader`）和字段内的换行。

还有一个参数很容易被跳过：`newline=""`。它不是可有可无的讲究，写的时候不写它就会改字节。我用 `csv.writer` 写两行、其中一格带换行，只改这一个参数：

```python
rows = [["date", "note"], ["2026-10-01", "第一行\n第二行"]]
for label, newline in (("with", ""), ("without", None)):
    with open(f"{label}.csv", "w", encoding="utf-8", newline=newline) as handle:
        csv.writer(handle).writerows(rows)
    print(label, open(f"{label}.csv", "rb").read().split(b"\n")[0])
```

```text
with b'date,note\r'
without b'date,note\r\r'
```

不写 `newline=""`，每行尾巴就多一个 `\r`：`csv.writer` 自己已经写了 `\r\n`，文本模式又把其中的 `\n` 翻译成 `\r\n`，拼成 `\r\r\n`。多出来的这个 `\r` 落在最后一个字段末尾——按 `\n` 切行、只去掉 `\n` 的代码会让最后一列带着它（实测首行 `rstrip(b"\n")` 之后是 `b'date,note\r\r'`），比较字符串或再写进别的文件时就露出来了。`csv` 负责换行，`open` 就别再管。

## 八行数据里有四行不能用，其中一行是我判错的

```text
date,category,amount,note
2026-10-01,书籍,45.00,"Python 入门, 第 2 版"
2026-10-01,餐饮,32.50,午饭
2026-10-02,书籍,,缺金额
2026-10-02,交通,18.00,
2026-10-01,餐饮,32.50,午饭
2026-10-03,书籍,None,坏金额
2026-10-04,餐饮,１２０.５０,全角数字
2026-13-01,交通,8.00,坏日期
```

四类坏数据，处理方式各不相同：

- **空金额**（第 3 行）：空着是「不知道花了多少」，不是 0。填 0 会同时污染合计和笔数。
- **字面量 `"None"`**（第 6 行）：这是字符串，不是 Python 的 `None`。导出工具把缺失写成四个字母，它能通过「非空」检查，到 `float()` 才炸。
- **坏日期**（第 8 行）：`2026-13-01`，报错原文是 `ValueError: time data '2026-13-01' does not match format '%Y-%m-%d'`。`datetime.strptime(text, "%Y-%m-%d")` 按给定格式解析，解析不了就抛 `ValueError`；它不会告诉你「月份超范围」。
- **重复**（第 5 行和第 2 行四个字段全同）：同一次导入跑了两遍。

第 7 行（全角金额）我原来也归进「坏数据」，还写了「全角数字会导致类型转换失败」。跑了一下，是我错：

```python
for raw in ("120.50", "１２０.５０", "1,200"):
    try:
        print(raw, "->", float(raw))
    except ValueError as error:
        print(raw, "-> ValueError:", error)
```

```text
120.50 -> 120.5
１２０.５０ -> 120.5
1,200 -> ValueError: could not convert string to float: '1,200'
```

全角 `０`–`９` 在 Unicode 里属于十进制数字（Nd 类），当前 CPython 会先把它们换成 ASCII 再交给解析器，所以全角金额被安静地当成 120.5 收进合计，而不是被跳过。这是当前 CPython 的实现行为，不是 `float` 的承诺——`float` 的文档只保证接受数字字面量。真正会抛 `ValueError` 的是千分位 `1,200` 和带货币符号的 `¥120`。

还有一处容易混：`DictReader` 里「单元格为空」和「整列缺失」不是一回事。行比表头短时，缺的那列拿到 `None`；单元格存在但空着，拿到空串：

```python
short = "date,category,amount,note\n2026-10-02,交通,18.00\n"
print(repr(next(csv.DictReader(io.StringIO(short)))["note"]))
empty = "date,category,amount,note\n2026-10-02,交通,18.00,\n"
print(repr(next(csv.DictReader(io.StringIO(empty)))["note"]))
```

```text
None
''
```

所以写清洗代码时用 `(row.get("note") or "").strip()` 一次兜住三种状态：`None`、`""`、`"   "`。

出错时先看哪里：

| 症状 | 多半是 | 先看哪 |
| --- | --- | --- |
| `KeyError: 'date'`，可键名看起来就是 `date` | 文件开头有 BOM，却用 `utf-8` 读 | `print(list(row))` 看有没有 `\ufeff`，或数头三个字节 |
| 一条记录多出几列，备注的话跑到别的列 | 用了 `split(",")`，而字段里带逗号 | 该字段在文件里有没有被 `"` 包住 |
| 合计比手算少几笔，程序一声不响 | 坏行被跳过，或缺失被当成 0 | 打印按原因分的 `skipped` |
| 金额显示 `120.5` 而不是原本的全角写法 | `float()` 认 Unicode 数字 | `repr(row["amount"])` |
| 两个金额相加得到 `0.30000000000000004` | 用 `float` 在算钱 | 换成整数分，见下下节 |

## 数笔数用 Counter，求和用 defaultdict

清洗和聚合都要回答「这个键还没出现过怎么办」。`Counter` 和 `defaultdict` 都能给 0，但给法不同：

```python
counts = Counter()
totals = defaultdict(int)
print(counts["书籍"], "书籍" in counts)
print(totals["书籍"], "书籍" in totals)
```

```text
0 False
0 True
```

`Counter` 读到不存在的键只返回 0，不真的建键；`defaultdict` 顺手把键建出来，值是默认值。所以：

- 数笔数用 `Counter`：`counts[category] += 1`，`most_common()` 直接给排行，跳过原因也用它记。
- 求和用 `defaultdict(int)`：`totals[category] += cents`。

反过来写不会立刻报错，这才是阴的地方：用 `Counter` 求和，第一笔是 0 + 金额，结果照样对，但下一个读代码的人会以为里面存的是笔数。用 `defaultdict(list)` 求和才是当场崩：

```python
totals = defaultdict(list)
try:
    totals["书籍"] += 4500
except TypeError as error:
    print("TypeError:", error)
```

```text
TypeError: 'int' object is not iterable
```

（`+=` 对列表是 extend，`int` 不是可迭代对象。）

这两个容器只省掉「先判断键在不在」那一句。真正让报表可信的是另一件事：坏行为什么被跳过，要能按原因分别数出来，而不是只留一个总数。

## 金额不进 float，进整数分

```python
print(0.1 + 0.2)
```

```text
0.30000000000000004
```

第 02 课讲小数不精确时给过结论：金额把元换成分、全程用整数。这个方向我照做了，下面全是整数运算。但「元换成分」的那一次乘法，我还是用了 `float`——坑就在这一步：

```python
for text in ("1.005", "8.165", "2.675"):
    value = float(text)
    print(text, "* 100 =", repr(value * 100), "-> int(round(...)) =", int(round(value * 100)))
```

```text
1.005 * 100 = 100.49999999999999 -> int(round(...)) = 100
8.165 * 100 = 816.4999999999999 -> int(round(...)) = 816
2.675 * 100 = 267.5 -> int(round(...)) = 268
```

`1.005` 元该是 101 分，`float` 先把它变成 `100.49999999999999`，四舍五入后少一分。`2.675` 恰好是 `267.5`，结果反而是对的——所以这类错不会每笔都犯，只会偶尔少一分。

下面这份代码用的正是 `int(round(float(text) * 100))`，也就是说它在这个坑上没修。我留着它，是因为正文先把流程走通，换 `Decimal` 或按字符串拆整数部分和小数部分是另一件事，代价写在文末「还差的」里。

## 跑一遍

这段不读文件：`io.StringIO` 把已经解码好的文本包装成文件对象（`csv.DictReader` 只要求能逐行迭代的字符串），BOM 和换行在前两节处理完了。四个函数各管一层：`parse_record` 吃一个 dict 只判合法性，`load` 去重并按原因计数，`summarize` 只做累加，`print_report` 只打印。

<!-- run -->
```python
import csv
import io
from collections import Counter, defaultdict
from datetime import datetime

SAMPLE = """date,category,amount,note
2026-10-01,书籍,45.00,"Python 入门, 第 2 版"
2026-10-01,餐饮,32.50,午饭
2026-10-02,书籍,,缺金额
2026-10-02,交通,18.00,
2026-10-01,餐饮,32.50,午饭
2026-10-03,书籍,None,坏金额
2026-10-04,餐饮,１２０.５０,全角数字
2026-13-01,交通,8.00,坏日期
"""


def yuan(cents):
    return f"{cents // 100}.{cents % 100:02d}"


def parse_cents(raw):
    text = (raw or "").strip()
    if not text:
        return None, "金额为空"
    try:
        return int(round(float(text) * 100)), None
    except ValueError:
        return None, "金额不是数字"


def parse_record(row):
    if not (row.get("date") or "").strip():
        return None, "日期为空"
    if not (row.get("category") or "").strip():
        return None, "分类为空"
    try:
        day = datetime.strptime(row["date"].strip(), "%Y-%m-%d").date()
    except ValueError:
        return None, "日期不合法"
    cents, reason = parse_cents(row.get("amount"))
    if reason:
        return None, reason
    return {
        "day": day,
        "category": row["category"].strip(),
        "cents": cents,
        "note": (row.get("note") or "").strip(),
    }, None


def load(rows):
    records, skipped, seen = [], Counter(), set()
    for row in rows:
        record, reason = parse_record(row)
        if reason:
            skipped[reason] += 1
            continue
        key = (record["day"], record["category"], record["cents"], record["note"])
        if key in seen:
            skipped["重复"] += 1
            continue
        seen.add(key)
        records.append(record)
    return records, skipped


def summarize(records):
    totals, counts = defaultdict(int), Counter()
    for record in records:
        totals[record["category"]] += record["cents"]
        counts[record["category"]] += 1
    return totals, counts


def print_report(records, skipped):
    print(f"读到 {len(records) + sum(skipped.values())} 行数据，保留 {len(records)} 行")
    for reason, times in skipped.most_common():
        print(f"跳过 {times} 行：{reason}")
    totals, counts = summarize(records)
    for category in sorted(totals):
        print(f"- {category}: {counts[category]} 笔，{yuan(totals[category])}")
    print(f"合计: {len(records)} 笔，{yuan(sum(totals.values()))}")


def run(text):
    return load(csv.DictReader(io.StringIO(text)))


records, skipped = run(SAMPLE)
print_report(records, skipped)

assert len(records) == 4
assert sum(skipped.values()) == 4
assert skipped["重复"] == 1
assert skipped["金额为空"] == 1
assert [r["category"] for r in records] == ["书籍", "餐饮", "交通", "餐饮"]
assert sum(r["cents"] for r in records) == 21600
print("断言通过")
```

```text
读到 8 行数据，保留 4 行
跳过 1 行：金额为空
跳过 1 行：重复
跳过 1 行：金额不是数字
跳过 1 行：日期不合法
- 书籍: 1 笔，45.00
- 交通: 1 笔，18.00
- 餐饮: 2 笔，153.00
合计: 4 笔，216.00
断言通过
```

读输出时的四个点：

- 「读到 8 行」是 `len(records) + sum(skipped.values())`，数据行数，不含表头。
- 四种跳过各 1 行，因为按原因存进了 `Counter` 才打得出来；只存一个总数的话这里只有一个 4。
- 餐饮 2 笔 153.00 是 32.50 + 120.50——全角那笔被收了进来，正是上一节说的「不是坏数据」。
- 分类顺序来自 `sorted(totals)`，是码点序：书籍 U+4E66 < 交通 U+4EA4 < 餐饮 U+9910。不是拼音序，也不是金额序。稳定、可复现，但中文分类这样排对人不友好。

## 命令行入口

第 18 课结尾说这一课补命令行参数。把上面四个函数原样放进 `csv_report.py`，只加一个 `main`：

```python
import argparse
import csv
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="按分类汇总 CSV 账目")
    parser.add_argument("csv_file", type=Path)
    parser.add_argument("--encoding", default="utf-8-sig")
    args = parser.parse_args()

    with args.csv_file.open(newline="", encoding=args.encoding) as handle:
        records, skipped = load(csv.DictReader(handle))
    print_report(records, skipped)


if __name__ == "__main__":
    main()
```

```text
PS> python csv_report.py expenses.csv
读到 8 行数据，保留 4 行
跳过 1 行：金额为空
跳过 1 行：重复
跳过 1 行：金额不是数字
跳过 1 行：日期不合法
- 书籍: 1 笔，45.00
- 交通: 1 笔，18.00
- 餐饮: 2 笔，153.00
合计: 4 笔，216.00
```

三个细节：`type=Path` 让 `args.csv_file` 直接是路径对象，不用再 `Path()` 转一次；`Path.open` 的参数和内置 `open` 一样，`newline=""` 照传；`if __name__` 守卫让别的脚本可以 `import` 这个文件而不触发命令行。

## 动手

上面的 `parse_cents` 里 `except` 只接了 `ValueError`。想清楚为什么这条路径上不会再抛别的。

```code-challenge
{
  "id": "py-19-fill",
  "kind": "fill",
  "title": "接住转换失败的那一类异常",
  "language": "python",
  "template": "def parse_cents(raw):\n    text = (raw or '').strip()\n    if not text:\n        return None, '金额为空'\n    try:\n        return int(round(float(text) * 100)), None\n    except {{0}}:\n        return None, '金额不是数字'",
  "options": [["ValueError", "(TypeError, ValueError)", "Exception"]],
  "answers": [0],
  "explain": "走到 float 时 text 一定已是非空字符串，坏输入只可能抛 ValueError。TypeError 在这条路径上到不了（raw 为 None 时已被 (raw or '') 兜掉）；写 Exception 会把拼错的变量名、除零这类真 bug 当成脏数据一起跳过。"
}
```

## 检查一下

```quiz
{
  "id": "py-19-quiz",
  "title": "CSV 清洗三问",
  "questions": [
    {
      "question": "用 encoding=「utf-8」读一份带 BOM 的 CSV，row['date'] 却抛 KeyError，原因是什么？",
      "options": [
        "csv 模块把表头转成了大写",
        "第一行被当成数据，表头丢了",
        "第一个键名前面多了一个不可见的 U+FEFF 字符",
        "DictReader 只认 ASCII 键名"
      ],
      "answer": 2,
      "explain": "utf-8 把开头三个字节解成 U+FEFF 黏在 date 上，键名实际是 U+FEFF 加 date，和 date 不相等。换 utf-8-sig 才会吃掉它。"
    },
    {
      "question": "某行的 amount 是空单元格，最合适的处理是哪个？",
      "options": [
        "跳过这一行，把原因记进 Counter",
        "当成 0 继续求和",
        "用上一行的金额补上",
        "让程序立刻整批崩溃"
      ],
      "answer": 0,
      "explain": "空值表示不知道，不是 0。跳过并按原因计数，其余数据还能用，合计也不会被缺失值污染。"
    },
    {
      "question": "sample 里同一份 CSV 读出来，一行 note 是 None，另一行 note 是空串，区别在哪？",
      "options": [
        "没区别，都是空值",
        "None 表示这一列整列缺失，空串表示单元格存在但为空",
        "None 表示行比表头短少了这一列，空串表示单元格存在但为空",
        "None 是 csv 模块的 bug"
      ],
      "answer": 2,
      "explain": "DictReader 用 None 填充表头有、这一行没有的列；单元格存在但内容空着时读到空串。所以 (row.get('note') or '').strip() 能一次兜住 None、'' 和纯空格。"
    }
  ]
}
```

## 还差的

- **精度没修。** `int(round(float(text) * 100))` 在 `1.005`、`8.165` 上各少一分（实测 100 和 816）。`Decimal` 我只在第 02 课见过名字，没在真实数据上跑过；按字符串拆整数部分和小数部分的版本也没写，所以「哪种更稳」我给不出实测结论。
- **`OverflowError` 没接。** `float("1e309")` 是 `inf`，`int(round(inf))` 抛 `OverflowError: cannot convert float infinity to integer`，`except ValueError` 挡不住，整批会停在这行。要不要接、接了算「金额不是数字」还是「超出范围」，我没想好。
- **去重键只是四个字段全同。** 同一天两笔金额相同、备注相同的午饭会被当成重复删掉一条，这是把钱算少了。真正的「同一笔」要靠订单号之类的业务键，这份数据里没有。
- **内存随行数涨。** `records` 留全量是为了断言，`seen` 为了去重，两者都跟行数成正比。我原来以为「边读边累加，内存只跟分类数有关」能顺手兼得，其实去重就必须记住见过哪些行——想省内存，只能放弃去重或者换外排。
- **没测过的输入：** GBK 编码的老导出、字段里带换行的行、表头顺序变化、`amount` 列整个缺失。只用手写 `assert` 验了这八行，没写成 pytest 文件，也没测一遍过十万行的耗时。
