---
title: XSS：三种类型与三道防线
category: web-security
order: 3
chapter: 边界
minutes: 20
level: intermediate
status: published
---

## `<h1>` 里拼的是告警名

`backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsDiagnosisEmail.java` 这个类手工拼一封运维诊断邮件的 HTML。它第 221 行是这么一句：

```java
sb.append("<h1>").append(htmlEscape(name)).append("</h1>");
```

`name` 是告警名，来自告警平台；同一封邮件里的报告正文来自模型。两个都不是能保证干净的字符串。所以这个类在第 253–270 行手写了一个 `htmlEscape`，把 `& < > " '` 逐字符换掉，一遍过。

我读到它时的判断是：「有这一层，就够了。」

要验就把它抄成 Python，喂同一段 payload，再用标准库的解析器看这段字节被切成什么 token（`[]` 是空的属性表）：

```text
未转义         : [('starttag', 'h1', []), ('starttag', 'img', [('src', 'x'), ('onerror', 'alert(document.cookie)')]), ('endtag', 'h1')]
html_escape : [('starttag', 'h1', []), ('data', '<img src=x onerror=alert(document.cookie)>'), ('endtag', 'h1')]
```

`onerror` 从属性变成了文本。这一步和我猜的一样。

不一样的在下一条命令里。

## 同一份字符串，放进 `<h1>` 和放进 `href="..."` 不是一件事

把同样的值塞进 `<a href="值">`，再切一次：

```text
直接注引号           : value='x&quot; onmouseover=&quot;alert(1)'  ->  [('starttag', 'a', [('href', 'x" onmouseover="alert(1)')]), ('data', '报告'), ('endtag', 'a')]
javascript 协议   : value='javascript:alert(document.cookie)'  ->  [('starttag', 'a', [('href', 'javascript:alert(document.cookie)')]), ('data', '报告'), ('endtag', 'a')]
```

第一行说明引号转义是有效的：`"` 换成了 `&quot;`，解析器把它当成值的一部分，没多出 `onmouseover` 属性。

第二行说明它不是万能的：`javascript:` 一个字符都不在映射表里，转义前转义后完全一样。**转义回答的是「这个字符会不会被当成语法」，不回答「这个值本身是不是危险内容」。**

这个仓库里的真实调用点是 `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsDiagnosisMailer.java:123-129`：`link` 由 `incidentLink(baseUrl)` 拼出，`baseUrl` 来自配置 `notify.getBaseUrl()`。所以这里不是漏洞——配置由运营填，攻击者碰不到。但它正是同一份函数下一次复制粘贴会撞上的地方：函数名叫 `htmlEscape`，谁看都以为它是「安全出口」，于是拿去拼用户可控的 URL。

落地规则：`href`/`src`/`action` 这类属性，转义之外还要校验协议（只放 `http`、`https`、相对路径），或者干脆不把不可信字符串放进 URL 位置。

## 我踩的那一脚：`replace` 链

我第一版没抄那个 `switch`，而是自己写了更短的链：

```text
payload           : <img src=x onerror=alert(document.cookie)>
html_escape()     : &lt;img src=x onerror=alert(document.cookie)&gt;
chain_escape()    : &amp;lt;img src=x onerror=alert(document.cookie)&amp;gt;
chain(chain())    : &amp;amp;lt;img src=x onerror=alert(document.cookie)&amp;amp;gt;
```

第一次替换吐出的 `&` 被第二次替换又换了一遍。token 层面用户看到的是字面 `&lt;img ...&gt;`：屏幕上出现一串转义符，看着「更安全」，实际是内容坏了。

同一条机制在真实代码里的样子是「写库时转义一次，渲染时再转义一次」——两次都是安全写法，加起来是双重转义；而如果某个出口忘了转义，前一次转过的数据也救不了它。所以规则只有一条：**转义只做一次，且只在该上下文的出口做**。

## 三种类型，判断依据是 payload 停在哪一步

| 现象 | payload 停在哪 | 先查哪 |
| --- | --- | --- |
| 一条没人动过的旧记录，打开详情页就出事 | 库里的字段 | 渲染那条记录的模板/组件；修的时候要「清数据 + 改输出」一起做，只改代码库里的旧 payload 还在 |
| 只在带某个参数的 URL 上出现，去掉参数就正常 | URL 参数被服务端写回 HTML | 那个直接把参数吐进页面的路由、错误页、搜索框 |
| 服务端日志里搜不到 payload，`view-source` 里也搜不到完整串 | 前端 JS 运行时把接口字段/`location` 拼进危险 API | 前端代码里搜 `innerHTML`、`outerHTML`、`insertAdjacentHTML`、`document.write`、`eval` |

第三种被漏掉的原因不在技术，在分工：后端日志干净、接口返回的也是 JSON，看起来「后端没问题」。事实是后端确实没问题，问题在这一段 JSON 到了浏览器之后被谁当成了标签。

三种类型的修法不是三套：**payload 停在哪，就堵那条出口的编码**。换类型不换防线。

## 前端：先数出口，再谈防线

这个仓库我 grep 过 `dangerouslySetInnerHTML|v-html|innerHTML`：`frontend/` 下只有 `frontend/packages/ui/src/code-editor/editor.test.tsx:25` 一处 `.innerHTML`，而且是读出来做断言（`expect(...).toContain('<span class="')`），不是往页面写。markdown 全部走 `@paideia/ui` 的 `Markdown`（`frontend/packages/ui/src/markdown/markdown.tsx`）：`streamdown` 基础包实测 +343KB（该文件第 21 行注释），所以 `lazy()` 引入，并在面板打开时 `preloadMarkdown()` 提前拉，不等第一个 token。

「框架默认转义」这句话得说准：它转义的是**插值那一刻的文本节点**，是库的行为、不是 HTML 规范给的保证，换个 API 就不成立。判断一个出口安不安全只看一件事：**这个字符串最后一步由谁决定「它是标签还是文字」**。

- 走 `{value}`、`th:text`、`v-text`：最后一步是渲染器，它当文字。
- 走 `dangerouslySetInnerHTML`、`v-html`、手工拼字符串：最后一步是你，它当标签——这时候「默认转义」已经不在链路里了。

| 防线 | 它解决什么 | 它挡不住什么 |
| --- | --- | --- |
| 按输出上下文编码 | 字符串不会被重新解释成语法 | 值本身危险的情形，例如 `href` 里就是个 `javascript:` URL |
| 白名单净化（DOMPurify、OWASP Java HTML Sanitizer） | 必须放行少量 HTML 时，只留下列出的标签与属性 | 配错的属性/协议白名单；净化库自身的版本漏洞——它得跟着升级，配错等于没配 |
| CSP | 改变「哪些脚本算这个页面的脚本」 | 不执行 JS 也能造成的伤害；也挡不住页面内容被篡改 |

CSP 那条值得展开一句：内联 `<script>` 和内联事件属性（`onclick=`）在规范里本来就不带任何来源，只有 `'unsafe-inline'`（或对事件属性写 `'unsafe-hashes'`）才放行；`script-src 'self'` 没给这个许可，它们就不执行。这是规范行为，不是某个浏览器的实现细节——也正因为如此，注入进去的 `<script>` 与 `onerror=` 才一起失效。

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; object-src 'none';
  base-uri 'self'; form-action 'self'; frame-ancestors 'none'
```

`base-uri`、`form-action`、`frame-ancestors` 为什么单独写：这三条**不吃 `default-src` 的回退**（`report-uri`、`sandbox` 同理），漏写就是没有任何限制，写多少 `default-src` 都管不到它们。

另有一层不在本文展开：`HttpOnly` / `SameSite` 管的是凭据怎么被带走（JS 能不能读到、跨站会不会自动带上），不是注入本身，见 `04-CSRF与Cookie.md`。

## 跑一遍

Python 3.11.5 / Windows，脚本放 `$env:TEMP/xss_esc_probe.py`。上面三段输出都出自它：

```python
# -*- coding: utf-8 -*-
"""复刻 backend/paideia-ops/.../OpsDiagnosisEmail.htmlEscape 的逐字符转义，
在字符串与 token 层面看「转义 / 不转义」分别长什么样。

注意：html.parser 是 Python 标准库的解析器，不是浏览器。
它只说明“这段字节会被切成什么 token”，不说明浏览器真的会不会执行、会不会加载图片。
"""
from html.parser import HTMLParser


def html_escape(value: str) -> str:
    # 与 OpsDiagnosisEmail.htmlEscape (Java) 同一张映射表、同样逐字符一遍过
    out = []
    for c in value:
        if c == '&':
            out.append('&amp;')
        elif c == '<':
            out.append('&lt;')
        elif c == '>':
            out.append('&gt;')
        elif c == '"':
            out.append('&quot;')
        elif c == "'":
            out.append('&#39;')
        else:
            out.append(c)
    return ''.join(out)


def chain_escape(value: str) -> str:
    # 常见的手写顺序：先换尖括号，再换 & —— 链式 replace 的写法
    return value.replace('<', '&lt;').replace('>', '&gt;').replace('&', '&amp;')


class Tokenizer(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.events = []

    def handle_starttag(self, tag, attrs):
        self.events.append(('starttag', tag, attrs))

    def handle_endtag(self, tag):
        self.events.append(('endtag', tag))

    def handle_data(self, data):
        if data.strip():
            self.events.append(('data', data))


def tokens(fragment: str):
    p = Tokenizer()
    p.feed(fragment)
    p.close()
    return p.events


payload = '<img src=x onerror=alert(document.cookie)>'

print('== A. 逐字符一遍 vs 链式 replace ==')
print('payload           :', payload)
print('html_escape()     :', html_escape(payload))
print('chain_escape()    :', chain_escape(payload))
print('chain(chain())    :', chain_escape(chain_escape(payload)))

print()
print('== B. 放进 <h1> 里，token 层面各是什么 ==')
for label, value in (('未转义', payload), ('html_escape', html_escape(payload)),
                     ('chain_escape', chain_escape(payload))):
    frag = f'<h1>{value}</h1>'
    print(f'{label:12}: {tokens(frag)}')

print()
print('== C. 放进 href="..." 里 ==')
for label, raw in (('未转义引号', 'http://h/ok'), ('直接注引号', 'x" onmouseover="alert(1)'),
                   ('javascript 协议', 'javascript:alert(document.cookie)')):
    esc = html_escape(raw)
    frag = f'<a href="{esc}">报告</a>'
    print(f'{label:16}: value={esc!r}  ->  {tokens(frag)}')
```

`convert_charrefs=True` 会把 `&quot;` 变回 `"` 再给你，所以 C 段第二行的属性值是 `x" onmouseover="alert(1)`——它仍然是**一个**属性的值，没有变成两个属性。这正是要观察的差别。

## 动手

1. 数出口。`grep -rnE "dangerouslySetInnerHTML|v-html|innerHTML|outerHTML|insertAdjacentHTML|document\.write" frontend/ backend/`，每一条都标上数据来源：用户输入、库输出、还是配置。
2. 挑你能拼 HTML 的那一处，回答两个问题：这份转义被调用几次？它知道自己在哪个上下文（文本 / 属性 / URL / JS 字符串）里吗？
3. 找一个 URL 属性，把值换成 `javascript:alert(1)`，确认有没有拦。
4. 打开响应头，查 `unsafe-inline`、`unsafe-eval`、`*`，再确认 `form-action`、`frame-ancestors`、`base-uri` 写了没有。

## 检查一下

```quiz
{"id":"sec-03-quiz","title":"XSS","questions":[
 {"question":"把 javascript:alert(1) 这样一段值放进 href=「...」，经过 htmlEscape 之后会怎样？","options":["整个 a 标签被丢弃","转义会把 javascript: 识别为危险协议并置空","原样留在 href 的值里——转义不检查协议","变成一段普通文本，不再是链接"],"answer":2,"explain":"javascript:alert(1) 里没有 &、<、> 和两个引号，五个字符一个都没命中，转义对它是空操作。转义只解决「字符会不会被当成语法」，值危不危险得另外校验协议。"},
 {"question":"先 replace(< 换成 &lt;) 再 replace(& 换成 &amp;)，输入 <b> 得到什么？","options":["&amp;lt;b&amp;gt; —— 第一次换出来的 & 又被换了一遍","&lt;b&gt; —— 链式 replace 与逐字符遍历等价","&amp;lt;b&gt; —— 只有 < 那一侧被二次转义","&amp;amp;lt;b&amp;gt; —— 换一次套一层，实体本身又被套了一层"],"answer":0,"explain":"链式 replace 的每一步都作用在上一步的结果上，所以已经换出来的 & 会被再换一次。写成一次遍历、一次调用，才不会叠。"},
 {"question":"只写 default-src 'self'，下面哪组指令仍然没有限制？","options":["script-src 与 style-src","img-src 与 connect-src","object-src 与 frame-src","frame-ancestors、form-action、base-uri"],"answer":3,"explain":"这三条（加 report-uri、sandbox）不吃 default-src 的回退，必须逐条显式写；漏写就等于没限制，前三条都在回退范围内。"}
]}
```

## 还差的

- 全文的 token 都来自 Python 标准库 `html.parser`，不是浏览器。`onerror` 什么时候真的触发、CSP 是否真的拦住某段内联脚本，得在浏览器里验（Playwright 起一页 + 设响应头是最便宜的验法）——这部分我没跑，本文不对浏览器行为下结论。
- `HtmlRenderer.builder().escapeHtml(true)` 的语义是我读代码加读它自带的单测（`backend/paideia-ops/src/test/java/io/github/xiaou61/ops/OpsDiagnosisEmailTest.java:78` 的 `rawHtmlInReportIsEscaped`）得出的；报告正文里出现 `javascript:` 链接时 commonmark 会怎么渲染，我没有做实验。
- 没跑 DOMPurify / OWASP Java HTML Sanitizer 的实际净化结果，`ALLOWED_TAGS` 之外那一堆配置（属性、协议、`srcset`、注释里的条件注释）都没验。
- 只覆盖了 HTML 文本与属性两个上下文；`<script>` 内的 JS 字符串、CSS、URL 路径参数、邮件客户端（会剥脚本和 CSS）都没有展开。
- 本文没涉及 `srcset`、`style`、`meta refresh`、SVG 内嵌这些注入面，需要各自单独验。
