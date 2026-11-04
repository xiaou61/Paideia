---
title: 异步与 fetch
category: frontend-from-zero
order: 8
chapter: 动起来
minutes: 26
level: beginner
status: published
---

## 这一关在解决什么

前端几乎所有数据都来自网络，而网络会慢、会失败、会超时。这一关讲三件事：

1. `Promise` 与 `async/await` 到底在等什么；
2. 用 `fetch` 取数据时，哪些错误**不会**被 `catch` 到；
3. 一个能用的请求必须处理哪几种状态。

## 讲解

### 先理解"不阻塞"

JavaScript 在主线程上跑，同一时刻只做一件事。网络请求不会让主线程停下来等——它把任务交给浏览器，等结果回来再排进队列：

```js
console.log('1')
fetch('/api/data').then(() => console.log('3'))
console.log('2')
// 输出：1 2 3
```

`then` 里的回调要等当前同步代码跑完才执行。这就是为什么"请求发出去了但下面的代码先跑了"不是 bug。

### Promise 的三个状态

- `pending`：还没结果；
- `fulfilled`：成功，带一个值；
- `rejected`：失败，带一个原因。

状态一旦确定就不会再变。`async/await` 只是把 `.then` 写成同步的样子：

```js
async function load() {
  try {
    const res = await fetch('/api/data')
    const data = await res.json()
    return data
  } catch (error) {
    console.error('请求失败', error)
  }
}
```

`await` 会暂停**这个函数**，不暂停整个页面。

### `fetch` 最容易踩的坑：HTTP 错误不 reject

```js
const res = await fetch('/api/not-found')
console.log(res.ok)    // false
console.log(res.status) // 404
```

`fetch` 只在**网络层失败**（断网、DNS 失败、CORS 被拦）时 reject。服务器返回 404、500 时它是"成功完成了一次 HTTP 交互"，`res.ok` 为 `false`，但不会抛错。

所以每次都要自己检查：

```js
async function getJson(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`)
  }
  return res.json()
}
```

否则你会把一段 500 的 HTML 错误页当 JSON 解析，得到 `Unexpected token '<'`——这个报错看起来像前端 bug，实际是后端 500。

### 超时与取消

`fetch` 没有内置超时。要超时得用 `AbortController`：

```js
const controller = new AbortController()
const timer = setTimeout(() => controller.abort(), 8000)
try {
  const res = await fetch(url, { signal: controller.signal })
  // ...
} finally {
  clearTimeout(timer)
}
```

`AbortError` 与"请求失败"要区分：用户快速切换页面导致的取消不是错误，不该弹红条。

### 并发请求

```js
const [user, orders] = await Promise.all([
  getJson('/api/user'),
  getJson('/api/orders'),
])
```

`Promise.all` 一起等，任何一个失败就整体失败。需要"部分成功也要展示"时用 `Promise.allSettled`，它给每个任务一个 `{ status, value/reason }`。

### 一个页面必须处理的四种状态

```js
const [state, setState] = useState({ status: 'idle', data: null, error: null })

// idle → loading → success | error
```

- **加载中**：给骨架或文字，不要空白；
- **成功但有数据**：正常渲染；
- **成功但为空**：明确说"还没有内容"，而不是一片空白；
- **失败**：说清是网络问题还是服务问题，给"重试"。

只写"成功"分支的页面，在弱网和空数据下看起来就是坏的——这四态是产品要求，不是锦上添花。

## 常见误区

### 误区：`fetch` 失败会进 `catch`

**你以为**：`try { await fetch(...) } catch` 能兜住 500。
**其实**：只有网络层错误才 reject；HTTP 4xx/5xx 是正常返回，必须自己看 `res.ok`。
**怎么验证**：请求一个不存在的路径，看 `catch` 是否执行——不会；打印 `res.status` 才能看到 404。

### 误区：`async` 函数里的异常会自动被处理

**你以为**：写了 `async` 就不会有未捕获异常。
**其实**：`async` 函数返回一个 Promise；如果调用方不 `await` 也不 `.catch`，失败会变成"未处理的 Promise 拒绝"，控制台报错但页面无反应。
**怎么验证**：调用一个会抛错的 async 函数但不 await，看控制台是否出现 `Uncaught (in promise)`。

### 误区：并行请求一定更快

**你以为**：所有请求都 `Promise.all` 最省时间。
**其实**：浏览器对同域并发连接数有限制；几十个请求同时发会排队，还可能把后端打满。有依赖关系的请求必须串行；大量小请求应该合并成一次。
**怎么验证**：Network 面板看瀑布图，请求是不是在排队等连接。

## 带走一句

**`fetch` 只在网络层失败时抛错，HTTP 错误要自己看 `res.ok`**——再加超时与四态，才算一个能上线的请求。

```quiz
{"id":"fe-08-quiz","title":"异步与 fetch","questions":[
 {"question":"服务器返回 500 时，`await fetch(url)` 的行为是？","options":["抛出异常，进 catch","正常返回一个 Response，`res.ok` 为 false","返回 null","自动重试"],"answer":1,"explain":"fetch 只在网络层失败时 reject；HTTP 状态码是正常响应，必须自己检查 res.ok。"},
 {"question":"`fetch` 本身缺少、需要 AbortController 补的能力是？","options":["跨域","超时与取消","JSON 解析","请求头"],"answer":1,"explain":"fetch 没有内置超时；用 AbortController 的 signal 可以在超时或切页时取消请求。"},
 {"question":"一个有数据的页面，除了成功态还至少要处理哪几种？","options":["只需要成功态","加载中、空数据、失败","只需要失败态","深色模式和浅色模式"],"answer":1,"explain":"弱网与空数据是常态；只写成功分支的页面在这两种情况下看起来就是坏的。"}
]}
```
