---
title: 生命周期与 effect：每个注册都是可撤销的
category: dsh-core
order: 3
minutes: 15
level: intermediate
status: published
---

## 先把结论摆出来

**每个被挂载的插件实例，都对应一个 fiber（运行时句柄），它有自己的状态机。** 而凡是**通过 Cordis API 做的注册**——监听器、子插件、服务、工具——本质上都是 **effect（可撤销的副作用）**：插件卸载时，这些注册会自动撤销，包括热更新时。

这就是为什么 DSH 敢让你"随便卸载、随便热替换"：不需要你写 `cleanup` 清单，框架替你回卷。

```sketch
{
  "title": "fiber 的一生",
  "rows": [
    [
      { "id": "pending", "title": "PENDING", "sub": "等服务就绪", "tone": "gray" },
      { "id": "loading", "title": "LOADING", "sub": "apply 正在跑", "tone": "blue" },
      { "id": "active", "title": "ACTIVE", "sub": "已就绪", "tone": "green" }
    ],
    [
      { "id": "unloading", "title": "UNLOADING", "sub": "disposer 在跑", "tone": "amber" },
      { "id": "disposed", "title": "DISPOSED", "sub": "全部拆干净", "tone": "gray" },
      { "id": "failed", "title": "FAILED", "sub": "apply 或配置抛错", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "pending", "to": "loading" },
    { "from": "loading", "to": "active" },
    { "from": "active", "to": "unloading" },
    { "from": "unloading", "to": "disposed" },
    { "from": "loading", "to": "failed" }
  ]
}
```

## 用 ctx.effect() 包住"框架管不了"的资源

Cordis 管得住它自己的注册，管不住你亲手开的定时器、连接、文件监听。这类资源要包进 `ctx.effect()`，并**返回一个释放函数**：

```ts
export function apply(ctx: Context) {
  ctx.effect(() => {
    const timer = setInterval(() => console.log('tick'), 200)
    return () => {
      clearInterval(timer)   // 卸载时被调用
    }
  })
}
```

规则很短：**effect 的主体在加载时跑，它返回的释放函数在卸载时跑。** 生命周期跟插件一致的资源，你永远不需要自己手动调用释放函数。

## 很多操作"本来就是 effect"

你其实很少手写 `ctx.effect()`，因为内置的注册 API 已经替你处理了：

- `ctx.on(event, listener)`：监听器在卸载时自动移除，不用 `removeListener`。
- `ctx.plugin(child)`：子插件随父插件一起释放（递归）。
- 服务注册本身就是 effect。
- `ctx.tools.register(...)` 这类 harness 注册表，也会把注销函数挂到调用它的插件上，卸载时自动撤销（第 6 关会看到）。

**判断标准只有一条**：这个注册是通过 Cordis 的 API 做的吗？是，就自动管；不是（裸的 `setInterval` / `new WebSocket` / `fs.watch`），就自己包 `ctx.effect()`。

## 一个容易忽略的顺序问题

释放函数**按注册顺序的逆序启动**，但**多个异步释放函数是并发跑的**。如果拆除步骤必须严格先后（比如"先停止接收请求，再关数据库连接"），把它们放进**同一个** effect 里、在里面依次 `await`，别指望两个 effect 之间的顺序。

```ts
ctx.effect(() => {
  const server = startServer()
  return async () => {
    await server.stopAccepting()   // 先停止接收
    await server.close()           // 再关闭
  }
})
```

## 为什么"可撤销"值得单独一关

因为它决定了整个框架的能力上限：

- **热更新**（第 5 关）：先卸载旧实例、再加载新代码。卸载得干净，热更新才安全。
- **服务替换**（第 2 关）：提供方换人时，依赖它的插件要能干净地卸载再挂载。
- **配置变更**：改一行配置就能重新配置一个插件，靠的也是"撤销旧的、装新的"。

反过来说，**只要有一个注册没被 effect 管住，热更新就会在不知不觉里泄漏**——旧实例还在监听事件、旧定时器还在跑。这类 bug 在开发时不明显，上线跑几天才暴露。

## 本关的小测

```quiz
{"id":"dsh-03-quiz","title":"生命周期三问","questions":[
 {"question":"插件里用裸的 setInterval 开了个定时器，卸载时会发生什么？","options":["框架自动帮你 clearInterval","不会自动清，必须包进 ctx.effect() 并返回释放函数","定时器会随进程一起被 GC 立刻回收"],"answer":1,"explain":"框架只回卷通过 Cordis API 建立的注册；裸资源要自己包 effect。"},
 {"question":"ctx.on(event, fn) 注册的监听器，卸载时需要手动 removeListener 吗？","options":["需要，否则泄漏","不需要，ctx.on 本身就是 effect，卸载时自动移除","需要，但只在热更新时"],"answer":1,"explain":"内置注册 API 已经是 effect。"},
 {"question":"两个异步释放函数必须严格按先后执行，正确写法是？","options":["写成两个独立的 ctx.effect","放进同一个 effect 的释放函数里依次 await","在 apply 末尾按顺序调用"],"answer":1,"explain":"effect 之间异步释放是并发的，顺序敏感的工作要串在同一个里。"}
]}
```

## 常见误区

### 误区：不开热更新就不用管 effect

**你以为**：我又不热更新，资源泄漏离我很远。

**实际上**：卸载不只在热更新时发生。配置变更、依赖服务消失、插件被禁用（`disabled: true`）、应用按需拆掉某个能力——都会触发卸载。只要资源没被 effect 管住，每一次这些操作都在漏。

**所以**：把"每个注册都要有对应的释放"当成写插件的默认动作，而不是某个特殊场景才想起的事。

### 误区：卸载就是"把状态清空"，无所谓顺序

**你以为**：释放函数跑一遍就行，先清哪个后清哪个不重要。

**实际上**：释放顺序错了会产生"用已经关掉的资源"这类错误——比如先关了连接池、再让请求去用它。而且多个异步释放默认并发，看起来"写了顺序"其实没有。

**所以**：顺序有要求，就放进同一个 effect 里串行 `await`；这才是唯一可靠的写法。

## 下一关

到这儿，插件能提供、能消费、能干净卸载。但它们大多还是"被直接调用"。真实系统里还需要一种**不知道对方是谁、也能插一脚**的通信方式——事件。下一关讲五种分发，以及最像"拦截器"的 waterfall。
