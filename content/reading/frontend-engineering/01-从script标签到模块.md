---
title: 从 script 标签到模块
category: frontend-engineering
order: 1
chapter: 管起来
minutes: 24
level: beginner
status: published
---

## 这一关在解决什么

十几年前的页面是这样组织的：

```html
<script src="/js/jquery.js"></script>
<script src="/js/util.js"></script>
<script src="/js/cart.js"></script>
<script src="/js/main.js"></script>
```

它当年能跑，今天不行了。不是因为语法过期，而是因为**这四个文件的正确性依赖于它们的加载顺序，而这个顺序没有任何机制在保证**：

- `util.js` 用了 `$`，所以它必须在 jQuery 之后；
- `cart.js` 用了 `util.js` 里的 `formatPrice`，所以它必须排第三；
- `main.js` 里调用了 `cart.render()`，所以它必须最后。

谁在保证这件事？**只有人和约定**。新来的人加一行 `<script>`，插错位置，页面在某个特定路径下才崩。

这一关要讲清楚的是：模块系统在解决什么问题，以及 `import` 到底做了什么。

## 讲解

### 全局变量时代的三个坑

没有模块时，每个文件都把自己的东西挂在 `window` 上：

```js
// util.js
function formatPrice(n) { return '¥' + n.toFixed(2) }
window.formatPrice = formatPrice
```

三个坑立刻出现：

1. **命名冲突**：两个库都定义了 `format`，后加载的覆盖前面的，而且没有任何提示。
2. **顺序耦合**：文件之间的依赖只存在于"加载顺序"里，不在代码里。你没法从 `cart.js` 看出它依赖谁。
3. **无法按需加载**：页面要 10 个功能，就得引 10 个文件，哪怕用户只用到一个。

### 模块解决的是"依赖关系写进代码里"

```js
// util.js
export function formatPrice(n) {
  return '¥' + n.toFixed(2)
}

// cart.js
import { formatPrice } from './util.js'   // 依赖写在第一行

export function render(items) {
  return items.map((i) => `${i.name} ${formatPrice(i.price)}`).join('\n')
}
```

变化有三个，每一个都很关键：

- **依赖变成代码的一部分**：`cart.js` 里能直接读到它需要 `util.js`。谁依赖谁不再靠记忆，靠 import 语句。
- **作用域是模块私有的**：没导出的变量，外面根本拿不到。命名冲突消失了。
- **加载顺序由依赖图决定**：浏览器（或打包器）先解析依赖，再决定谁先执行。

### ESM 和 CommonJS 不只是语法不同

这两种模块格式今天都在用：`import/export` 是 ESM（ECMAScript Module），`require/module.exports` 是 CommonJS。它们的差别决定了后面很多行为：

| | ESM (`import`) | CommonJS (`require`) |
| --- | --- | --- |
| 解析时机 | **静态解析**，代码运行前就知道依赖谁 | 运行时执行到 `require` 才知道 |
| 导出什么 | 活绑定（引用） | 值的拷贝 |
| 能摇树吗 | 能（见第 09 关） | 基本不能 |
| 顶层 await | 支持 | 不支持 |

"静态解析"是最重要的一条：因为 `import` 必须写在顶层、路径必须是字面量，打包工具**不用运行代码就能画出完整的依赖图**。这就是 tree shaking、代码分割、循环依赖检测的前提。

```js
// 这是合法的 ESM：路径是字面量，静态可见
import { render } from './cart.js'

// 这不是静态可见的依赖，打包器只能放弃分析
const name = './cart.js'
const mod = await import(name)
```

### 浏览器原生支持 ESM，为什么还需要打包

浏览器确实能直接跑 ESM：

```html
<script type="module" src="/js/main.js"></script>
```

但直接用会撞上两个现实问题：

**① 请求数变成瀑布**。`main.js` 引 5 个模块，那 5 个模块又各引几个——每多一层，就多一轮网络往返。一个稍微像样的应用能轻松拆出几百个文件。

**② 第三方包往往不是 ESM**。`node_modules` 里大量包还是 CommonJS，或者引用了 Node 才有的东西（`process`、`fs`、`Buffer`）。浏览器不认识这些。

打包器做的就是这两件事：**把几百个文件合并成少数几个请求**，**把不兼容的模块格式和 Node 内置模块转成浏览器能跑的**。

### 今天一个真实项目的依赖图长什么样

```text
src/main.tsx
├── react            (node_modules，CJS + ESM 双格式)
├── react-dom
└── ./App.tsx
    ├── ./routes/home.tsx
    │   └── ./components/Card.tsx
    └── ./routes/settings.tsx    ← 只在进设置页时才需要
```

注意最后一行：**不是所有依赖都该被塞进第一个包里**。`settings.tsx` 只有用户点进设置才用得上，把它编进首屏包，等于让所有人下载他们可能永远不看的代码。这就是第 09 关"代码分割"要处理的事。

先把依赖关系看清楚，再决定哪些要合并、哪些要留到后面——这是整条线的思路。

## 常见误区

### 误区：`import` 就是"把那个文件的代码复制过来"

**你以为**：`import { x } from './a.js'` 相当于把 `a.js` 的内容粘贴到当前位置。
**其实**：模块只会被执行**一次**，而且是在第一次被导入时。所有导入方拿到的是同一份实例。这也是为什么模块里的顶层变量天然是单例——两个文件 `import` 同一个模块，改的是同一个对象。
**怎么验证**：在 `a.js` 顶层写 `console.log('a 被求值')`，然后在 `b.js` 和 `c.js` 里都 `import` 它，最后在 `main.js` 里同时导入 `b` 和 `c`。控制台只会打印一次。

### 误区：路径不写扩展名也没关系

**你以为**：`import './util'` 能跑，因为打包器会帮我补。
**其实**：打包器确实会补，但**浏览器原生 ESM 不会**。一旦你哪天直接用 `<script type="module">` 跑，或者某个工具按原生规则解析，`import './util'` 会直接 404。写全扩展名是唯一两边都成立的做法（`.js`/`.ts` 视构建链而定）。
**怎么验证**：把构建去掉，用 `python -m http.server` 起个静态服务，直接打开带 `type="module"` 的页面。省掉扩展名的那一行会报 `Failed to load module script`。

### 误区：循环依赖是"设计问题"，实际不会遇到

**你以为**：A 引 B、B 引 A，这么明显的错不会犯。
**其实**：它在真实项目里非常常见，而且往往不报错——只是某个值是 `undefined`。ESM 用"活绑定"处理循环：导入方拿到的是引用，但被导入模块当时还没执行完，所以值是未初始化的。表现就是"这个函数怎么会是 undefined"。
**怎么验证**：`import { a } from './b.js'` 时，如果 `b.js` 也在导入 `a.js`，试着在 `b.js` 顶层打印 `a`——会看到 `undefined`，因为此时 `a.js` 还没执行完。真遇到这种报错，先把共享的部分抽到第三个文件里，而不是靠调整 import 顺序。

## 带走一句

**模块把"谁依赖谁"写进了代码，打包器把"几个请求"降到了能接受的数量**——这两件事加起来，才让一个几十个文件的项目还能被人读懂。

```quiz
{"id":"fe-eng-01-quiz","title":"模块与打包","questions":[
 {"question":"ESM 和 CommonJS 最本质的差别是？","options":["语法写法不同","依赖在编译前静态可见 vs 运行时才知道","ESM 更新更快","CommonJS 不能用于浏览器"],"answer":1,"explain":"import 必须顶层、路径是字面量，所以打包器不运行代码就能画出完整依赖图——摇树和代码分割都建立在这上面。"},
 {"question":"浏览器已经原生支持 ESM，为什么还要打包？","options":["浏览器不支持 import","减少请求瀑布、兼容非 ESM 的第三方包","为了加密代码","为了支持 TypeScript"],"answer":1,"explain":"原生 ESM 每个模块一个请求，层级一多就是瀑布；而 node_modules 里大量包是 CommonJS 或引用了 Node 内置模块，浏览器跑不了。"},
 {"question":"循环依赖时，一方拿到的值是 undefined，原因是？","options":["变量名写错了","对方模块还没执行完，活绑定尚未初始化","浏览器 bug","必须用 require"],"answer":1,"explain":"ESM 导出的是活绑定，但循环时模块执行顺序被打断，导入方在对方求值完成前就拿到了引用，此时值是未初始化的。"}
]}
```
