---
title: CSV 汇总：跳过坏行
category: python-scripting
order: 1
minutes: 25
level: beginner
status: published
---

## 这一关要做什么

从标准输入读若干行 `名称,金额`，输出**有效行**的总额与笔数：

```text
total=<总额>
count=<笔数>
```

**有效行**的定义（先写清楚，再写代码）：

- 非空行；
- 恰好两列（`split(',')` 后长度为 2）；
- 金额是**非负整数**（`int()` 能解析，且 `>= 0`）。

其余行一律跳过，不报错、不中断。

打个比方：这像分拣快递，坏的包裹挑出来放一边，别让它把整条流水线卡停。真实机制是：批量脚本宁可跳过并计数，也不要抛异常退出。

## 先想清楚

```sketch
{
  "title": "坏行的三种形态",
  "rows": [
    [
      { "id": "blank", "title": "空行", "sub": "strip 后为空", "tone": "gray" },
      { "id": "shape", "title": "列数不对", "sub": "少一列或多一列", "tone": "amber" },
      { "id": "value", "title": "金额不是非负整数", "sub": "abc、3.5、-5", "tone": "blue" }
    ]
  ],
  "arrows": []
}
```

真实数据里三种都会出现。脚本的默认行为应该是**跳过并继续**，而不是崩在第一行坏数据上——批处理脚本跑一半退出，比少算几行更麻烦。

## 自己写

```code-challenge
{
  "id": "pyscript-1-run",
  "kind": "run",
  "title": "CSV 汇总：跳过坏行",
  "language": "python",
  "starter": "import sys\n\n\ndef main():\n    total = 0\n    count = 0\n    for line in sys.stdin:\n        line = line.strip()\n        if not line:\n            continue\n        parts = line.split(',')\n        total += int(parts[1])\n        count += 1\n    print(f'total={total}')\n    print(f'count={count}')\n\n\nif __name__ == '__main__':\n    main()\n",
  "cases": [
    {
      "name": "干净输入",
      "stdin": "apple,10\nbanana,5\n",
      "expected": "total=15\ncount=2\n"
    },
    {
      "name": "跳过空行",
      "stdin": "\n\napple,10\n\n",
      "expected": "total=10\ncount=1\n"
    },
    {
      "name": "跳过非数字金额",
      "stdin": "apple,10\nbanana,x\ncherry,5\n",
      "expected": "total=15\ncount=2\n"
    },
    {
      "name": "跳过多余列",
      "stdin": "apple,10,note\nbanana,7\n",
      "expected": "total=7\ncount=1\n"
    },
    {
      "name": "跳过负数",
      "stdin": "apple,10\nrefund,-5\n",
      "expected": "total=10\ncount=1\n"
    }
  ],
  "explain": "先判列数再看金额：`if len(parts) != 2: continue`，然后 try/except ValueError 包住 `int(parts[1])`，再判 `amount >= 0`。不要用裸 `except:`——它会把 KeyboardInterrupt 也吞掉；`except ValueError` 足够。输出两行固定格式，顺序与大小写都是契约。"
}
```

## 常见误区

### 误区：坏数据应该让脚本停下

**你以为**：发现异常就 `raise`，让用户知道数据有问题。
**其实**：批处理脚本面对的是"每天几万行里总有几行脏"的现实。停下来的结果是整个任务失败；正确做法是跳过、计数，最后在报告里写"跳过 N 行"。
**怎么验证**：给输入里塞一行 `x,y`，看脚本是继续输出结果，还是直接 traceback。

### 误区：`int()` 什么都能转

**你以为**：`int('3.5')` 会得到 3。
**其实**：它抛 `ValueError`；`int(' 5 ')` 可以（会去空白），`int('+5')` 也可以。**小数要用 `float` 再判断是否为整数**，但本关的定义就是"非负整数"，所以小数算无效。
**怎么验证**：在 Python REPL 里各试一次。

### 误区：输出格式无所谓

**你以为**：反正人能看懂。
**其实**：脚本常常被别的程序消费（另一个脚本、CI、监控）。`total=15` 与 `total: 15` 对人是同一件事，对下游不是。**输出格式也是接口。**
**怎么验证**：用例逐字比对；多一个空格就会 fail。

## 带走一句

**先定义"哪些行不算"，再写累加**——脏数据的处理规则，比求和本身更重要。

```quiz
{"id":"pyscript-01-quiz","title":"坏行处理","questions":[
 {"question":"批处理脚本遇到一行坏数据，默认更合适的做法是？","options":["抛异常退出","跳过并计数，最后在报告里说明","把该行当成 0","打印到 stderr 后崩溃"],"answer":1,"explain":"脚本跑一半退出会让整个任务失败；跳过与计数才能保住其余数据并留下线索。"},
 {"question":"`int('3.5')` 的结果是？","options":["3","4","抛 ValueError","3.5"],"answer":2,"explain":"int 只接受整数字符串（可带空白与正负号）；小数要先 float 再判断。"}
]}
```
