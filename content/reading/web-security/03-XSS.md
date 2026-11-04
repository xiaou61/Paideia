---
title: XSS：三种类型与三道防线
category: web-security
order: 3
chapter: 边界
minutes: 28
level: intermediate
status: published
---

## 这一关在解决什么

XSS（跨站脚本）是：**攻击者的字符串最终被浏览器当成脚本执行**。一旦成立，攻击者可以在你的页面里做用户能做的一切——读页面内容、发请求、改界面、诱导输入。

这一关先分清三种类型（它们的修法不同），再讲三道防线。

## 讲解

### 三种类型，区别在"脚本从哪来"

| 类型 | 恶意内容存在哪 | 触发方式 |
| --- | --- | --- |
| 存储型 | 数据库（评论、昵称、文件名） | 任何人打开页面就执行 |
| 反射型 | URL 参数，服务端原样吐回 HTML | 诱导受害者点一个链接 |
| DOM 型 | 前端 JS 把不可信数据写进危险 API | 页面自身的 JS 执行 |

存储型最危险（一次注入，所有访客中招）；DOM 型最容易被忽视（后端完全无辜）。

### 代码里长什么样

```js
// DOM 型：URL 里的 name 进了 innerHTML
const name = new URLSearchParams(location.search).get('name')
document.getElementById('hello').innerHTML = `你好，${name}`
```

访问 `?name=<img src=x onerror=alert(document.cookie)>` 就执行了。

```jsx
// React 的逃生口：明确叫 dangerously
<div dangerouslySetInnerHTML={{ __html: comment }} />
```

React 默认会把 `{comment}` 当文本转义，但这条路绕过了转义。只要内容来自用户，就必须先净化。

### 第一道防线：按输出上下文编码

同一个字符串，放进不同位置需要不同处理：

| 位置 | 做法 |
| --- | --- |
| HTML 文本 | `& < > " '` 转义 |
| HTML 属性 | 属性值加引号 + 转义；不要放进事件属性 |
| URL 参数 | `encodeURIComponent` |
| JS 字符串 | 不用内联拼接；用 `JSON.stringify` 或数据属性 |
| CSS | 不让用户控制 |

**不要自己写转义函数**。用模板引擎的默认转义（Thymeleaf、React、Vue 都默认转义），只在必须输出 HTML 时才显式标记，并配净化。

### 第二道防线：净化富文本

需要允许一部分 HTML（加粗、链接、图片）时，用成熟的白名单净化库（DOMPurify、OWASP Java HTML Sanitizer）：

```js
import DOMPurify from 'dompurify'
element.innerHTML = DOMPurify.sanitize(html, { ALLOWED_TAGS: ['b', 'i', 'a', 'p', 'code'] })
```

关键在**白名单**：只允许列出的标签与属性，而不是"过滤 `<script>`"。`onerror`、`javascript:`、`<svg>` 里的脚本有无数种写法。

### 第三道防线：CSP

Content-Security-Policy 告诉浏览器"只允许执行哪些来源的脚本"：

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; object-src 'none'; base-uri 'self'
```

它能显著降低 XSS 的成功率，尤其是"注入了一段内联脚本"的场景。但：

- `unsafe-inline` 会让它几乎失效；
- 内联事件属性（`onclick=`）需要 `unsafe-inline`，应该改成 `addEventListener`；
- CSP 是**纵深防御**，不能替代转义——已经注入的 HTML 仍可能窃取数据。

### HttpOnly 与 SameSite

```text
Set-Cookie: session=...; HttpOnly; Secure; SameSite=Lax
```

`HttpOnly` 让 JS 读不到 Cookie，XSS 就偷不走会话（但仍能以用户身份发请求）。`Secure` 只走 HTTPS，`SameSite` 限制跨站携带。

这三个是**降低损失**，不是防 XSS 本身。

### 一个容易漏的地方：用户生成内容的预览

Markdown 预览、富文本编辑器、文件名展示、错误信息回显、邮件模板——这些都是"用户内容进 HTML"的入口。**每一个都要走同一套转义/净化规则**。

## 常见误区

### 误区：React/Vue 会自动防 XSS

**你以为**：用了框架就不用管。
**其实**：框架只保证"插值默认转义"。`dangerouslySetInnerHTML`、`v-html`、`innerHTML`、`eval`、`new Function` 都是明确的逃逸口。
**怎么验证**：全局搜这些 API，逐个确认数据来源是否可信。

### 误区：转义了引号就安全

**你以为**：把 `<` `>` 换掉就行。
**其实**：属性上下文、URL 上下文、JS 上下文的规则不同；`javascript:` 协议、`onerror` 属性、SVG 都能绕过不完整的过滤。
**怎么验证**：用 OWASP 的 XSS 测试向量过一遍（只在自己环境）。

### 误区：CSP 加上就万事大吉

**你以为**：有 CSP 就不会 XSS。
**其实**：`unsafe-inline`、过宽的 `script-src`、允许 `data:` 或第三方 CDN，都会让 CSP 形同虚设；而且 CSP 不阻止"内容被篡改"（页面被 deface）。
**怎么验证**：看响应头里有没有 `unsafe-inline` / `unsafe-eval` / `*`。

## 带走一句

**插值默认转义、富文本白名单净化、CSP 兜底**——三道防线各管一段，缺一道就有一类场景会漏。

```quiz
{"id":"sec-03-quiz","title":"XSS","questions":[
 {"question":"三种 XSS 里，后端完全没参与的是？","options":["存储型","反射型","DOM 型","都不参与"],"answer":2,"explain":"DOM 型是页面自身的 JS 把不可信数据写进 innerHTML 等危险 API。"},
 {"question":"需要允许用户写少量 HTML（加粗、链接）时，正确做法是？","options":["过滤掉 script 标签","用白名单净化库，只允许列出的标签与属性","把所有 < 换成 &lt;","只允许管理员输入"],"answer":1,"explain":"黑名单过滤会被 onerror、svg、javascript: 等写法绕过；白名单才是可维护的边界。"},
 {"question":"`HttpOnly` Cookie 的作用是？","options":["阻止 XSS 发生","让 JS 读不到 Cookie，降低会话被窃取的损失","加密 Cookie 内容","限制跨站请求"],"answer":1,"explain":"它降低损失，不防注入；XSS 仍能以用户身份发请求。"}
]}
```
