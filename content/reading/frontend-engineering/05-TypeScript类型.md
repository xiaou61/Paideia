---
title: TypeScript：类型是给谁看的
category: frontend-engineering
order: 5
chapter: 可维护
minutes: 28
level: intermediate
status: published
---

## 这一关在解决什么

很多人对 TypeScript 的期待是"帮我抓 bug"，然后发现：

- 线上照样 `Cannot read properties of undefined`；
- 写了 `any` 之后一切照旧，类型形同虚设；
- 接口加了个字段，前端不改也能编译通过。

原因是没搞清一件事：**类型只在编译期存在，编译之后全部消失**。它管不到运行时，管不到接口返回，管不到用户输入。

那它管什么？这一关要讲清楚：类型真正在替你做的事是**在改代码的时候，把所有受影响的地方一次性列出来**。

## 讲解

### 类型在编译后不存在

```ts
function greet(name: string) {
  return 'Hi, ' + name
}
```

编译产物：

```js
function greet(name) {
  return 'Hi, ' + name
}
```

`: string` 完全消失。所以：

- `greet(123)` 在打包后**不会报错**，只会输出 `Hi, 123`；
- 只有在你**写代码时**（编辑器 / `tsc --noEmit` / 构建前的类型检查）它才拦得住。

这条决定了 TS 的第一个使用原则：**类型错误必须在 CI 里跑，不能只靠编辑器提示**。编辑器里有人关掉 TS 服务、有人用别的 IDE，就等于没有检查。

### 现代工具链里，`tsc` 通常不负责产出

构建流程里两个角色是分开的：

| 角色 | 谁来做 | 干什么 |
| --- | --- | --- |
| 转译 | esbuild / swc / Vite | 删掉类型，变成 JS，追求快 |
| 类型检查 | `tsc --noEmit` | 只检查，不产出文件，慢但全 |

Vite 默认**不做类型检查**——它只转译，速度毫秒级。这也是一堆人项目跑得好好的、类型错误却攒了几百个的原因。

所以 `package.json` 里应该有一条明确的命令，并在 CI 里跑：

```json
{
  "scripts": {
    "typecheck": "tsc --noEmit",
    "build": "tsc --noEmit && vite build"
  }
}
```

### `any` 是关机键，`unknown` 是减速带

```ts
const data: any = await res.json()
data.user.name.toLowerCase()   // 编译通过，运行时可能炸
```

`any` 会让类型检查在这个值上**彻底失效，并且传染**：`data.user` 是 any，`data.user.name` 还是 any，一直往下传。

需要"暂时不知道是什么"时用 `unknown`：

```ts
const data: unknown = await res.json()
// data.user 会直接报错——必须先证明它是什么
if (isUser(data)) {
  data.name  // 这里才有类型
}
```

`unknown` 强制你在使用前做检查（类型守卫 / 校验），这正是接口数据该有的态度。

### 用类型表达"不可能的状态"

这是 TS 最有价值、也最少被用好的部分。

考虑一个请求的四种状态：

```ts
// 反例：三个可选字段，能组合出 8 种状态，其中 6 种是错的
interface State {
  loading?: boolean
  data?: User
  error?: string
}
```

这个类型允许 `{ loading: true, data: {...}, error: 'x' }`——三个同时出现。代码里就得写一堆 `if` 兜底，永远不确定哪个是真的。

用**判别联合**表达：

```ts
type State =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: User }
  | { status: 'error'; error: string }
```

现在：

- `status === 'success'` 时，`data` **一定存在**，不需要 `data!`；
- 想访问 `state.data` 而没先判断 `status`，编译器直接报错；
- 想新增一个状态，所有 `switch` 都会提示"这里没处理"。

**类型的作用不是描述现状，而是排除错误组合**。一个字段可有可无，往往说明你没想清楚状态机。

### 类型真正的回报在重构

```ts
// 原来
interface User { id: string; nickname: string }

// 需求变了：昵称拆成姓和名
interface User { id: string; firstName: string; lastName: string }
```

改完这一行，`tsc` 会列出**所有还在用 `nickname` 的地方**。项目里 37 处，一次列全，改完就干净。

没有类型的世界里，这件事靠 `grep nickname` + 人眼判断"这个 nickname 是不是同一个东西"，漏一处就是线上空白。**这就是类型最大的价值：它不是文档，是重构时的一次性全量检查。**

## 常见误区

### 误区：类型是给人看的注释，写宽一点没关系

**你以为**：`data: any` 只是偷懒，功能照样对。
**其实**：`any` 会把整条链路变成无检查区。更隐蔽的是它会**传染**：函数的参数是 `any`，返回值推导出来也是 `any`，下游全部失效。一个 `any` 能让整个文件的类型保护归零。
**怎么验证**：把 `tsconfig` 的 `noImplicitAny` 打开（严格模式下默认开），然后看报错数量。从 `any` 数量最多的那个文件开始改，收益最大。

### 误区：类型写全了，运行时就不会有 undefined

**你以为**：`interface User { name: string }` 保证了 `user.name` 一定有值。
**其实**：类型只是你对编译器的**声明**，不是运行时的保证。接口少返回字段、后端改了字段名、数据是 `null`——类型都不会知道。运行时校验（或用 zod 这类库）才是那道门。
**怎么验证**：把接口 mock 成少一个字段，看页面崩不崩。崩了说明类型在骗你——它在编译期信了你的声明。

### 误区：类型检查慢，放到发布前统一跑一次就行

**你以为**：开发时省时间，最后一把过。
**其实**：类型错误攒到几十上百个时，会进入"改一个冒三个"的状态，最后大家开始加 `any` 和 `@ts-ignore` 让它闭嘴，类型系统就此失效。它必须像测试一样**每次提交都跑**，保持零错误基线。
**怎么验证**：看仓库里 `@ts-ignore` / `as any` 的数量。超过几十处，说明这条纪律已经破了。

## 带走一句

**类型不保证运行时正确，它保证你改一处时，能看见还有哪几处要跟着改**——把它当成重构的雷达，而不是运行时防护栏。

```quiz
{"id":"fe-eng-05-quiz","title":"TypeScript","questions":[
 {"question":"TypeScript 的类型信息在打包产物里是什么？","options":["被转成运行时校验","完全不存在","存成注释","存成 .d.ts 供浏览器读取"],"answer":1,"explain":"类型在编译期用于检查，编译后全部擦除；所以类型错误必须在 CI 里跑 tsc --noEmit，不能指望运行时。"},
 {"question":"要接一个还不确定结构的接口返回值，应该用什么类型？","options":["any","unknown","object","never"],"answer":1,"explain":"unknown 必须在使用前通过类型守卫或校验证明它是什么；any 会让检查彻底失效并传染下游。"},
 {"question":"用判别联合（status: 'success' | 'error' …）表达请求状态，最大的好处是？","options":["代码更短","排除了不可能的状态组合，访问字段前必须先判断 status","运行更快","能自动生成接口"],"answer":1,"explain":"可选字段能组合出大量非法状态；判别联合让「success 时 data 一定存在」成为编译期事实，新增状态时所有分支都会被提示补上。"}
]}
```
