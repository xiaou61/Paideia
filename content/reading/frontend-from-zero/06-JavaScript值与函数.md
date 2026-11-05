---
title: JavaScript：值、类型与函数
category: frontend-from-zero
order: 6
chapter: 动起来
minutes: 28
level: beginner
status: published
---

## 这一关在解决什么

JavaScript 的坑大多集中在三处：**类型会偷偷转换**、**对象是引用**、**函数里的 `this` 看调用方式**。这一关把这三件事讲清楚，后面的 DOM 与框架才不用靠猜。

打个比方：原始类型像复印一份文件，改复印件不影响原件；对象像共享同一个云文档链接，谁改都一起变。真实机制是原始类型按值复制，对象赋值复制的是内存地址（引用）。

```sketch
{
  "title": "JS 三条主线：值·引用·this",
  "rows": [
    [
      { "id": "primitive", "title": "原始类型", "sub": "按值复制·不可变", "tone": "blue" },
      { "id": "object", "title": "对象", "sub": "按引用共享", "tone": "violet" }
    ],
    [
      { "id": "eq", "title": "== 与 ===", "sub": "默认用 ===", "tone": "amber" },
      { "id": "truthy", "title": "真值与假值", "sub": "八个假值", "tone": "amber" },
      { "id": "fn", "title": "函数与 this", "sub": "this 看调用方式", "tone": "green" }
    ],
    [
      { "id": "spread", "title": "解构与展开", "sub": "做不可变更新", "tone": "green" },
      { "id": "array", "title": "数组四方法", "sub": "map/filter/find/reduce", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "primitive", "to": "eq", "label": "先分清类型" },
    { "from": "object", "to": "spread", "label": "复制才断引用" },
    { "from": "fn", "to": "array", "label": "写回调" }
  ]
}
```

## 讲解

### 原始类型与对象

原始类型（按值传递、不可变）：

```js
string / number / boolean / null / undefined / symbol / bigint
```

对象（按引用传递、可变）：

```js
{} / [] / function / Date / Map / Set ...
```

```js
let a = 1
let b = a
b = 2
console.log(a) // 1：复制的是值

const x = { n: 1 }
const y = x
y.n = 2
console.log(x.n) // 2：两个变量指向同一个对象
```

"改了一个，另一个也变了"几乎都是引用共享。要断开就复制：浅拷贝用 `{ ...x }` / `[...arr]`，深拷贝用 `structuredClone(x)`。

`const` 只约束**变量不能重新赋值**，不约束对象内部：

```js
const user = { name: 'A' }
user.name = 'B'   // 可以
user = {}          // 报错
```

### `==` 与 `===`

```js
'1' == 1        // true（会做类型转换）
'1' === 1       // false
null == undefined // true
NaN === NaN     // false，判断 NaN 用 Number.isNaN
```

**默认用 `===`**。`==` 只在一种场景有用：`value == null` 同时匹配 `null` 与 `undefined`。

### 真值与假值

假值只有八个：`false`、`0`、`-0`、`0n`、`''`、`null`、`undefined`、`NaN`。其余都是真值——包括 `[]` 和 `{}`。

```js
if ([]) console.log('会执行')     // 空数组是真值
[].length === 0                   // 判空要看长度
```

这是"接口没数据但页面显示了"的常见来源：判断写成了 `if (list)`，而 `[]` 永远为真。

### 函数：声明、表达式、箭头

```js
function add(a, b) { return a + b }        // 声明，会提升
const sub = function (a, b) { return a - b }
const mul = (a, b) => a * b                 // 箭头，单表达式可省略 return
```

箭头函数与普通函数的关键差别：**箭头函数没有自己的 `this`**，它从外层词法作用域拿。

```js
const counter = {
  n: 0,
  inc() {
    setTimeout(function () { this.n++ }, 0)      // this 是 window/undefined，错
    setTimeout(() => { this.n++ }, 0)            // this 是 counter，对
  },
}
```

所以回调里用箭头函数几乎总是更安全。需要动态 `this`（如事件处理器里想拿 `this` 指向元素）时才用普通函数。

### 解构与展开

```js
const { name, age = 18 } = user
const [first, ...rest] = list
const next = { ...user, name: 'B' } // 新对象，不改原对象
```

前端框架里"更新状态要产生新对象"的写法（不可变更新）全靠展开语法：

```js
setUser({ ...user, name: 'B' })
```

直接 `user.name = 'B'` 在很多框架里不会触发重新渲染，因为对象引用没变。

### 数组方法：map / filter / find / reduce

```js
const active = users.filter((u) => u.active)
const names = active.map((u) => u.name)
const total = orders.reduce((sum, o) => sum + o.amount, 0)
const hit = users.find((u) => u.id === 42)
```

这四个覆盖了大部分数据处理。注意 `map` 返回新数组、`forEach` 返回 `undefined`——想把结果拿去渲染，用 `map`。

## 常见误区

### 误区：接口返回空数组就是没数据，`if (data)` 能判断

**你以为**：空数组是假值。
**其实**：`[]` 与 `{}` 都是真值，`if (data)` 对空数组永远成立。要判断"有没有内容"必须看 `length` 或 `Object.keys(x).length`。
**怎么验证**：控制台里 `Boolean([])`、`Boolean({})` 都是 `true`。

### 误区：对象赋值是复制一份

**你以为**：`const copy = original` 之后改 `copy` 不影响 `original`。
**其实**：赋值复制的是引用，两个名字指向同一个对象。要真正复制得展开或 `structuredClone`。
**怎么验证**：`const a = {n:1}; const b = a; b.n = 2; console.log(a.n)` 输出 2。

### 误区：`this` 指向"定义它的对象"

**你以为**：函数写在哪个对象里，`this` 就是谁。
**其实**：普通函数的 `this` 由**调用方式**决定（`obj.fn()` 才是 `obj`，单独调用 `fn()` 在严格模式下是 `undefined`）。箭头函数则根本没有自己的 `this`。
**怎么验证**：把方法取出来单独调用 `const f = obj.inc; f()`，看 `this` 变成什么。

## 带走一句

**值复制、对象共享、`this` 看调用**——JavaScript 里大部分"莫名奇妙"的行为，都能从这三条推出来。

```quiz
{"id":"fe-06-quiz","title":"值与函数","questions":[
 {"question":"`const a = {n:1}; const b = a; b.n = 2` 之后 `a.n` 是？","options":["1","2","undefined","报错"],"answer":1,"explain":"对象赋值复制的是引用，a 与 b 指向同一个对象；const 只禁止重新赋值变量本身。"},
 {"question":"接口返回 `[]` 时，`if (data)` 的结果是？","options":["假，不执行","真，因为空数组也是真值","报错","取决于严格模式"],"answer":1,"explain":"假值只有 false/0/''/null/undefined/NaN 等；判空要看 data.length 或 Object.keys。"},
 {"question":"回调函数里想拿到外层对象的 `this`，最直接的写法是？","options":["function 回调","箭头函数","bind(null)","把 this 存成字符串"],"answer":1,"explain":"箭头函数没有自己的 this，直接沿用外层；普通函数的 this 由调用方式决定。"}
]}
```
