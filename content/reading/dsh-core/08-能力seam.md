---
title: 能力 seam：一个能力，三种角色
category: dsh-core
order: 8
minutes: 15
level: intermediate
status: published
---

## 先把结论摆出来

**一个"可替换的能力"不是一个包，而是三个角色**：定义接口的 **Service Definition**、实现它的 **Service Provider**、使用它的 **Consumer**（面向模型时常常就是一个工具）。

三者合起来才叫一个 **seam**（接缝）。任何单一角色都不是 seam。seam 是 DSH 能"换一个提供方就换掉整个产品"的原因。

```sketch
{
  "title": "一个能力，三种角色",
  "rows": [
    [
      { "id": "def", "title": "Definition", "sub": "接口 + 类型", "tone": "violet" }
    ],
    [
      { "id": "prov", "title": "Provider", "sub": "本地实现", "tone": "blue" },
      { "id": "cons", "title": "Consumer", "sub": "暴露给模型", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "prov", "to": "def", "label": "实现" },
    { "from": "cons", "to": "def", "label": "依赖" }
  ]
}
```

## 以 Bash 为例

DSH 里最典型的一个 seam 就是"执行命令"：

- **Service Definition**（`dsh-shell`）：定义 `shell` 服务和请求/结果类型。
- **Service Provider**（`dsh-bash-local`）：在本机真正执行命令。
- **Consumer**（`dsh-tool-bash`）：把这个能力包装成模型能调的 `bash` 工具。

消费方只 `inject: ['shell']`，它不知道、也不需要知道背后是本机执行还是沙箱执行。

```ts
// 1) Definition：拥有接口和类型
export abstract class ShellService extends Service {
  constructor(ctx: Context) {
    super(ctx, 'shell')
  }
  abstract run(request: ShellRequest): Promise<ShellResult>
}

// 2) Provider：实现它
class BashLocal extends ShellService {
  async run(request: ShellRequest) {
    // 真正在本机执行
  }
}

// 3) Consumer：包装成工具，只依赖定义
export const inject = ['tools', 'shell']
export function apply(ctx: Context) {
  ctx.tools.register(defineTool({ /* ... execute 里调 ctx.shell.run ... */ }))
}
```

## 拆成三角色，换来什么

- **提供方可替换**：`cordis.yml` 里把 `dsh-bash-local` 换成另一个提供 `shell` 的包，定义和工具都不用改。
- **各自独立演进**：定义很少变；提供方可以单独优化性能与安全；消费方可以单独改"怎么呈现给模型"。
- **互不依赖**：提供方与消费方都只依赖定义，二者之间没有直接耦合。

这也解释了一个很"魔法"的现象：**把文件系统和进程执行都指向远程沙箱，Bash、终端、LSP 会一起搬过去**。因为它们共享同一个执行世界，换的是那个 seam 的提供方，不是各自 fork 一套。

## 要不要拆，先问一句

官方文档给了一条很硬的建议：**不要预防性拆分。**

- 一个简单的工具插件，自己就把能力做了，**不用**拆成三个包。
- 只有当某个角色**需要独立演进或替换**（同一个定义有多个提供方、或消费方要脱离提供方单独改）时，才值得拆成不同包。

判断标准不是"够不够规范"，而是"**将来这一块会不会被单独换掉**"。不会，就别拆。

## 本关的小测

```quiz
{"id":"dsh-08-quiz","title":"能力 seam 三问","questions":[
 {"question":"一个能力 seam 由哪三个角色组成？","options":["前端、后端、数据库","Service Definition、Service Provider、Consumer","模型、工具、会话"],"answer":1,"explain":"定义接口、实现、消费三者合起来才是一个 seam。"},
 {"question":"为什么换掉 bash 的提供方，工具不用改？","options":["因为工具会自己检测提供方","因为工具只依赖 shell 这个定义，不依赖具体提供方","因为提供方会改工具代码"],"answer":1,"explain":"消费方 inject 的是定义名，提供方可替换。"},
 {"question":"什么时候该把一个能力拆成三个包？","options":["只要写插件就必须拆","当角色需要独立演进或替换时，否则简单工具不用拆","永远不拆"],"answer":1,"explain":"不要预防性拆分；有替换/演进需求才拆。"}
]}
```

## 常见误区

### 误区：拆得越细越"专业"

**你以为**：既然三种角色是官方模式，那每样能力都拆成三个包最标准。

**实际上**：拆分是有成本的——多一个包、多一层版本与依赖。官方明确说"不要预防性拆分"，简单工具插件一个文件就够。三角色是**在需要替换时**才付的成本，不是默认形态。

**所以**：先写一个直的插件；真有第二个提供方、或消费方要独立改，再回头拆。

### 误区：换提供方是"改代码"

**你以为**：要从本地执行换成远程执行，得去改工具的调用逻辑。

**实际上**：只要它走的是 `ctx.shell` 这个 seam，换提供方就是在 `cordis.yml` 里换一行。工具的代码一行不动。

**所以**：遇到"换底层实现"的需求，先确认它是不是已经是一个 seam；是，就只该改配置。

## 下一关

这些服务、seam、事件平时各自安静。但一次真实对话发生时，它们是被一条主循环串起来的。下一关把镜头拉远：**一次 Agent 请求，从输入到回答，中间经过了谁。**
