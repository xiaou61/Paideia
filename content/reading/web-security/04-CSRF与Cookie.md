---
title: CSRF 与 Cookie
category: web-security
order: 4
chapter: 边界
minutes: 24
level: intermediate
status: published
---

## 这一关在解决什么

CSRF（跨站请求伪造）利用的是一件浏览器默认会做的事：**向某个站点发请求时，自动带上该站点的 Cookie**。

攻击者不需要读到你的响应，只需要让浏览器"替用户发一条请求"——转账、改密码、删数据。这一关讲清 Cookie 的携带规则，以及三种防护分别防住了什么。

## 讲解

### 攻击怎么发生

1. 你登录了 `bank.example`，浏览器存了会话 Cookie；
2. 你访问了 `evil.example`，页面里有一张隐藏表单：

```html
<form action="https://bank.example/transfer" method="POST">
  <input name="to" value="attacker" />
  <input name="amount" value="10000" />
</form>
<script>document.forms[0].submit()</script>
```

3. 表单自动提交，浏览器带上 `bank.example` 的 Cookie；
4. 服务端看到的是"已登录用户发起的合法请求"。

关键点：**攻击者没有拿到 Cookie，也没有读到响应**，只是借用了浏览器的自动携带。

### Cookie 的三个属性

```text
Set-Cookie: session=abc; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=3600
```

- `HttpOnly`：JS 读不到，降低 XSS 窃取会话的风险；
- `Secure`：只通过 HTTPS 发送；
- `SameSite`：**跨站请求是否携带这个 Cookie**。

`SameSite` 的取值：

| 值 | 行为 |
| --- | --- |
| `Strict` | 任何跨站请求都不带（从外站点链接进来也不带，可能像"未登录"） |
| `Lax`（现代浏览器默认） | 顶层导航的 GET 带；跨站 POST、iframe、fetch 不带 |
| `None` | 都带，但必须同时 `Secure` |

`Lax` 挡住了大部分 CSRF（跨站 POST 不带 Cookie），但**不挡跨站的顶层 GET 导航**——所以"用 GET 做写操作"仍然危险。

### 三种防护，各防什么

**一、SameSite（浏览器层）**

成本最低，默认就该开。缺点是老浏览器不支持、`None` 场景（跨站嵌入）必须关掉它。

**二、CSRF Token（应用层）**

服务端下发一个随机 token，写操作必须带上；攻击者的页面读不到 token（受同源策略限制），也就伪造不了。

```text
GET  /form        → Set-Cookie: csrf=...（或放在页面 meta）
POST /transfer    → 请求头 X-CSRF-Token: ...  服务端比对
```

同步器模式（token 存在服务端会话）与双提交模式（token 同时放 Cookie 与请求头，服务端只比对两者）都常见。双提交不依赖会话存储，但要求攻击者无法设置自定义头。

**三、Origin / Referer 校验（应用层）**

写操作校验 `Origin` 是否是本站。它简单有效，但依赖浏览器发送该头；对非浏览器客户端（API、脚本）要单独设计。

### CORS 不是 CSRF 防护

CORS 控制的是"**A 站的前端 JS 能不能读 B 站的响应**"，它不阻止请求被发出。

```text
跨站 POST 默认会发出（简单请求），只是响应读不到。
```

所以"没配 CORS"不代表不会被 CSRF；反过来，配了宽松 CORS（`Access-Control-Allow-Origin: *` + 允许凭据）反而会扩大风险。

### 写操作不要用 GET

```html
<img src="https://bank.example/delete?id=42" />
```

一张图片就能触发 GET。**GET 必须无副作用**，所有写操作走 POST/PUT/DELETE，并配合 SameSite 与 token。

### 登录 CSRF 与 JSON 接口

- **登录 CSRF**：攻击者诱导你登录他的账号，之后你的操作都记在他名下。登录接口同样要防；
- **纯 JSON 接口**：跨站表单发不出 `Content-Type: application/json`（会触发预检），这层"天然屏障"让很多 API 免于 CSRF；但不要把它当唯一防线——预检配置错误、或接口也接受表单编码时，屏障就没了。

## 常见误区

### 误区：用了 JWT 就不需要 CSRF 防护

**你以为**：Token 放在请求头，浏览器不会自动带。
**其实**：如果 JWT 存在 Cookie 里，浏览器照样自动带，CSRF 依旧成立；只有存在内存/localStorage 并由 JS 手动加头时，才天然免疫（但会暴露给 XSS）。
**怎么验证**：看令牌存在哪、怎么进请求——这是判断 CSRF 风险的唯一依据。

### 误区：SameSite=Lax 就万无一失

**你以为**：浏览器默认 Lax，不用管了。
**其实**：Lax 仍允许顶层 GET 携带 Cookie；如果你的写操作能用 GET 触发，照样中招。
**怎么验证**：把所有能改变状态的接口列出来，确认没有 GET。

### 误区：校验 Referer 太麻烦，可以省

**你以为**：有 token 就够了。
**其实**：token 在跨站嵌入、旧客户端、多标签页场景下容易漏配；Origin 校验是一层便宜的后备。
**怎么验证**：写操作同时检查 token 与 Origin，观察线上是否有正常请求缺少 Origin（再决定是否放宽）。

## 带走一句

**写操作不用 GET、Cookie 开 SameSite、关键操作加 token**——CSRF 的防护是三层叠加，不是一道开关。

```quiz
{"id":"sec-04-quiz","title":"CSRF 与 Cookie","questions":[
 {"question":"CSRF 攻击者最关键的依赖是？","options":["偷到用户密码","浏览器自动携带目标站点的 Cookie","知道数据库结构","能读响应内容"],"answer":1,"explain":"攻击者不读响应，只借浏览器自动带 Cookie 的机制发出写请求。"},
 {"question":"`SameSite=Lax` 下仍然可能发生的 CSRF 场景是？","options":["跨站 POST 表单","跨站 fetch","顶层导航的 GET 写操作","iframe 里的 POST"],"answer":2,"explain":"Lax 允许顶层 GET 导航携带 Cookie；写操作必须不用 GET。"},
 {"question":"CORS 与 CSRF 的关系是？","options":["CORS 防 CSRF","CORS 只限制跨站读取响应，不能阻止请求被发出","两者无关","开了 CORS 就一定安全"],"answer":1,"explain":"简单请求默认会发出，只是响应读不到；CSRF 防护要靠 SameSite、token 与 Origin 校验。"}
]}
```
