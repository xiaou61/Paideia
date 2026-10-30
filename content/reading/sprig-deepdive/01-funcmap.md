---
title: FuncMap：函数是怎么进到模板里的
category: sprig-deepdive
order: 1
minutes: 16
level: intermediate
status: published
---

## 先把结论摆出来

**Go 模板引擎本身不认识任何函数（除了几个内建判断）；所有函数都来自一张 `FuncMap`——一个「名字 → 函数」的 map，在解析模板之前交给引擎。**

```sketch
{
  "title": "模板里的函数，全靠一张表",
  "rows": [
    [
      { "id": "map", "title": "FuncMap", "sub": "名字 → 函数", "tone": "blue" },
      { "id": "inject", "title": "解析模板前交进去", "sub": "交晚了就认不出", "tone": "blue" },
      { "id": "use", "title": "模板里按名字调", "sub": "引擎自己不认任何函数", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "map", "to": "inject" },
    { "from": "inject", "to": "use" }
  ]
}
```

Sprig 的全部内容，就是一张很大的 `FuncMap`：

```go
// 本质上是这样（实际用了多个 map 合并）
var funcMap = template.FuncMap{
    "upper":  strings.ToUpper,
    "repeat": strings.Repeat,
    "default": func(d interface{}, given ...interface{}) interface{} { /* ... */ },
    // ... 一百多个
}
```

## 为什么是这个设计

Go 的 `text/template` 有一个刻意的限制：**模板里不能调用任意函数，只能调用注册过的。** 这不是偷懒，是安全与可预测性的取舍：

- 如果任意函数都能在模板里调，那么"模板"就变成了"代码"——运维改一个 config 模板，理论上能执行任意逻辑。
- 注册制把能力**收窄成一个明确的白名单**。你能做什么，取决于调用方给了什么。

Sprig 站在这个设计的"扩大白名单"一侧：它让这个白名单变得足够大，大到日常字符串与数据处理都能覆盖。

## 三个关键细节

### 1. 注册发生在解析之前，不是渲染时

```go
tmpl, err := template.New("demo").Funcs(funcMap).Parse(src)
```

`Funcs` 必须在 `Parse` **之前**调用。原因：解析阶段就要把模板里的标识符解析成"函数引用"；如果这时 `FuncMap` 里没有它，`Parse` 直接报 `function "xyz" not defined`。

这也解释了一个常见困惑：**模板里的函数名拼错，报错出现在编译期（Parse），不是运行期。** 好消息是能在启动时抓出来。

### 2. 函数名是字符串，不是标识符——所以拼错不会被编译器发现

```go
// 编译期完全合法，运行到 Parse 才报错
template.New("x").Funcs(template.FuncMap{"upper": strings.ToUpper}).
    Parse("{{ upper .Name }}")
```

如果写成 `{{ Upper .Name }}`（大写），Go 的模板解析会把它当成**字段访问**（`.Name` 那种语法的开头），报的错是 `can't evaluate field Upper`——这个错误信息会把人引向错误方向。

**实践建议**：把模板编写放进单元测试的覆盖范围。模板解析错误在测试里秒现，在线上是启动失败或渲染失败。

### 3. 函数可以是"变参 + 返回错误"

模板函数的签名叫 `func(args ...) (value, error)`。第二个返回值是 error 时，错误会中断渲染并传回 `Execute`：

```go
funcMap["mustParseDate"] = func(s string) (time.Time, error) {
    t, err := time.Parse("2006-01-02", s)
    if err != nil {
        return time.Time{}, err   // 渲染在这里停下
    }
    return t, nil
}
```

Sprig 里有一整族 `must*` 前缀的函数（`mustFirst`、`mustToJson`、`mustRegexFind`）就是这个模式：**宁可渲染失败，也不要静默给一个错误的默认值。**

反过来，不带 `must` 的版本会吞掉错误返回零值——`first (list)` 对空列表返回 `nil` 而不是报错。选哪个取决于你希望"配置写错了"是立即暴露还是静默降级。

## 本关的小测

```quiz
{"id":"sprig-01-quiz","title":"FuncMap 三问","questions":[
 {"question":"模板里的 {{ upper .Name }} 报错 function \"upper\" not defined，最可能的原因是什么？","options":["upper 不是合法的 Go 标识符","Funcs() 没在 Parse() 之前调用，或者这个 map 里没有 upper","strings.ToUpper 签名不对","模板文件没保存"],"answer":1,"explain":"解析阶段就要能查到函数名；Funcs 晚于 Parse 调用等于没注册。"},
 {"question":"写成 {{ Upper .Name }}（首字母大写）会怎样？","options":["一样能找到 upper 函数","报 function not defined","被当成字段访问，报 can't evaluate field Upper","自动转成小写查找"],"answer":2,"explain":"首字母大写走的是字段/方法访问语法，错误信息会误导人去查结构体字段。"},
 {"question":"Sprig 里 mustToJson 与 toJson 的区别是什么？","options":["性能不同","must 版本在出错时返回 error 中断渲染，非 must 版本吞掉错误给默认值","must 版本支持更多类型","没有区别"],"answer":1,"explain":"这是 Sprig 的通用命名约定：must 前缀 = 出错即失败。"}
]}
```

## 常见误区

### 误区：函数是在渲染时查找的

**你以为**：模板先解析成通用 AST，渲染时遇到 `upper` 再去 `FuncMap` 里查。

**实际上**：解析阶段就绑定。所以"先 Parse 再 Funcs"不行，而"函数名拼错"在 Parse 那一刻就会暴露。

**所以**：模板与它的 `FuncMap` 必须一起测。只测 `FuncMap` 里的函数（`upper("a") == "A"`）证明不了模板可用——真会出问题的是模板引用了一个不存在的名字。

### 误区：函数越多越好

**你以为**：既然 `FuncMap` 是白名单，那把能想到的都塞进去最方便。

**实际上**：每加一个函数，模板作者就多一个"在模板里写逻辑"的诱惑。Sprig 有一百多个函数，Helm 社区最常被批评的一点就是"chart 里写了业务逻辑"。

**所以**：这一关只讲机制，第 4 关讲边界——机制能力越大，越需要判断力。
