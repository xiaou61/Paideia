---
title: 进入 harness：一个工具就是一个插件
category: dsh-core
order: 6
minutes: 15
level: intermediate
status: published
---

## 先把结论摆出来

**在 DSH 里给模型加一个工具，不需要改 harness，只要写一个插件，往 `ctx.tools` 服务里注册。** 工具注册同样是一个 effect：插件卸载，工具自动注销。

这一关把前五关的机制串起来，你会看到它们不是五个独立概念，而是**同一条链上的五个动作**：插件（第 1 关）→ 拿到 tools 服务（第 2 关）→ 注册是 effect（第 3 关）→ 用事件观察结果（第 4 关）→ 靠配置装进应用（第 5 关）。

```code-flow
{
  "id": "dsh-06-tool",
  "title": "一次工具调用经过谁",
  "nodes": [
    { "id": "model", "label": "模型", "sub": "决定调哪个工具", "layer": 0, "order": 0 },
    { "id": "tools", "label": "ctx.tools", "sub": "执行流水线", "layer": 1, "order": 0 },
    { "id": "exec", "label": "tool.execute", "sub": "真正干活", "layer": 2, "order": 0 },
    { "id": "result", "label": "tools/result 事件", "sub": "旁观的插件看得到", "layer": 3, "order": 0 },
    { "id": "back", "label": "结果回到模型", "sub": "进入下一轮", "layer": 4, "order": 0 }
  ],
  "edges": [
    { "from": "model", "to": "tools", "label": "调用" },
    { "from": "tools", "to": "exec", "label": "派发" },
    { "from": "exec", "to": "result", "label": "发出" },
    { "from": "result", "to": "back", "label": "物化后" }
  ],
  "steps": [
    {
      "title": "模型决定调工具",
      "desc": "模型只产出调用意图，不动手",
      "file": "conversation/chunk.ts",
      "code": "{ type: 'tool_call', name: 'greet', args: { name: 'Cordis' } }",
      "focus": [1],
      "nodes": ["model"],
      "edges": [],
      "log": [{ "k": "模型", "v": "调用 greet" }],
      "explain": "模型负责决定调什么，真正执行是插件的事。"
    },
    {
      "title": "工具注册在 ctx.tools",
      "desc": "一个工具就是一个插件，注册即 effect",
      "file": "plugins/greet-tool.ts",
      "code": "export const inject = ['tools']\nexport function apply(ctx) {\n  ctx.tools.register(defineTool({ name: 'greet', … }))\n}",
      "focus": [3],
      "nodes": ["tools", "model"],
      "edges": ["model>tools"],
      "log": [{ "k": "服务", "v": "greet 已注册" }],
      "explain": "插件卸载，工具自动注销，不用手写清理。"
    },
    {
      "title": "执行 execute",
      "desc": "流水线派发到工具实现",
      "file": "plugins/greet-tool.ts",
      "code": "async execute(args) {\n  return `Hello, ${args.name}!`\n}",
      "focus": [2],
      "nodes": ["exec", "tools"],
      "edges": ["tools>exec"],
      "log": [{ "k": "执行", "v": "Hello, Cordis!" }],
      "explain": "defineTool 会在执行前校验模型给的参数。"
    },
    {
      "title": "发出 tools/result 事件",
      "desc": "旁观插件订阅这个事件就能看到结果",
      "file": "plugins/tool-logger.ts",
      "code": "ctx.on('tools/result', (exec, result) => {\n  console.log(`[tool-logger] ${exec.name}`)\n})",
      "focus": [1],
      "nodes": ["result", "exec"],
      "edges": ["exec>result"],
      "log": [{ "k": "事件", "v": "tools/result" }],
      "explain": "logger 先看到结果，execute 的 promise 才兑现。"
    },
    {
      "title": "结果回到模型",
      "desc": "结果进入下一轮，模型据此继续",
      "nodes": ["back", "result"],
      "edges": ["result>back"],
      "log": [{ "k": "状态", "v": "进入下一轮" }],
      "explain": "两个插件谁也不需要认识谁，只共享 tools 服务与事件。"
    }
  ]
}
```

## 写一个工具插件

```ts
import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'greet-tool'
export const inject = ['tools']

export function apply(ctx: Context) {
  ctx.tools.register(defineTool({
    name: 'greet',
    description: 'Greet the named person.',
    parameters: {
      name: { type: 'string', required: true, description: 'Who to greet' },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return `Hello, ${args.name}!`
    },
  }))
}
```

每一段都来自前面几关：

- `inject: ['tools']`（第 2 关）：插件会等到工具注册表就绪再启动。
- `ctx.tools.register(...)`（第 3 关）：注册把注销函数挂到这个插件上，卸载时自动撤掉工具。
- `defineTool` 替你做了三件事：把 `parameters` 这个规约翻译成给模型看的 **JSON Schema**、推导出 `execute(args)` 里 `args` 的类型、并在执行前**校验模型给的参数**。
- `output.schema` 声明工具返回的规范值，`output.render` 是"原生渲染器"，负责把它变成可持久化的结果内容。

## 一个旁观者插件

工具执行时会发一个 `tools/result` 事件。另一个插件可以完全不知道谁注册了工具，只订阅这个事件：

```ts
export const name = 'tool-logger'
export const inject = ['tools']

export function apply(ctx: Context) {
  ctx.on('tools/result', (exec, result) => {
    const text = result.content
      .map((block) => (block.type === 'text' ? block.text : ''))
      .join('')
    console.log(`[tool-logger] ${exec.name} -> ${text}`)
  })
}
```

把两个插件和工具所需的依赖一起写进清单：

```yaml
- name: '@deepseek-ai/dsh-system-prompt'
- name: '@deepseek-ai/dsh-tools'
- name: './tool-logger.ts'
- name: './greet-tool.ts'
```

跑起来后，logger 会**先**看到结果，然后 `execute` 才把 promise 兑现——因为 `tools/result` 是在结果物化过程中发的，早于调用方拿到返回值。两个插件谁也不需要认识谁，它们只是共享了 `tools` 服务和 `tools/result` 事件。

> 注意清单里为什么要有 `@deepseek-ai/dsh-system-prompt`：工具需要把 schema 贡献进系统提示词，而 `dsh-tools` 注入 `systemPrompt` 服务。少了它，工具插件会像第 5 关说的那样停在 PENDING。

## 真实 Agent = 这套组合 + 更多插件

你刚才做的，就是"给模型加一个可调用能力"的完整形状。真实的 DSH Agent 无非是把这个组合加上更多插件：

- **LLM 适配器**：把"模型调用"变成一个服务。
- **Agent 循环**：决定下一步是调工具还是回话。
- **持久化**：会话、记忆、用量。
- **应用入口**：桌面、Web、无界面。

这些东西没有一个写死在主流程里，它们都只是清单上的条目。DSH 自带的 `base` 层和 `headless` 层，本身就是两份 `cordis.patch.yml`——把里面的每一行按 id 看一遍，你会发现它们全是你这六关学过的形态。

## 本关的小测

```quiz
{"id":"dsh-06-quiz","title":"进入 harness 三问","questions":[
 {"question":"给模型加一个工具，需要改 harness 的核心代码吗？","options":["需要，改工具调度主流程","不需要，写一个插件往 ctx.tools 注册即可","需要，改系统提示词的源码"],"answer":1,"explain":"工具就是一个插件，注册并随插件卸载自动注销。"},
 {"question":"tool-logger 为什么能观察到工具结果，却不需要认识 greet-tool？","options":["它 import 了 greet-tool","两者共享 tools 服务与 tools/result 事件","框架把工具结果全局广播到所有插件"],"answer":1,"explain":"服务和事件把提供方与观察方解耦。"},
 {"question":"清单里少了 dsh-system-prompt 会怎样？","options":["工具照常工作","dsh-tools 注入 systemPrompt 落空，工具插件停在 PENDING","进程直接崩溃"],"answer":1,"explain":"硬依赖缺失就是 PENDING：不报错、不运行。"}
]}
```

## 常见误区

### 误区：能调工具就说明 Agent 是"模型自己会动手"

**你以为**：模型看到工具就能自己执行。

**实际上**：模型只输出"我想调 greet，参数是 name=Cordis"；真正执行的是 `ctx.tools` 这条流水线，执行完由 harness 把结果塞回下一轮。模型负责"决定调什么"，**动手是插件的事**。

**所以**：排查"工具没被调用"时，既要看模型有没有产出正确的调用意图，也要看工具有没有真的注册上（`inject` 满足、没卡 PENDING）。

### 误区：这套插件机制只是"架构洁癖"，业务里用不上

**你以为**：我又不写框架，学这些干什么。

**实际上**：DSH 的每个能力都是插件，意味着**你能动的地方比想象的多**——换模型只是改配置，加工具只是加一个文件，拦截一次模型请求只需监听 `agent/request`。不理解这套机制，你只能等别人给你开配置项；理解了，很多"得改框架"的需求其实是一个小插件。

**所以**：这条线想给你的不是"记住几个概念"，而是**遇到需要改 DSH 行为时，先问一句：这是不是某个服务、事件或配置项就能解决的事？**

## 上篇小结

前半程六关到这里就齐了：插件怎么发起（1）、能力怎么找到（2）、注册怎么撤销（3）、事件怎么拦截（4）、应用怎么组合（5）、工具怎么接进 harness（6）。它们合起来回答了"DSH 是怎么拼起来的"。

## 下一关

但这条线还没到终点。上面六关更像"插件作者"的视角——知道怎么写一个能装进去的插件。接下来的六关换成"架构读者"的视角往下钻：`ctx` 到底是一棵什么树？一个能力为什么要拆成三种角色？一次真实请求在 DSH 里怎么走完？

先从上篇反复出现、却一直没讲透的那个东西开始：`ctx`——**上下文到底是什么**。
