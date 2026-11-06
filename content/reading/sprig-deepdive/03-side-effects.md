---
title: 副作用与时间：为什么日期函数最容易出事
category: sprig-deepdive
order: 3
minutes: 15
level: intermediate
status: published
---

## 先把结论摆出来

**模板函数看起来是"纯函数"，但 Sprig 里有一批不是**：`now`、`randAlpha`、`uuidv4`、`env`、`getHostByName`。它们的共同点是——**同样的模板、同样的输入，输出会变**。这让"渲染结果可复现"这件事直接失效。

打个比方：带 `now` 的模板像一台会自己翻日历的打印机，同一份文件送进去两次，出来的日期却不同；带 `randAlpha` 的模板像每次都换一把新配的钥匙。机制上，这些函数在渲染时去读时钟、环境变量或随机源，所以同样的模板、同样的输入，产物不再一样。

```code-flow
{
  "id": "sprig-side-effects",
  "title": "有一批函数会让渲染结果不可复现",
  "nodes": [
    { "id": "pure", "label": "纯函数", "sub": "同样输入同样输出", "layer": 0, "order": 0 },
    { "id": "impure", "label": "有副作用的函数", "sub": "now / rand / env", "layer": 1, "order": 0 },
    { "id": "cost", "label": "渲染不可复现", "sub": "排障与对比都变难", "layer": 2, "order": 0 }
  ],
  "edges": [
    { "from": "pure", "to": "impure", "label": "混入" },
    { "from": "impure", "to": "cost", "label": "产物漂移" }
  ],
  "steps": [
    {
      "title": "纯函数：同输入同输出",
      "desc": "排版类函数可以安全地反复渲染",
      "file": "demo.tmpl",
      "code": "{{ .Name | upper }}",
      "focus": [1],
      "nodes": ["pure"],
      "edges": [],
      "log": [{ "k": "类型", "v": "纯函数" }],
      "explain": "这是模板该有的样子：同样的输入永远同样的产物。"
    },
    {
      "title": "读时钟：now / date / ago",
      "desc": "产物变成时间相关",
      "file": "demo.tmpl",
      "code": "{{ now | date \"2006-01-02\" }}",
      "focus": [1],
      "nodes": ["impure", "pure"],
      "edges": ["pure>impure"],
      "log": [{ "k": "副作用", "v": "读时钟" }],
      "explain": "今天渲染和明天渲染产出不同文件，git diff 永远有变化。"
    },
    {
      "title": "读环境：env / expandenv",
      "desc": "同一份模板在不同机器产出不同结果",
      "file": "demo.tmpl",
      "code": "{{ env \"HOME\" }}",
      "focus": [1],
      "nodes": ["impure"],
      "edges": [],
      "log": [{ "k": "副作用", "v": "读环境变量" }],
      "explain": "模板看不出它依赖哪些环境变量，本地成功、CI 失败很难查。"
    },
    {
      "title": "随机：randAlphaNum / uuidv4",
      "desc": "每次渲染都换一套值",
      "file": "demo.tmpl",
      "code": "{{ randAlphaNum 32 }}",
      "focus": [1],
      "nodes": ["impure"],
      "edges": [],
      "log": [{ "k": "副作用", "v": "随机源" }],
      "explain": "而且是 math/rand，不是密码学安全，不能拿来生成真实口令。"
    },
    {
      "title": "结果：渲染不可复现",
      "desc": "带副作用的模板不能随便重渲染",
      "file": "notes.md",
      "code": "# 同一模板两次渲染 → 不同产物",
      "focus": [1],
      "nodes": ["cost", "impure"],
      "edges": ["impure>cost"],
      "log": [{ "k": "后果", "v": "diff 疲劳 / 无意义滚动更新" }],
      "explain": "想让渲染可复现，就把时间/随机/环境值在调用方取值再注入。"
    }
  ]
}
```

## 三类副作用

### 1. 读时钟：`now`、`date`、`ago`

```go
{{ now | date "2006-01-02" }}          # 今天
{{ now | dateModify "-1h" | date "15:04" }}
{{ .CreatedAt | ago }}                  # "3 hours ago"
```

它们本身是"读",不是"写"。问题在于**模板的产物变成了时间相关**：

- 同一份模板，今天渲染和明天渲染产出不同的文件 → `git diff` 永远有变化
- 配置文件的哈希每次都变 → 触发不必要的滚动更新（Helm 的 `checksum/config` 注解就踩过这个）
- 测试断言"渲染结果等于期望字符串"永远不稳

**处理办法**：把时间从外面传进来（`.RenderTime`），模板只做格式化，不调用 `now`。这条纪律在 CI 里能用一条 grep 守住。

### 2. 读环境：`env`、`expandenv`

```go
{{ env "HOME" }}
```

它让**同一份模板在不同机器上产出不同结果**。对"部署到多环境"来说这看起来是特性，对"配置可复现"来说是毒药：

- 本地渲染成功、CI 渲染失败，原因是一个环境变量没设置
- 模板文件本身看不出它依赖哪些环境变量

**处理办法**：显式把需要的外部值作为数据传入。如果一定要读环境，在**调用方**读、然后传进去——这样依赖是可见的。

### 3. 随机：`randAlphaNum`、`uuidv4`、`derivePassword`

```go
{{ randAlphaNum 32 }}    # 每次渲染都不同
```

生成密码/密钥时用它很方便，但现在有两个问题：

- **不可复现**：重跑一次渲染就换了一套密码，而你无法 diff 出"这次和上次哪里不同"
- **它不适合做真正的密钥生成**：Sprig 的随机源是 `math/rand`（非密码学安全）。生成口令、token 必须用 `crypto/rand`。

**处理办法**：随机值在**应用里**生成并持久化，模板只负责把它填进目标位置。

## 一个具体的坏例子

```yaml
# 反面教材
metadata:
  name: {{ .Name }}
  annotations:
    rendered-at: {{ now | date "2006-01-02T15:04:05Z07:00" }}
data:
  sessionKey: {{ randAlphaNum 32 | b64enc }}
```

这段配置每次渲染都会产生新文件。后果：

1. `kubectl diff` 永远显示有变化，人会逐渐忽略它
2. 每次 apply 都触发一次 rollout（注解变了）
3. `sessionKey` 每次重部署都换，所有会话失效——而排查时你会以为是别的原因

## 判断标准

拿到一个模板函数，问三个问题：

1. **同样的输入，同样的环境，两次调用结果一样吗？** 不一样就有副作用。
2. **它的输出会进入最终产物吗？** 会，就要考虑"产物可复现"是否重要。
3. **这个值是不是应该在别处生成？** 时间、随机、环境变量通常都属于"别处"。

## 本关的小测

```quiz
{"id":"sprig-03-quiz","title":"副作用三问","questions":[
 {"question":"为什么在配置文件模板里用 now 是危险的？","options":["now 会报错","now 让渲染产物随时间变化，git diff 永远有变化且可能触发无意义的重部署","now 精度不够","now 需要 root 权限"],"answer":1,"explain":"产物不可复现是不必要的滚动更新与「diff 疲劳」的根源。"},
 {"question":"Sprig 的 randAlphaNum 可以用来生成生产环境的口令吗？","options":["可以，长度够就行","不可以，它基于 math/rand，不是密码学安全随机源","可以，但只能用一次","只有 32 位以上才安全"],"answer":1,"explain":"生成凭据必须用 crypto/rand；Sprig 的随机只适合占位或非安全场景。"},
 {"question":"env 函数带来的核心问题是什么？","options":["性能差","模板的依赖变得不可见，同一份模板在不同机器上产出不同结果","只能读大写变量","会泄露密钥到日志"],"answer":1,"explain":"依赖藏进模板里，本地与 CI 行为不一致时很难查。"}
]}
```

## 常见误区

### 误区：模板函数是纯的，所以模板可以随便重渲染

**你以为**：模板渲染只是"把数据格式化"，可以安全地反复执行。

**实际上**：只要用到 `now`/`rand*`/`env`，渲染就是**不纯的**。反复渲染会得到不同结果，而很多流程（CI 检查、diff 预览、幂等 apply）都假设它是纯的。

**所以**：想让渲染可复现，就得把这些函数排除在模板之外——在调用方取值、注入模板。

### 误区：这些函数是"不好的设计"

**你以为**：Sprig 不该提供 `now` 和 `randAlphaNum`。

**实际上**：它们解决了真实需求（"生成一个临时口令"、"给日志打时间戳"）。问题不在函数存在，而在**用错了场景**。

**所以**：判断标准不是"能不能用"，而是"这份产物是否需要可复现"。一次性脚本里用 `now` 完全没问题。
