---
title: 进入 harness：一个工具就是一个插件
category: dsh-core
order: 6
minutes: 18
level: intermediate
status: published
---

## 四行清单，缺的是一个执行器

`docs/cordis-tutorial/07-into-the-harness.zh.md:78-83` 给的组合清单是四行：

```yaml
- name: '@deepseek-ai/dsh-system-prompt'
- name: '@deepseek-ai/dsh-tools'
- name: './tool-logger.ts'
- name: './greet-tool.ts'
```

同一页 `:91-94` 承诺的输出是：

```
[tool-logger] greet -> Hello, Cordis!
tool replied: [{"type":"text","text":"Hello, Cordis!"}]
```

按 0.2.2-rc.5 的源码读，这份清单跑不到第二行。`greet-tool.ts` 的 `apply` 末尾那段 `await ctx.tools.execute({...})` 会 reject：

```
The selected tool executor is unavailable. Load an executor in this composition before running the agent.
```

（`packages/core/tools/src/executor.ts:96-101` 的 `ToolExecutorUnavailableError`，code `TOOL_EXECUTOR_UNAVAILABLE`。）抛它的地方只有一处：

```ts
// packages/core/tools/src/index.ts:357-367（节选）
private selectExecutor(scope) {
  ...
  if (executor === undefined) throw new ToolExecutorUnavailableError()
```

四行清单里，没有一行会走到 `ctx.tools.setExecutor`。

我原来的判断是「工具注册成功 = 能被调用」。在 harness 里这是两个人的活：`ctx.tools` 管登记表，执行器管派发。v0.2.1-rc.2 起执行器被拆成独立插件（`docs/upgrade-guide/v0.2.1-rc.2/tool-executor-plugins/guide.zh.md`），内置的 base / headless 组合都补上了，手写的组合不会自动有。

本文对着 0.2.2-rc.5 的源码读，版本据 `packages/core/tools/package.json`。

## 一个 greet 要占三个位置

`greet` 从被注册到被调用，跨三个位置，每个位置由不同的插件负责。

**位置一，登记表。** `defineTool` 把定义规约成注册用的形状，`ctx.tools.register` 挂进工具注册表。注册走的是 `this.layers.effect(this.ctx, ...)`（`packages/core/tools/src/index.ts:148`），所以插件卸载时工具跟着注销，不用手写 `dispose`。

```ts
// greet-tool.ts，照 docs/cordis-tutorial/07-into-the-harness.zh.md:11-34 的结构
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

`inject: ['tools']` 让插件等注册表就绪。`ToolsService` 自己还注入 `systemPrompt`（`index.ts:100`），所以清单里少了 `@deepseek-ai/dsh-system-prompt`，整条链停在 PENDING——第 5 关给过查法。

**位置二，派发者。** 执行器是独立插件，整个实现就这么点：

```ts
// packages/core/tool-executor-native/src/index.ts:25-35
export const name = 'native-tool-executor'
export const inject = ['tools']

export function apply(ctx: Context): void {
  ctx.tools.setExecutor(new NativeToolExecutor(ctx))
}
```

一个作用域只认一个执行器：`setExecutor` 在已有执行器时直接抛 `Only one tool executor may be registered in each scope`（`index.ts:328`）。所以 base 层是「两个都列、只启用一个」——`packages/bundle/base/cordis.patch.yml:524-535` 里 `tool-executor-native` 生效，`tool-executor-ptc` 标着 `disabled: true`。卸载执行器也不会回退到别人：`index.ts:345-348` 把记录标成不可用，之后派发继续抛 `TOOL_EXECUTOR_UNAVAILABLE`。

**位置三，结果。** 工具体跑完后，返回值先被物化、投影成 content，再发 `tools/result`（`packages/core/tools/src/events.ts:49`，`@mode emit`）。旁观插件看的正是这个位置。

补上执行器，清单是五行：

```yaml
- name: '@deepseek-ai/dsh-system-prompt'   # ToolsService 静态注入它
- name: '@deepseek-ai/dsh-tools'
- name: '@deepseek-ai/dsh-tool-executor-native'   # 少这行，execute 直接 reject
- name: './tool-logger.ts'
- name: './greet-tool.ts'
```

## 谁先拿到结果

`[tool-logger]` 那行日志，和工具体里的输出，谁先？先看工具体怎么被调：

```ts
// packages/core/tools/src/pipeline.ts:277-288（节选）
const tool = this.resolveExecution(exec.name, exec.agent, exec.parent !== undefined, exec.toolSet)
if (!tool) throw new ToolNotFoundError(exec.name)
state.bodyInvoked = true
const returned = await tool.execute(exec.arguments, exec)
const result = this.createSuccessResult(exec, tool, returned)
```

`await tool.execute(...)` 在这一行就已经兑现。抛错也不往上冒，被下面的 `catch` 收成 `isError: true` 的结果。然后：

```ts
// packages/core/tools/src/pipeline.ts:394-409（finish，节选）
this.notifyResult(exec, finalResult)
return finalResult
```

```ts
// packages/core/tools/src/pipeline.ts:420-439（notifyResult，节选）
const callbacks = this.ctx.events.dispatch('emit', [scopeTarget(this.tools, exec.agent), 'tools/result', exec, result])
for (const callback of callbacks) {
  try {
    const returned = callback(exec, result)
    void Promise.resolve(returned).catch(reportFailure)
  } catch (error) {
    reportFailure(error)
  }
}
```

于是顺序是：工具体 → 同步监听者 → 调用方。`notifyResult` 在 `return` 之前把整个 for 循环同步跑完，所以同步的 `[tool-logger]` 一定早于 `await ctx.tools.execute(...)` 恢复。

这里有个容易讲反的地方：**被推迟的不是工具体的 promise**，它在上面那行 `await` 就已经兑现了；被推迟的是调用方拿到返回值。听到「logger 先到、execute 后兑现」时，那个「兑现」指的是调用方那次 `await`，不是工具体。

还有一层：这不是「框架保证监听者先跑」，是这张实现里 notify 排在 return 前面。第 4 关那张表给过 `emit` 的语义——同步广播、不收返回值。落到这里的两个后果：

- 监听者一旦在里面 `await`，它后面那半段就没人等：返回的 promise 被 `void ... .catch()` 丢掉，顺序不再固定；
- 监听者抛错只进 `reportFailure` → `ctx.logger.warn`（`pipeline.ts:426`），工具结果不变。

## 失败不在同一层，报错也不是一种

同样是「工具调用失败」，六种情况长得很像，位置完全不同：

| 你看到的 | 先看哪 | 为什么在这层 |
| --- | --- | --- |
| 一行日志都没有，进程正常 | 组合清单里 `@deepseek-ai/dsh-system-prompt` 在不在 | `ToolsService` 静态注入 `systemPrompt`，缺了插件停在 PENDING（第 5 关的查法） |
| `execute()` 直接 reject：`The selected tool executor is unavailable...` | 组合里有没有执行器插件 | 只在派发时选执行器（`index.ts:357-367`），注册阶段不查 |
| 结果里是 `Error: unknown tool "greet"` | 名字有没有注册上、当前作用域看不看得到 | `resolveExecution` 没找到定义（`pipeline.ts:278`、`contracts.ts:292-302`） |
| 结果里是 `Error: invalid arguments: ...` | `parameters` 声明的类型和给的值 | `defineTool` 在校验后抛，`execute` 根本没跑（`schema.ts:598-602`） |
| 结果里是 `Error:` 加上你自己 throw 的 message | 工具体自己 | 异常被收成结果，不冒到调用方（`pipeline.ts:285-288`） |
| 体里一切正常，结果是 `Error: tool "greet" returned invalid output: ...` | 返回值合不合 `output.schema` | 先校验返回值再 `render`（`pipeline.ts:558-566`、`contracts.ts:327-337`） |

最后一行值得单独说：`output.schema` 不是给渲染看的注释，是校验器。`createSuccessResult` 先用 `validateJsonSchemaValue(tool.output.schema, ...)` 校验返回值，再调 `output.render` 生成 content。返回对象却声明 `{ type: 'string' }`，体里打印一切正常，调用结果是 error。

第三行也常被误判成「模型调错名字」：名字对了但对当前 agent 不可见，报的是同一句话。

## 想自己验顺序

在 `execute` 里加一行 `console.log('body ran')`，同步监听者会把它夹在中间：先 `body ran`，再 `[tool-logger] greet -> Hello, Cordis!`，最后 `tool replied:`。

再翻一步：把 logger 改成先 `await` 一个 `Promise.resolve()` 再打印，那行会排到 `tool replied:` 之后，甚至可能不再被等到——`pipeline.ts:436` 的 `void ... .catch()` 只是吞掉异常，不保留顺序。注意写成 `async` 但函数体内没有 `await` 时，代码仍然同步跑完，顺序不变；这不是「async 就乱序」，是「await 之后没人等」。

这段顺序是从上面三段代码读出来的，本文没有实际运行——原因写在最后一节。

## 检查一下

```quiz
{"id":"dsh-06-quiz","title":"工具、执行器与结果事件","questions":[
 {"question":"组合清单里删掉 dsh-tool-executor-native，跑到 ctx.tools.execute(...) 时发生什么？","options":["注册 greet 时就抛错，插件 apply 失败","返回的 promise reject，消息是 The selected tool executor is unavailable...","调用照常返回，只是 content 是空数组","进程不报错也不输出，停在 PENDING"],"answer":1,"explain":"执行器只在派发时选（packages/core/tools/src/index.ts:357-367），找不到就抛 ToolExecutorUnavailableError（executor.ts）；注册阶段（index.ts:128-153）不检查执行器。"},
 {"question":"logger 是同步函数 ctx.on(tools/result, (exec, result) => console.log(...))，它和 greet 的 execute 里那行 console.log 谁先？","options":["execute 里的先，同步监听者的日志在 execute() 的 await 恢复之前就跑完","监听者先，因为 notifyResult 在 finish 的 return 之后调用","顺序不确定，取决于 NativeToolExecutor 怎么实现","取决于工具有没有声明 isConcurrencySafe"],"answer":0,"explain":"pipeline.ts:277-288 的 await 先兑现工具体；notifyResult（pipeline.ts:420-439）在 finish return 前同步跑完，所以同步监听者早于调用方恢复。isConcurrencySafe 只影响能不能并行调度。"},
 {"question":"greet 声明 output.schema 为 { type: string }，但 execute 返回一个对象，结果是？","options":["照常成功，render 不读 schema","抛到调用方，整批工具调用中断","这次调用变成 isError 结果，content 是 Error: tool 「greet」 returned invalid output: ...","进程崩溃"],"answer":2,"explain":"createSuccessResult 先 validateJsonSchemaValue(tool.output.schema, ...) 再 render，违规抛 ToolOutputError（contracts.ts:327-337），在 pipeline.ts:285-288 被收成 isError 结果，不会冒到调用方。"}
]}
```

## 还差的

- 官方文档 `:91-94` 那两行输出，本文引用但没有复跑：`F:\tmp-dsh-review` 没装 node_modules，npm 上 `@deepseek-ai/dsh-tools` 只有 0.0.1-rc.1，不是 0.2.2-rc.5。「补上执行器就得到那两行」是按 `pipeline.ts:277-288` 与 `:394-409` 推的，不是跑出来的。
- `tools/pre-execute` 与 `tools/execute` 两个 waterfall（`events.ts:18`、`:29`）怎么拦、怎么拒一次调用，留给第 10 关；本文的表格没单独列「被 guard 拒绝」与「被 abort」两种，它们和别的失败一样走 post-result。
- PTC 执行器只说到它是 disabled。切到 PTC 后模型收到的是 `run_code` 而不是每个工具的 schema（v0.2.1-rc.2 升级指南），`tools/result` 的 payload 怎么变，没读代码确认。
- `deferLoading`、`toolSet`、`definitions()` / `schemas()`（`index.ts:305-318`）对上下文的实际影响没有测量，工具集多大开始变贵，没有数据。
- base 层同时列了 native 与 ptc 两个执行器（`cordis.patch.yml:524-535`），headless 层的差异没读完——换执行器到底该改组合还是改配置，本文只说到「一个 scope 一个」。
