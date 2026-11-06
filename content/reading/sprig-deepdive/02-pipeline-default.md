---
title: 管道、default 与「参数顺序坑」
category: sprig-deepdive
order: 2
minutes: 15
level: intermediate
status: published
---

## 先把结论摆出来

**模板里的 `|` 不是 shell 管道，是"把左边作为最后一个参数传给右边"。** 这解释了两件事：为什么 `default` 的参数顺序反直觉，以及为什么有些函数在管道里"看起来不工作"。

打个比方：`|` 像工厂流水线的传送带，每个工位只接上道工序送来的半成品，还默认把它放在自己参数表的最后一位。机制上，`x | f` 就是 `f x`；管道的值被塞进右边函数的最后一个参数位，所以像 `default` 这种「默认值在前、被检查值在后」的签名，顺序才显得反直觉。

```code-flow
{
  "id": "sprig-pipeline",
  "title": "管道不是 shell 管道，是「塞到最后一个参数」",
  "nodes": [
    { "id": "left", "label": "左边的值", "sub": "不是第一个参数", "layer": 0, "order": 0 },
    { "id": "pipe", "label": "|", "sub": "作为最后一个实参", "layer": 1, "order": 0 },
    { "id": "fn", "label": "右边函数", "sub": "收到管道值", "layer": 2, "order": 0 },
    { "id": "result", "label": "结果", "sub": "所以 default 反直觉", "layer": 3, "order": 0 }
  ],
  "edges": [
    { "from": "left", "to": "pipe" },
    { "from": "pipe", "to": "fn" },
    { "from": "fn", "to": "result" }
  ],
  "steps": [
    {
      "title": "管道把左边塞进最后一个参数",
      "desc": "{{ .Name | upper }} 就是 {{ upper .Name }}",
      "file": "demo.tmpl",
      "code": "{{ upper .Name }}",
      "focus": [1],
      "nodes": ["left", "pipe"],
      "edges": ["left>pipe"],
      "log": [{ "k": "等价", "v": "{{ .Name | upper }}" }],
      "explain": "管道不是 shell 的 stdin，是把左边作为最后一个实参传过去。"
    },
    {
      "title": "default 的签名：默认值在前",
      "desc": "第一个参数是默认值，第二个才是被检查的值",
      "file": "sprig/defaults.go",
      "code": "func default(d interface{}, given ...interface{}) interface{}",
      "focus": [1],
      "nodes": ["fn", "pipe"],
      "edges": ["pipe>fn"],
      "log": [{ "k": "签名", "v": "default(默认值, 值)" }],
      "explain": "管道把左边放到最后一位，所以 default 的用法看着反直觉。"
    },
    {
      "title": "所以 default 要写在最后",
      "desc": ".Name | default \"匿名\" 才是对的",
      "file": "demo.tmpl",
      "code": "{{ .Name | default \"匿名\" }}   // = default \"匿名\" .Name",
      "focus": [1],
      "nodes": ["result", "fn"],
      "edges": ["fn>result"],
      "log": [{ "k": "结果", "v": "空则取默认值" }],
      "explain": "写成 \"匿名\" | default .Name 会得到相反的结果。"
    },
    {
      "title": "零值也会被替换",
      "desc": "0 / false / 空字符串都算零值",
      "file": "demo.tmpl",
      "code": "{{ default 1 .Replicas }}   // Replicas=0 -> 1",
      "focus": [1],
      "nodes": ["result"],
      "edges": [],
      "log": [{ "k": "坑", "v": "replicas: 0 被改成 1" }],
      "explain": "「设置了 0」和「没设置」在模板里看起来一样，这是 Helm 最常见的坑。"
    }
  ]
}
```

## 管道到底是什么

```go
{{ .Name | upper }}
```

等价于：

```go
{{ upper .Name }}
```

再看 `default`：

```go
{{ .Name | default "匿名" }}
```

等价于：

```go
{{ default "匿名" .Name }}
```

注意：**`"匿名"` 在前，`.Name` 在后。** 这就是 Sprig 里 `default` 的签名：

```go
func default(d interface{}, given ...interface{}) interface{}
```

第一个参数是"默认值"，第二个才是"被检查的值"。而管道总是把左边作为**最后一个**参数。所以 `.Name | default "匿名"` 是对的，写成 `"匿名" | default .Name` 就会得到完全相反的结果。

这个坑在 Helm chart 里极其常见。

## 三个必须记住的规则

### 1. 管道只影响最后一个参数

```go
{{ "hello" | repeat 3 }}   →  repeat 3 "hello"   →  "hellohellohello"
```

`repeat` 的签名是 `repeat(count int, str string)`。如果写成 `{{ repeat 3 "hello" | upper }}`，则变成 `upper "hellohellohello"`，结果一样。但 `{{ 3 | repeat "hello" }}` 就成了 `repeat "hello" 3`——类型不匹配，报错。

**记法：管道是"喂给最后一个位置"，不是"喂给第一个"。**

### 2. 管道可以串，从左到右

```go
{{ .Name | trim | upper | trunc 20 }}
```

等价于 `trunc 20 (upper (trim .Name)))`——**内到外**，但书写是**从左到右**。这是管道最大的价值：嵌套函数读起来反人类，管道读起来是流水线。

### 3. `default` 对"零值"也生效

```go
{{ default "匿名" "" }}     →  "匿名"   （空字符串是零值）
{{ default "匿名" 0 }}      →  "匿名"   （0 是零值！）
{{ default "匿名" false }}  →  "匿名"   （false 是零值）
```

这条最容易出事：**你可能有意识地配置了 `replicas: 0`，然后被 `default 1` 改成了 1。**

绕法是显式判断"未设置"而不是"零值"。Sprig 有 `hasKey` 用于 map，或把 `replicas` 设计成"不填就表示默认，填 0 表示零副本"两种入口。

## 一个 pipe 与括号的实战对比

要表达"取最后一段路径、转小写、最多 10 字符"：

```go
# 嵌套写法（能跑，但要从里往外读）
{{ trunc 10 (lower (last (splitList "/" .Path))) }}

# 管道写法（从左往右读，和意图同序）
{{ .Path | splitList "/" | last | lower | trunc 10 }}
```

两者完全等价。管道写法的价值不在"短"，而在**阅读顺序等于执行顺序**。

## 本关的小测

```quiz
{"id":"sprig-02-quiz","title":"管道与 default 三问","questions":[
 {"question":"{{ .Name | default \"匿名\" }} 等价于哪个？","options":["default .Name \"匿名\"","default \"匿名\" .Name","default(\"匿名\", .Name) 的相反顺序","upper .Name"],"answer":1,"explain":"管道把左边作为最后一个参数；default 的签名是 (默认值, 被检查值)。"},
 {"question":"{{ default 1 .Replicas }} 在 Replicas=0 时返回什么？","options":["0","1","报错","nil"],"answer":1,"explain":"0 是零值，default 会把零值替换成默认值——这是 Helm 里最经典的坑。"},
 {"question":"{{ \"a,b\" | splitList \",\" | last }} 的结果是？","options":["a","b","[\"a\",\"b\"]","报错"],"answer":1,"explain":"splitList 切成 [a b]，last 取最后一个 → b。"}
]}
```

## 常见误区

### 误区：管道像 shell 那样传 stdin

**你以为**：`x | f` 是把 `x` 塞进 `f` 的输入流。

**实际上**：Go 模板没有"输入流"概念，纯函数调用。`x | f` 就是 `f x`；有两个参数的 `f a b` 被管道替换的是 `b`。

**所以**：写管道时先看被调函数的签名，确认"被替换的是哪个位置"。Sprig 里绝大多数函数的"数据参数"都在最后，但 `default`、`hasKey`、`ternary` 这几个是例外，要单独记。

### 误区：default 判断的是"有没有设置"

**你以为**：`default` 检查字段是否存在于数据里。

**实际上**：它检查值是否为零值。**"设置了 0"和"没设置"在模板里看起来一样。**

**所以**：当"0 / false / 空字符串"是有意义的配置时，不要用 `default`。改用 `hasKey` + 显式分支，或者干脆把语义设计成"不填即零"。
