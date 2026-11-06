---
title: DOM 与事件
category: frontend-from-zero
order: 7
chapter: 动起来
minutes: 26
level: beginner
status: published
---

## 这一关在解决什么

页面在浏览器里是一棵 DOM 树。这一关回答三个问题：

1. 怎么找到节点、怎么改它；
2. 点击到底是怎么传到你的代码里的；
3. 为什么给"动态插入的元素"绑事件不生效。

不依赖任何框架，手写一遍，后面看 React 才知道它替你做了什么。

打个比方：DOM 像一棵家谱树，事件像家族里传话——从长辈往下传、再从当事人往上传。真实机制是事件先捕获、后冒泡，所以能在父容器上监听一次就接住所有子项的点击。

```code-flow
{
  "id": "fe-07-events",
  "title": "DOM 与事件：查节点 → 改节点 → 事件委托",
  "nodes": [
    { "id": "query", "label": "查找节点", "sub": "querySelector", "layer": 0, "order": 0 },
    { "id": "update", "label": "改节点", "sub": "textContent 更安全", "layer": 1, "order": 0 },
    { "id": "event", "label": "绑定监听", "sub": "addEventListener", "layer": 2, "order": 0 },
    { "id": "bubble", "label": "捕获 · 冒泡", "sub": "外层 ↔ 目标", "layer": 3, "order": 0 },
    { "id": "delegate", "label": "事件委托", "sub": "父容器监听一次", "layer": 4, "order": 0 },
    { "id": "thrash", "label": "强制布局", "sub": "读尺寸触发", "layer": 5, "order": 0 }
  ],
  "edges": [
    { "from": "query", "to": "update", "label": "选中" },
    { "from": "update", "to": "event", "label": "响应" },
    { "from": "event", "to": "bubble", "label": "捕获后冒泡" },
    { "from": "bubble", "to": "delegate", "label": "利用冒泡" }
  ],
  "steps": [
    {
      "title": "查找节点",
      "desc": "querySelectorAll 是静态快照",
      "nodes": ["query"],
      "edges": [],
      "code": "document.querySelector('.card')      // 第一个匹配\ndocument.querySelectorAll('.card')   // 静态 NodeList\nelement.closest('.card')             // 从自己往上找",
      "focus": [1, 2, 3],
      "log": [{ "k": "注意", "v": "快照，不含后来插入的元素" }],
      "explain": "需要动态结果时每次重新查，或者用事件委托。"
    },
    {
      "title": "改节点：优先 textContent",
      "desc": "innerHTML 会把内容当 HTML 解析",
      "nodes": ["query", "update"],
      "edges": ["query>update"],
      "code": "el.textContent = '你好'       // 纯文本，安全\nel.innerHTML = '<b>你好</b>'  // 解析 HTML，有 XSS 风险",
      "focus": [1, 2],
      "log": [{ "k": "安全", "v": "textContent" }],
      "explain": "拼接用户输入用 innerHTML，等于把 XSS 的入口留在页面上。"
    },
    {
      "title": "绑定监听：先捕获，再冒泡",
      "desc": "事件从最外层往下捕获，再从目标往上冒泡",
      "nodes": ["update", "event", "bubble"],
      "edges": ["update>event", "event>bubble"],
      "code": "button.addEventListener('click', (event) => {\n  event.preventDefault()   // 阻止默认行为\n  event.stopPropagation()  // 阻止继续冒泡（少用）\n  console.log(event.target)\n})",
      "focus": [1, 2, 3, 4, 5],
      "log": [{ "k": "顺序", "v": "捕获 → 目标 → 冒泡" }],
      "explain": "利用冒泡，可以只在父容器上绑一次，就接住所有子项的点击。"
    },
    {
      "title": "事件委托：父容器上绑一次",
      "desc": "动态插入的子项不用重新绑",
      "nodes": ["event", "bubble", "delegate"],
      "edges": ["event>bubble", "bubble>delegate"],
      "code": "list.addEventListener('click', (event) => {\n  const item = event.target.closest('[data-id]')\n  if (!item) return\n  console.log('点了', item.dataset.id)\n})",
      "focus": [1, 2, 3, 4, 5],
      "log": [{ "k": "收益", "v": "动态子项不用重绑" }],
      "explain": "只在父容器绑一次，用 closest 找到真实目标；列表、表格、菜单都该这么写。"
    },
    {
      "title": "读布局会强制同步计算",
      "desc": "写样式后立刻读尺寸，浏览器必须马上布局",
      "nodes": ["thrash"],
      "edges": [],
      "code": "el.style.width = '200px'\nconsole.log(el.offsetHeight) // 浏览器必须立刻布局才能回答",
      "focus": [1, 2],
      "log": [{ "k": "修法", "v": "先批量读、再批量写" }],
      "explain": "循环里反复'写样式 → 读尺寸'会触发多次强制布局（layout thrashing），页面明显卡顿。"
    }
  ]
}
```

## 讲解

### 查找节点

```js
document.querySelector('.card')        // 第一个匹配
document.querySelectorAll('.card')     // NodeList（可 forEach，但不是数组）
document.getElementById('main')        // 按 id，最快
element.closest('.card')               // 从自己往上找最近的匹配
```

`querySelectorAll` 的结果是**静态快照**：之后新插入的元素不在里面。需要动态结果时每次重新查，或者用事件委托（见下）。

### 改节点

```js
el.textContent = '你好'          // 纯文本，安全
el.innerHTML = '<b>你好</b>'     // 解析 HTML，有 XSS 风险
el.classList.add('active')
el.style.color = 'red'           // 行内样式，优先级很高
el.setAttribute('aria-expanded', 'true')
```

**优先 `textContent`**。`innerHTML` 只在内容确实是你自己生成的 HTML 时用。拼接用户输入（评论、昵称、搜索词）等于把 XSS 开口留在页面上。

创建与插入：

```js
const li = document.createElement('li')
li.textContent = '新条目'
list.append(li)          // 追加到末尾
list.prepend(li)         // 插到最前
li.remove()              // 删除自己
```

### 事件：监听、冒泡、委托

```js
button.addEventListener('click', (event) => {
  event.preventDefault()  // 阻止默认行为（表单提交、链接跳转）
  event.stopPropagation() // 阻止继续冒泡（少用）
  console.log(event.target)
})
```

事件从最外层往下捕获、再从目标往上冒泡。利用冒泡，可以只在父容器上绑一次：

```js
list.addEventListener('click', (event) => {
  const item = event.target.closest('[data-id]')
  if (!item) return
  console.log('点了', item.dataset.id)
})
```

这就是**事件委托**：动态插入的子项不用重新绑，性能也更好。列表、表格、菜单都该这么写。

### 表单事件

```js
form.addEventListener('submit', (event) => {
  event.preventDefault()                 // 不要整页刷新
  const data = new FormData(form)
  console.log(Object.fromEntries(data))
})
```

`submit` 要绑在 `form` 上，而不是按钮的 `click`——这样回车提交也能被接住。

### 读布局会强制同步计算

```js
el.style.width = '200px'
console.log(el.offsetHeight) // 浏览器必须立刻布局才能回答
```

在一个循环里反复"写样式 → 读尺寸"，会触发多次强制布局（layout thrashing），页面明显卡顿。正确做法是**先批量读、再批量写**：

```js
const heights = items.map((el) => el.offsetHeight) // 先读
items.forEach((el, i) => { el.style.height = `${heights[i]}px` }) // 再写
```

### 什么时候该用框架

手写 DOM 在页面简单时完全够用。一旦出现"同一份数据要同步到多个地方"，手写就会开始漏：改了列表忘了改计数、删了行忘了更新空状态。框架解决的就是这件事——**把数据作为唯一来源，界面由它推出来**（第 10 关）。

## 常见误区

### 误区：脚本放在 `<head>` 里也能查到元素

**你以为**：只要写了 `querySelector` 就能拿到。
**其实**：`<head>` 里的脚本执行时，`body` 还没解析，元素不存在，返回 `null`。要么把脚本放 `</body>` 前，要么用 `defer`，要么包在 `DOMContentLoaded` 里。
**怎么验证**：在 `<head>` 里 `console.log(document.querySelector('h1'))`，看到的是 `null`。

### 误区：给每个按钮单独绑事件更"直接"

**你以为**：一个按钮一个 `addEventListener`，逻辑清楚。
**其实**：列表有 100 行就要绑 100 次；新增行还要再绑一次，忘了就"新按钮没反应"。委托在父容器上绑一次就够。
**怎么验证**：动态插入一行后点击它，看事件是否触发——单独绑定的版本不会。

### 误区：`innerHTML` 和 `textContent` 可以互换

**你以为**：都是设置内容。
**其实**：`textContent` 把内容当纯文本；`innerHTML` 当 HTML 解析并执行。用 `innerHTML` 拼用户输入就是 XSS：输入 `<img src=x onerror=alert(1)>` 会执行脚本。
**怎么验证**：本地用 `innerHTML` 插入上面那串，看是否弹窗；换成 `textContent` 后它只显示成文字。

## 带走一句

**查节点、改内容、事件委托**——手写 DOM 的三件事；一旦开始"多处同步"，就该考虑把界面交给数据驱动。

```quiz
{"id":"fe-07-quiz","title":"DOM 与事件","questions":[
 {"question":"列表项是动态插入的，怎样让新项也能响应点击？","options":["每次插入后重新绑定","在父容器上做事件委托","用 innerHTML 重建整个列表","加 onclick 属性到每个新节点"],"answer":1,"explain":"利用冒泡在父容器监听一次，用 closest 找到真实目标；新子项不需要单独绑定。"},
 {"question":"`textContent` 相比 `innerHTML` 的关键优势是？","options":["更快","不会把内容当 HTML 解析执行，避免 XSS","支持中文","能保留换行"],"answer":1,"explain":"拼接用户输入时用 innerHTML 等于开了一个脚本执行入口；textContent 只当纯文本。"},
 {"question":"脚本放在 `<head>` 里查不到 `body` 中的元素，常用修法是？","options":["把脚本放 `</body>` 前或加 defer","给元素加 id","用 window.onload 包住所有逻辑","把 HTML 改成大写"],"answer":0,"explain":"脚本执行时 body 尚未解析；defer 会等解析完成再按顺序执行，也不会阻塞解析。"}
]}
```
