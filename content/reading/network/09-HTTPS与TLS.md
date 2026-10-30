---
title: HTTPS 与 TLS：加密到底发生在哪一层
category: network-basics
order: 9
minutes: 26
level: intermediate
status: published
---

## 先把结论摆出来

**TLS 在 TCP 之上、HTTP 之下。它加密的不是"整个网络"，而是"两个端点之间的这段字节流"。**

```sketch
{
  "title": "TLS 夹在 TCP 和 HTTP 之间",
  "rows": [
    [
      { "id": "http", "title": "HTTP", "sub": "它自己不知道被加密", "tone": "gray" },
      { "id": "tls", "title": "TLS", "sub": "握手、加密、校验", "tone": "blue" },
      { "id": "tcp", "title": "TCP", "sub": "只管把字节送到", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "http", "to": "tls" },
    { "from": "tls", "to": "tcp" }
  ]
}
```

- 你敲 `https://`，浏览器先完成 DNS 和 TCP 握手，再在 TCP 连接里做一次 TLS 握手，然后才发 HTTP 报文。
- 从 TLS 握手完成那一刻起，HTTP 的请求行、首部、正文、响应全都装在加密记录里。中间的 Wi-Fi、路由器、运营商只能看到"哪个 IP、哪个端口、大概多少流量"。
- 但**域名不一定藏得住**：TLS 1.2 的 ClientHello 里明文带着 SNI（服务器名指示），中间设备靠它知道你要访问哪个站点。TLS 1.3 默认仍加密不了 SNI，ECH（Encrypted Client Hello）还在推广中。

所以 HTTPS 解决的是三件事：**机密性**（别人看不懂）、**完整性**（改不了）、**身份认证**（连的是不是真站点）。它不解决"这个网站是不是骗子"——一个钓鱼站也能申请到合法证书。

## HTTPS 到底加了什么

HTTP 是明文文本，中间任何一跳都能读、能改。HTTPS 就是 HTTP 外面套一层 TLS：

```text
HTTP 报文
   ↓ 交给 TLS 加密
TLS 记录（加密后的字节）
   ↓ 交给 TCP
TCP 段
   ↓ 交给 IP
IP 包
```

用 `curl` 把时间账打出来，能看到 TLS 是独立的一段：

```text
$ curl -w 'dns=%{time_namelookup} tcp=%{time_connect} tls=%{time_appconnect} first=%{time_starttransfer} total=%{time_total}\n' -o /dev/null -s https://example.com/
dns=0.012 tcp=0.031 tls=0.089 first=0.142 total=0.145
```

`time_appconnect` 就是 TLS 握手完成的时刻。它减 `time_connect`，就是 TLS 握手花的时间。HTTP/3 把传输握手和 TLS 1.3 合并了，这段账要换个方式算。

抓到包时，TLS 握手之后的包在 `tcpdump` 里只剩"Application Data"，看不到 `GET /`。这不是没发请求，是内容被加密了。要看明文，只能在客户端或服务端导出会话密钥（浏览器支持 `SSLKEYLOGFILE`），或者直接在服务端看日志。

## 对称与非对称：各干各的活

TLS 同时用了两种加密，因为它们各有一个致命短板：

| 类型 | 代表算法 | 优点 | 缺点 | 在 TLS 里干什么 |
| --- | --- | --- | --- | --- |
| 对称 | AES-GCM、ChaCha20-Poly1305 | 快，适合大量数据 | 双方要先有同一把密钥 | 加密真正的 HTTP 数据 |
| 非对称 | RSA、ECDSA、ECDHE | 能在不安全的通道上协商密钥、做签名 | 慢，RSA 还有长度限制 | 身份认证、密钥交换 |

**不是"用非对称加密传数据"，而是"用非对称手段协商出一把对称密钥，再用它加密数据"。** 原因很实在：非对称运算比对称慢几个数量级，用它加密几 MB 的页面不现实。

密钥交换常用 ECDHE（临时椭圆曲线 Diffie-Hellman）。它的关键性质是**前向保密**：每次连接生成一对临时密钥，用完就丢。即使攻击者以后拿到了服务器的私钥，也解不开之前录下的流量。

老 TLS 允许 RSA 密钥交换：客户端用服务器证书里的公钥加密预主密钥。这种模式没有前向保密，私钥一旦泄露，历史流量全完。**TLS 1.3 直接删掉了 RSA 密钥交换**，只保留（EC）DHE 一类。

证书里的公钥负责"证明你是你"，ECDHE 负责"商量出会话密钥"，这是两件事，别混。

对称加密内部也有性能差异：AES-GCM 在带 AES-NI 的 CPU 上几乎免费，ChaCha20-Poly1305 在没有硬件加速的移动设备上更快。所以服务端的套件顺序不是"越新越好"，要看客户端构成。另外 TLS 记录最大 16 KB，每条记录都带首部与认证标签，频繁的小记录会额外浪费带宽和处理时间——和系统调用的小 IO 问题同源。

## 证书链：谁给谁签字

证书不是"网站自己声明自己可信"，而是一条签名链：

```text
根证书（Root CA）
   └─ 签名 → 中间证书（Intermediate CA）
                └─ 签名 → 服务器证书（Leaf，example.com）
```

- **根证书**预装在操作系统或浏览器的信任库里。你不直接信任任何网站，你信任的是这几十个根。
- **中间证书**由根签，作用是把根保护起来——根私钥离线保存，日常签发用中间证书。
- **服务器证书**由中间签，里面写着域名（SAN）、公钥、有效期、用途。

验证时，客户端从服务器证书往上追：谁能签它？一直追到一个自己信任的根。任何一环断了，验证就失败。

服务器**应该发送"服务器证书 + 中间证书"，不要发根证书**。根证书客户端已经有，发了是浪费字节；漏了中间证书，某些客户端能靠缓存或 AIA 补上，很多客户端不能，直接报"无法获取本地颁发者证书"。

证书里几个字段值得认识：

| 字段 | 含义 | 常见问题 |
| --- | --- | --- |
| SAN（Subject Alternative Name） | 这张证书对哪些域名有效 | 现代浏览器只看 SAN，不看 CN |
| 有效期 | `notBefore` 到 `notAfter` | 过期是最常见的线上事故 |
| Key Usage / EKU | 这张证书能干什么 | 用于服务器认证要有 `serverAuth` |
| 签名算法 | 谁用什么算法签的 | 老 SHA-1 已被淘汰 |

通配符证书 `*.example.com` 只匹配一级：能匹配 `api.example.com`，不能匹配 `a.b.example.com`，也不匹配裸域 `example.com`（除非 SAN 里另写）。

## TLS 1.2 与 1.3 握手差异

两者的目标一样：认证服务器、协商密钥、开始加密。差别在往返次数和信息暴露程度。

| 对比项 | TLS 1.2 | TLS 1.3 |
| --- | --- | --- |
| 完整握手往返 | 2-RTT | 1-RTT |
| 密钥交换 | RSA 或 ECDHE | 只能（EC）DHE |
| 加密套件 | 包含密钥交换、认证、加密、MAC | 只描述 AEAD 加密与哈希，密钥交换与签名分开协商 |
| 证书何时加密 | 明文传输 | ServerHello 之后全部加密 |
| 会话恢复 | Session ID / Session Ticket | PSK 票据，可 0-RTT |
| 0-RTT 风险 | 无此机制 | 有重放风险，只能用于幂等请求 |
| 降级保护 | 较弱 | ServerHello 随机数里带降级标记 |

TLS 1.2 的完整握手大致是：

```text
客户端 → ClientHello（随机数、套件列表、SNI）
服务端 → ServerHello（随机数、选定套件）
        Certificate（证书链）
        ServerKeyExchange（ECDHE 参数 + 签名）
        ServerHelloDone
客户端 → ClientKeyExchange（自己的 ECDHE 参数）
        ChangeCipherSpec
        Finished（加密）
服务端 → ChangeCipherSpec
        Finished（加密）
```

两个往返，证书是明文。TLS 1.3 把客户端密钥份额直接放进第一个 ClientHello（猜一个组），服务端如果同意，立刻就能算共享密钥：

```text
客户端 → ClientHello + key_share
服务端 → ServerHello + key_share
        EncryptedExtensions
        Certificate
        CertificateVerify
        Finished          ← 从这里开始全加密
客户端 → Finished
```

一个往返就完成。省下的那一个往返，在高延迟链路上就是实打实的几百毫秒。

TLS 1.3 还删掉了一堆历史包袱：CBC 模式、RC4、压缩、重协商、静态 RSA 密钥交换。套件从几十个降到五个左右，配置错了导致"没有共同套件"的概率反而降低。

## SNI：一台服务器怎么接待多个域名

一个 IP 上可能放着几十个 HTTPS 站点。TLS 握手发生在 HTTP 之前，服务器还不知道你要访问哪个域名，怎么选证书？

答案是 **SNI**：客户端在 ClientHello 里明文写上目标域名。服务器按名字选证书，再继续握手。

```text
ClientHello
  server_name: api.example.com
  supported_versions: TLS 1.3, TLS 1.2
  ...
```

这带来三个实际影响：

1. **SNI 是明文**。中间设备能看到你访问哪个域名，即使内容是加密的。想藏域名要靠 ECH，目前支持面还有限。
2. **用 IP 直连会拿错证书**。`curl https://1.2.3.4/` 默认不发 SNI，服务器返回默认站点的证书，于是域名不匹配。排查时要用 `--resolve` 或 `openssl s_client -servername`。
3. **SNI 可以用于分流和封锁**。反向代理按 SNI 选后端，防火墙也能按 SNI 丢连接。

用 `openssl` 指定 SNI：

```bash
openssl s_client -connect 203.0.113.10:443 -servername api.example.com
```

不加 `-servername`，你测的可能根本不是你想测的那个站点。这是"本地测正常、线上报证书错误"的常见原因之一。

## 会话复用：第二次为什么快

完整握手要算密钥、验证书、签名，开销不小。短连接场景下，每次请求都重来一遍很浪费。TLS 提供两种复用：

| 机制 | 原理 | 特点 |
| --- | --- | --- |
| Session ID（TLS 1.2） | 服务端缓存会话状态，客户端带 ID 来恢复 | 占用服务端内存，多机要共享 |
| Session Ticket（TLS 1.2/1.3） | 服务端把状态加密成票据交给客户端 | 无状态，多机共享密钥即可 |
| PSK（TLS 1.3） | 恢复时直接用预共享密钥 | 支持 0-RTT，但有重放风险 |

恢复握手的收益很明显：省掉证书验证和密钥协商，TLS 1.3 的 PSK 恢复可以做到 0-RTT——客户端第一个包就带加密的应用数据。代价是 **0-RTT 数据可能被重放**：攻击者录下这个包，再发一次，服务端可能重复执行。所以 0-RTT 只该用于 `GET` 这类幂等请求，支付、下单绝不能走。

Nginx 的配置长这样：

```nginx
ssl_session_cache shared:SSL:10m;
ssl_session_timeout 1h;
ssl_session_tickets on;
```

`curl` 每次都是新进程，天然不复用会话。所以用 `curl` 测"第二次是不是更快"，测不出来，得在同一个进程里发多次请求，或者看服务端日志里的 `reused` 标记。

## 中间人代理与自签证书

TLS 的信任模型有一个"后门"：**谁控制了客户端的信任库，谁就能当中间人。** 企业网络里的 HTTPS 代理就是这么工作的：

```text
浏览器 →（TLS 连接 A）→ 公司代理 →（TLS 连接 B）→ 目标网站
```

代理对浏览器出示一张自己签的证书（由公司根 CA 签发），对网站则作为普通客户端。只要公司根 CA 被装进浏览器的信任库，浏览器就认为一切正常。代理能看到全部明文——这既是审计手段，也是风险。

个人开发时常见的自签证书是同一回事，只是根 CA 没被信任：

```bash
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 365 \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

浏览器打开会报 `ERR_CERT_AUTHORITY_INVALID`。正确的处理是把这张证书（或它的 CA）加进信任库，而不是点"继续访问"。

各语言信任库不同，这是"命令行能访问、代码里报错"的根源：

| 环境 | 信任库 | 覆盖方式 |
| --- | --- | --- |
| curl / OpenSSL | 系统 CA 目录 | `--cacert`、`SSL_CERT_FILE` |
| Node.js | 内置 CA + 系统 | `NODE_EXTRA_CA_CERTS` |
| Python requests | certifi 包 | `REQUESTS_CA_BUNDLE`、`verify=` |
| Java | JDK 的 cacerts | `-Djavax.net.ssl.trustStore` |
| Go | 系统 CA | `SSL_CERT_FILE`（部分场景） |

`curl -k`、`verify=False` 能绕过验证，但等于放弃了身份认证：中间人可以随意替换内容，而你毫无察觉。**只在一次性调试里用，不要写进代码。**

比"信任哪个 CA"更强的是**证书固定**（pinning）：客户端只认某张证书或某个公钥。移动 App 常用，能挡住企业代理和部分中间人攻击；代价是换证书时要发版，轮换策略必须提前设计。

## 常见报错对照表

| 报错文本 | 含义 | 先查什么 |
| --- | --- | --- |
| `certificate has expired` | 证书过期 | `openssl x509 -dates`，服务器时间 |
| `hostname mismatch` / `no alternative certificate subject name` | 域名不在 SAN 里 | 证书 SAN、SNI 是否带对 |
| `unable to get local issuer certificate` | 链不全，缺中间证书 | 服务端是否发全链 |
| `self-signed certificate in certificate chain` | 链里有自签证书 | 是否用了自签或代理证书 |
| `unable to verify the first certificate`（Node） | 同上，缺中间证书 | `-showcerts` 看发了几张 |
| `certificate signed by unknown authority`（Go） | 根不在信任库 | 客户端 CA 配置 |
| `sslv3 alert handshake failure` | 没有共同协议或套件 | 协议版本、套件、SNI |
| `wrong version number` | 把 HTTPS 当 HTTP 连，或反了 | 端口与协议是否匹配 |
| `tlsv1 alert protocol version` | 对方不支持你选的版本 | 是否禁用了老协议 |

注意"过期"和"域名不匹配"是两类完全不同的错误，只是浏览器都显示成"您的连接不是私密连接"。看具体错误码比看中文提示有用。

## 用 openssl s_client 排查

这是 TLS 排查的主力工具。最基本的用法：

```bash
openssl s_client -connect example.com:443 -servername example.com
```

关注三处输出：

- `subject=` 和 `issuer=`：证书是谁的、谁签的；
- `Verification:` 与最后的 `Verify return code:`；
- `Protocol:` 与 `Cipher:`：实际协商出的版本与套件。

只看关键信息，用 `-brief`：

```text
$ openssl s_client -connect example.com:443 -servername example.com -brief
CONNECTION ESTABLISHED
Protocol version: TLSv1.3
Cipher: TLS_AES_256_GCM_SHA384
...
Verification: OK
```

看服务端到底发了几张证书：

```bash
openssl s_client -connect example.com:443 -servername example.com -showcerts </dev/null
```

每个 `-----BEGIN CERTIFICATE-----` 是一张。正常应该看到 leaf 加至少一张中间证书；只有一张，基本就是链不全。

把 leaf 的字段打出来：

```bash
openssl s_client -connect example.com:443 -servername example.com 2>/dev/null \
  | openssl x509 -noout -subject -issuer -dates -ext subjectAltName
```

输出：

```text
subject=CN = example.com
issuer=C = US, O = Let's Encrypt, CN = R3
notBefore=Sep 20 10:00:00 2026 GMT
notAfter=Dec 19 09:59:59 2026 GMT
X509v3 Subject Alternative Name:
    DNS:example.com, DNS:www.example.com
```

手工验证一条链：

```bash
openssl verify -CAfile root.pem -untrusted intermediate.pem leaf.pem
```

`-untrusted` 提供中间证书，`-CAfile` 提供信任根。想验证系统信任库，去掉 `-CAfile`，用默认路径。

指定协议版本，确认服务端支持范围：

```bash
openssl s_client -connect example.com:443 -servername example.com -tls1_3 -brief
openssl s_client -connect example.com:443 -servername example.com -tls1_2 -brief
```

一条命令看完整的握手过程（每一步的方向和类型）：

```bash
openssl s_client -connect example.com:443 -servername example.com -state
```

看 ALPN 协商结果（h2 还是 http/1.1）：

```bash
openssl s_client -connect example.com:443 -servername example.com -alpn h2,http/1.1
```

最后提醒：`s_client` 默认不因为验证失败而退出非零，所以脚本里要加 `-verify_return_error`，否则"验证失败"会被当成成功。

## 一个具体例子：浏览器报错，命令行却"正常"

用户反馈某个内部系统打不开，浏览器报"您的连接不是私密连接"。先用 `curl -v` 看：

```text
$ curl -v https://internal.example.com/ 2>&1 | tail -5
* SSL certificate problem: unable to get local issuer certificate
curl: (60) SSL certificate problem: unable to get local issuer certificate
```

再让 `openssl` 把服务端发的证书全打出来：

```text
$ openssl s_client -connect internal.example.com:443 -servername internal.example.com -showcerts </dev/null 2>/dev/null | grep -c 'BEGIN CERTIFICATE'
1
```

只有一张。正常应该是两张以上（leaf + 中间证书）。看它的 issuer：

```text
$ openssl s_client -connect internal.example.com:443 -servername internal.example.com 2>/dev/null \
  | openssl x509 -noout -issuer -subject
issuer=C = US, O = Example Corp, CN = Example Internal CA
subject=CN = internal.example.com
```

签发者是内部 CA，但服务端只发了 leaf，没发中间证书。某些客户端（比如装了内部 CA 的 Windows）能从本地缓存补上，所以"有人能打开"；浏览器与命令行补不上，就报错。

修复很简单：把 fullchain（leaf + 中间证书）配到服务端。

```nginx
ssl_certificate     /etc/nginx/certs/internal.example.com.fullchain.pem;
ssl_certificate_key /etc/nginx/certs/internal.example.com.key;
```

改完再验：

```text
$ openssl s_client -connect internal.example.com:443 -servername internal.example.com -showcerts </dev/null 2>/dev/null | grep -c 'BEGIN CERTIFICATE'
2
$ curl -sS -o /dev/null -w '%{http_code}\n' https://internal.example.com/
200
```

这个例子说明两件事：**"命令行能通"不代表证书没问题**，不同客户端补链能力不同；**排查顺序是先看发了几张证书，再看信任库**。反过来查，容易在客户端配置上绕很久。

## 检查一下

```quiz
{
  "id": "net-09-quiz",
  "title": "HTTPS 三问",
  "questions": [
    {
      "question": "TLS 加密发生在哪一层？",
      "options": ["IP 层，加密整个包", "TCP 之上、HTTP 之下，加密两个端点之间的字节流", "只在 Wi-Fi 层", "DNS 层"],
      "answer": 1,
      "explain": "TLS 是传输层之上的会话加密，HTTP 报文整体作为载荷被加密；中间设备仍能看到 IP、端口和 SNI。"
    },
    {
      "question": "服务器只发了一张叶子证书，最可能导致什么错误？",
      "options": ["证书过期", "unable to get local issuer certificate，链不完整", "域名不匹配", "密码错误"],
      "answer": 1,
      "explain": "客户端需要中间证书才能把 leaf 追到受信任的根；缺中间证书就是链不全。"
    },
    {
      "question": "TLS 1.3 的 0-RTT 为什么不能用于下单请求？",
      "options": ["因为太慢", "因为 0-RTT 数据可能被重放，服务端可能重复执行", "因为不加密", "因为浏览器不支持"],
      "answer": 1,
      "explain": "0-RTT 用预共享密钥直接发数据，缺少防重放保证；只适合幂等请求。"
    }
  ]
}
```

## 常见误区

### 误区：HTTPS 把访问的域名也藏起来了

**你以为**：用了 HTTPS，别人就不知道你访问了哪个网站。
**其实**：DNS 查询、目标 IP、以及 ClientHello 里的 SNI 都是可见的。TLS 1.3 加密了证书等握手内容，但 SNI 默认仍是明文，ECH 尚未全面可用。
**怎么验证**：在客户端抓包，看 ClientHello 里的 `server_name`；或看网络设备上的 SNI 日志。

### 误区：证书是权威机构签的，网站就一定安全

**你以为**：地址栏有小锁，说明这个站可信。
**其实**：证书只证明"你连的域名确实由这个主体控制"，不证明主体是好人。钓鱼站也能拿到免费的 DV 证书。小锁只代表传输加密与身份匹配。
**怎么验证**：点开证书看颁发给谁、什么类型（DV/OV/EV），再对照域名拼写与业务来源判断。

### 误区：自签证书只是"浏览器不认"，加个 `-k` 就行

**你以为**：跳过验证只是少点一次警告，没什么影响。
**其实**：`-k` 或 `verify=False` 关掉的是身份认证，中间人可以任意解密和篡改，你完全看不出来。正确做法是把自签 CA 装进信任库，或在内网使用受信任的私有 CA。
**怎么验证**：用 mitmproxy 配合自签证书做一次中间人，观察客户端在 `-k` 下是否会接受被替换的响应。

### 误区：TLS 1.3 一定比 TLS 1.2 快

**你以为**：新版本握手更少，所以永远更快。
**其实**：首次连接少一个往返，但 TLS 1.3 的密钥交换与签名算法更重（尤其纯软件环境），CPU 可能更高；会话复用的收益也取决于服务端票据配置。短连接高延迟场景收益明显，同机高并发场景未必。
**怎么验证**：用 `openssl s_time` 或 `curl -w '%{time_appconnect}'` 在目标网络分别测 1.2 与 1.3 的首次与复用握手，别只看协议版本。

## 小结

HTTPS 是 HTTP 套在 TLS 里。TLS 用非对称手段完成身份认证与密钥协商，再用对称密钥加密真正的数据。证书的可信来自一条从根到叶子的签名链，服务端要发全链但不必发根。TLS 1.3 把完整握手从 2-RTT 降到 1-RTT，删掉了不安全的旧算法，也带来 0-RTT 的重放取舍。SNI 让一个 IP 承载多个站点，同时暴露了域名。排查 TLS 问题的顺序是：看协议与套件、看发了几张证书、看链能不能追到根、最后才看客户端信任库。`openssl s_client` 加 `-showcerts`、`-servername`、`-brief` 三件套，能覆盖大多数线上故障。记住排查顺序：链、域名、有效期、信任库。

下一篇往后退一层：请求从客户端到服务端，中间还可能经过正向代理、反向代理、负载均衡和 CDN。它们各自解决什么问题，又会在哪些地方把"真实 IP"和"缓存"搞错。
