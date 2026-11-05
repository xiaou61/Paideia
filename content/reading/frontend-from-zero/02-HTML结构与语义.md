---
title: HTML：把结构说清楚
category: frontend-from-zero
order: 2
chapter: 看懂
minutes: 24
level: beginner
status: published
---

## 这一关在解决什么

HTML 看起来最简单：不就是标签吗。但前端里大量"能跑但不好用"的页面，根因都在这一层：

- 读屏软件读不出来（用 `div` 当按钮）；
- 回车提交不了表单、浏览器不记密码；
- 点了链接新标签打开，而按钮又打不开；
- 搜索引擎抓不到内容。

这些问题都不影响"看起来对"，所以最容易欠账。这一关的目标是：**用对的元素表达结构**。

打个比方：HTML 元素像给内容贴上"这是什么"的标签，而不是给盒子刷漆。真实机制是浏览器和读屏都按标签名判断角色：`button` 天生能被 Tab 到、被回车触发，`div` 不能。

```sketch
{
  "title": "HTML 五件事：从结构到细节",
  "rows": [
    [
      { "id": "nest", "title": "元素·属性·嵌套", "sub": "嵌套关系就是信息", "tone": "blue" },
      { "id": "semantic", "title": "常用语义元素", "sub": "header/nav/main…", "tone": "blue" },
      { "id": "form", "title": "表单", "sub": "浏览器免费给的", "tone": "green" }
    ],
    [
      { "id": "img", "title": "图片与 alt", "sub": "alt 不是装饰", "tone": "green" },
      { "id": "attr", "title": "属性易错点", "sub": "布尔值与唯一 id", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "nest", "to": "semantic", "label": "用对标签" },
    { "from": "semantic", "to": "form", "label": "可交互" },
    { "from": "form", "to": "img", "label": "补细节" },
    { "from": "img", "to": "attr", "label": "再校对" }
  ]
}
```

## 讲解

### 元素、属性、嵌套

```html
<article class="card" data-id="42">
  <h2>标题</h2>
  <p>正文 <strong>重点</strong></p>
</article>
```

- `article` 是标签名，决定**语义**；
- `class`/`data-id` 是属性，提供**附加信息**（样式钩子、数据）；
- 标签可以嵌套，形成树。**嵌套关系本身就是信息**：标题属于这篇文章、重点属于这句话。

一个常见错误：为了布局把结构拍平。

```html
<!-- 错：结构被 div 拍平，标题不再属于文章 -->
<div class="article">
  <div class="title">标题</div>
  <div class="content">正文</div>
</div>
```

浏览器能显示，但"这是一篇文章"的信息丢了。语义标签不是道德要求。它直接决定辅助技术与搜索引擎能不能理解你的页面。

### 常用的语义元素

| 元素 | 表达 | 什么时候用 |
| --- | --- | --- |
| `header` / `footer` | 页头 / 页脚 | 一个区块的开头与结尾 |
| `nav` | 导航 | 一组主要链接 |
| `main` | 页面主内容 | 一个页面只应有一个 |
| `article` | 独立成篇的内容 | 一篇文章、一条评论 |
| `section` | 有主题的分组 | 通常带标题 |
| `aside` | 旁支内容 | 侧栏、相关推荐 |
| `button` | 可点击的动作 | 提交、打开弹窗 |
| `a` | 跳转 | 去另一个地址 |

判断 `a` 还是 `button` 的标准只有一条：**它改变地址吗**。会跳到另一个 URL 用 `a`；在原地做事（提交、展开、删除）用 `button`。

### 表单：浏览器免费给你的东西

```html
<form>
  <label for="email">邮箱</label>
  <input id="email" name="email" type="email" required autocomplete="email" />
  <button type="submit">订阅</button>
</form>
```

这几行里，浏览器免费提供：

- 点标签聚焦输入框（`for` 关联 `id`）；
- 邮箱格式校验与错误提示（`type="email"` + `required`）；
- 密码管理器能识别字段（`autocomplete`）；
- 回车提交、移动端弹出合适的键盘。

用 `div` 自己拼一个"输入框 + 按钮"，这些全都要自己实现，而且很难做全。**能用原生元素就不要自己造。**

### 图片与替代文本

```html
<img src="chart.png" alt="2024 年各季度营收：Q1 100 万，Q2 150 万，Q3 120 万，Q4 200 万" />
```

`alt` 不是可选的装饰：

- 图片加载失败时它显示在页面上；
- 读屏软件读它；
- 搜索引擎用它理解图片内容。

纯装饰图（分隔线、背景花纹）用 `alt=""` 明确标成"无信息"，而不是随便写一句。

### 属性的两个易错点

1. **布尔属性看"有没有"，不看值**：`disabled="false"` 仍然是禁用，因为属性存在。要启用就别写。
2. **`id` 必须全页唯一**：`label for`、`aria-labelledby`、锚点跳转都依赖它；重复会让关联失效。

## 常见误区

### 误区：`div` + `onclick` 和 `button` 差不多

**你以为**：样式一样、点下去都有反应，没区别。
**其实**：`div` 不会获得键盘焦点、回车/空格不触发、读屏软件不报"按钮"。要补齐得手动加 `tabindex`、`role`、键盘事件，还要处理焦点样式——比直接用 `button` 麻烦得多。
**怎么验证**：只用 Tab 键走一遍页面。所有可点的东西都应该能被 Tab 到、被回车/空格触发；用 `div` 做的按钮会直接跳过。

### 误区：语义标签只是 SEO 用的

**你以为**：不做 SEO 就随便用 `div`。
**其实**：语义首先服务的是**用键盘和读屏的人**，其次才是搜索引擎。`main` 让读屏能一键跳到主内容，`nav` 让导航可以被跳过，`label` 让输入框有名字。这些是可用性，不是优化项。
**怎么验证**：浏览器 DevTools 的 Accessibility 面板看一个元素的 role 与 name。`div` 的 role 是 generic；`button` 是 button，且名字来自文本。

### 误区：`<br>` 和空 `div` 是好用的间距工具

**你以为**：加个 `<br>` 或 `<div style="height:16px">` 就能控制间距。
**其实**：间距是样式问题，属于 CSS 的 margin/gap。用空元素撑间距会让结构里塞满无意义节点，读屏还会念出空内容，改布局时也很难维护。
**怎么验证**：把页面样式全关掉看结构（DevTools 里禁用 CSS）——语义正确的页面仍然能读，靠 `<br>` 排的页面会变成一坨。

## 带走一句

**HTML 负责"这是什么"，CSS 负责"长什么样"，JS 负责"发生什么"**——把三件事混在 `div` 里，后面每一样都会更难。

```quiz
{"id":"fe-02-quiz","title":"结构与语义","questions":[
 {"question":"一个「提交表单」的动作，应该用哪个元素？","options":["a","button","div","span"],"answer":1,"explain":"判断标准是「改不改变地址」：原地做事用 button；a 只用于跳到另一个地址。"},
 {"question":"`<label for=\"email\">` 里的 for 必须对应什么？","options":["input 的 name","input 的 id","form 的 id","button 的 type"],"answer":1,"explain":"for 关联的是 id；它让点标签能聚焦输入框，也让读屏知道这个输入框叫什么。"},
 {"question":"`<input disabled=\"false\">` 的实际效果是？","options":["启用","仍然禁用","报错","忽略该属性"],"answer":1,"explain":"布尔属性看存在与否；要启用就别写 disabled，而不是给它一个字符串值。"}
]}
```
