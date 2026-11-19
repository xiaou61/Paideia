---
title: HTTPS 与 TLS：加密到底发生在哪一层
category: network-basics
order: 9
minutes: 24
level: intermediate
status: published
---

## 先看我踩的坑：curl 说 http/1.1，openssl 说 h2

本机是 Windows 11，测试时间是 2026-11-19。先看客户端：

```text
$ curl.exe -V
curl 8.0.1 (Windows) libcurl/8.0.1 Schannel WinIDN
Release-Date: 2023-03-20
...
Features: AsynchDNS HSTS HTTPS-proxy IDN IPv6 Kerberos Largefile NTLM SPNEGO SSL SSPI threadsafe Unicode UnixSockets
```

注意两个词：`Schannel`，以及 Features 里没有 `HTTP2`。这决定了后面能看到什么。

```text
$ curl.exe -v --trace-time -I https://github.com/
14:03:47.241139 *   Trying 140.82.114.3:443...
14:03:47.462730 * Connected to github.com (140.82.114.3) port 443 (#0)
14:03:47.462808 * schannel: disabled automatic use of client certificate
14:03:47.467412 * ALPN: offers http/1.1
14:03:47.695730 * ALPN: server accepted http/1.1
14:03:47.695766 * using HTTP/1.1
...
< HTTP/1.1 200 OK
```

我原以为这里会出现 `TLSv1.3` 和 `h2`。两个都没有。原因不是 GitHub 只支持 1.1，而是这个 curl 只带 Schannel、没编 HTTP/2；Schannel 的 `-v` 也不打印协商出的 TLS 版本。这份日志只告诉我「ALPN 谈成了 http/1.1」，没告诉我「TLS 是 1.2 还是 1.3」。

换 OpenSSL，对同一个域名、同一个 IP：

```text
$ Write-Output '' | openssl s_client -connect github.com:443 -servername github.com -showcerts -alpn h2,http/1.1
...
depth=2 C=GB, O=Sectigo Limited, CN=Sectigo Public Server Authentication Root E46
depth=1 C=GB, O=Sectigo Limited, CN=Sectigo Public Server Authentication CA DV E36
depth=0 CN=github.com
...
New, TLSv1.3, Cipher is TLS_AES_128_GCM_SHA256
ALPN protocol: h2
Verify return code: 0 (ok)
```

同一张证书，OpenSSL 谈成了 TLS 1.3 + h2。第一件事先记住：**TLS 版本、加密套件、ALPN 都是客户端和服务器协商出来的，不是 `https://` 这个 scheme 写死的。** 你用什么客户端、它编进了什么能力，会直接改变你看到的握手结果。

这次不是命令返回非 0 的失败，是「我以为能看到 X，结果没有」；真正的 `curl: (35)` 在版本那一节。

下文都拿 `github.com` 做同一个目标。证书错误表会用 `badssl.com` 的专用测试域名复现，因为它们本来就是用来造错误的；主线不换目标。

本机 PATH 里没有 `openssl`，我用的是 Git for Windows 自带的 `C:\Program Files\Git\usr\bin\openssl.exe`，`openssl version` 输出 `OpenSSL 3.2.1 30 Jan 2024`。下面命令统一写 `openssl`，PATH 里没有就换成完整路径。PowerShell 里给 `s_client` 喂一个空行用 `Write-Output '' |`；bash 里用 `< /dev/null`。

## 一次请求从 TCP 到首字节

先把一条请求的时间账打出来：

```text
$ curl.exe -sS -I -o NUL -w 'dns=%{time_namelookup} tcp=%{time_connect} tls=%{time_appconnect} first=%{time_starttransfer} total=%{time_total} http=%{http_version}\n' https://github.com/
dns=0.012444 tcp=0.334550 tls=0.646653 first=2.025425 total=2.025528 http=1.1
```

这是单次 HEAD 请求，本机到 GitHub。`time_appconnect` 不是「TLS 花了 0.647 秒」，它是从进程开始到 TLS 握手完成，包含 DNS 和 TCP。要单独看 TLS：

```text
0.646653 - 0.334550 = 0.312103 秒
```

`first - tls` 是 TLS 完成后到第一个响应字节：

```text
2.025425 - 0.646653 = 1.378772 秒
```

这一段是服务器处理、排队、网络回程，不是 TLS 握手。单次样本，别把 1.38 秒当成 GitHub 的固定处理时间。

### DNS：先把名字换成 IP

`time_namelookup` 是 0.012 秒。DNS 把 `github.com` 换成 `140.82.114.3`；TLS 还没开始。域名解析本身不在 TLS 里，走 DoH/DoT 才另说。

失败时会看到：

```text
curl: (6) Could not resolve host: github.com
```

先查 DNS、hosts、代理设置，不要上来就怀疑证书。

### TCP：先有一条可靠字节流

`time_connect` 是 0.335 秒，包含 TCP 三次握手。TLS 在 TCP 建立之后才开始；`https://` 默认走 443 端口。

失败时常见两种：

```text
curl: (7) Failed to connect to github.com port 443: Connection refused
curl: (28) Connection timed out after 20001 milliseconds
```

拒绝说明端口没开，或者中间设备回了 RST；超时说明防火墙、路由或对端没有响应。先按这两类查，此时证书还没参与。

HTTP/3 是另一条路：它走 QUIC，底层是 UDP，TLS 1.3 被嵌进 QUIC，不再有独立的 TCP 三次握手。本文的 `time_connect` / `time_appconnect` 账在 HTTP/3 上不适用。

### TLS：身份、密钥、加密

TCP 通了以后，客户端先发 `ClientHello`，里面至少有几类信息：

- 支持的 TLS 版本（`supported_versions`）；
- 支持的加密套件；
- SNI（要访问的域名）；
- ALPN（想谈 h2 还是 http/1.1）；
- 密钥交换的公开份额（TLS 1.3 的 `key_share`，例如 X25519 或 P-256）。

服务器回 `ServerHello`，选定版本和套件，给出自己的密钥份额；随后出示证书链，用 `CertificateVerify` 证明自己持有证书私钥；最后双方发 `Finished`，握手完成。`time_appconnect` 记的就是这一刻。

这里同时用了两类算法，各干各的活：

- **对称加密**（AES-GCM、ChaCha20-Poly1305）负责批量数据。它快，适合加密整个 HTTP 报文。
- **非对称/密钥协商**（ECDHE）负责在不安全通道上算出同一把会话密钥。每次连接生成临时密钥，用完就丢，所以有前向保密。
- **签名**（RSA、ECDSA）负责身份认证。证书里的公钥验证 `CertificateVerify` 的签名，证明对面持有私钥。

「对称更快，所以只用对称」漏了前提：双方得先有同一把密钥，而这件事不能在不安全通道上直接说。只用非对称加密几 MB 的页面又不划算，而且它也不解决「这把密钥是不是中间人的」。TLS 把三件事拆开：ECDHE 协商密钥、证书签名认证身份、AES-GCM 加密数据。

本机对 GitHub 强制两个版本，能直接看到套件名：

```text
$ openssl s_client -connect github.com:443 -servername github.com -brief -tls1_2
Protocol version: TLSv1.2
Ciphersuite: ECDHE-ECDSA-AES128-GCM-SHA256
...
Server Temp Key: X25519, 253 bits

$ openssl s_client -connect github.com:443 -servername github.com -brief -tls1_3
Protocol version: TLSv1.3
Ciphersuite: TLS_AES_128_GCM_SHA256
...
Server Temp Key: X25519, 253 bits
```

`ECDHE` 是密钥交换，`ECDSA` 是证书签名，`AES128-GCM` 是批量加密。TLS 1.2 把这些写在一个套件名里；TLS 1.3 把密钥交换和签名拿出来单独协商，套件名只描述 AEAD 和哈希。

老 TLS 还允许 RSA 密钥交换：客户端用服务器证书里的 RSA 公钥加密预主密钥。它没有前向保密，私钥泄露后历史流量可解。TLS 1.3 删掉了这条路，只保留（EC）DHE 和 PSK。注意「RSA 证书」不等于「RSA 密钥交换」：服务器可以拿 RSA 证书签名，同时用 ECDHE 协商密钥。

想故意用老协议，会先撞到客户端自己的下限：

```text
$ curl.exe -v --tls-max 1.1 -I https://github.com/
...
* schannel: next InitializeSecurityContext failed: SEC_E_ILLEGAL_MESSAGE (0x80090326) - This error usually occurs when a fatal SSL/TLS alert is received (e.g. handshake failed). More detail may be available in the Windows System event log.
curl: (35) schannel: next InitializeSecurityContext failed: SEC_E_ILLEGAL_MESSAGE (0x80090326) - ...
```

本机 OpenSSL 也一样，直接说没有可用协议：

```text
$ openssl s_client -connect github.com:443 -servername github.com -tls1_1 -brief
...no protocols available...
```

这个失败只能证明**这台机器的客户端栈**不接受 TLS 1.1；它不能证明 GitHub 关了 1.1。要判断服务器下限，得换一个还允许 1.1 的客户端。结论仍然成立：版本是双方各自的下限，任意一端拒绝，握手就失败。

### HTTP：ALPN 决定说哪种话

TLS 握手完成后，HTTP 报文才进来。`curl` 的日志里能看到：

```text
> HEAD / HTTP/1.1
< HTTP/1.1 200 OK
```

如果 ALPN 谈成 `h2`，同样的请求会变成 HTTP/2 的二进制帧；如果是 `h3`，它走 QUIC，不走这条 TCP 连接。TLS 本身不关心 HTTP 版本，ALPN 只是让双方在握手时先对好「接下来用哪种应用层协议」。

ALPN 没有共同项时，不一定在 TLS 层报错：可能退回 HTTP/1.1，也可能被只接受 h2 的服务器拒绝。看到 `no application protocol` 这类错误，先查 ALPN，不要只盯证书。

### 首字节：TLS 之后还有服务端

`time_starttransfer` 是 2.025 秒，TLS 在 0.647 秒就完成了。后面 1.38 秒是请求发出后，服务器处理、排队、回源、网络回程的总和。TLS 成功只说明「加密通道和身份验证过了」，不说明「这个请求一定快」。

如果 TLS 成功但首字节很慢，先看服务端日志、数据库、缓存和回源，不要把锅扣在握手。

## TLS 1.2 与 1.3：差的是一个往返，不是「快一倍」

先把真实消息序列摆出来。下面是本机 `openssl s_client -msg` 对 GitHub 的输出，只留握手记录：

```text
# TLS 1.2
>>> TLS 1.2, Handshake [length 00d0], ClientHello
<<< TLS 1.2, Handshake [length 0041], ServerHello
<<< TLS 1.2, Handshake [length 0ab5], Certificate
<<< TLS 1.2, Handshake [length 0073], ServerKeyExchange
<<< TLS 1.2, Handshake [length 0004], ServerHelloDone
>>> TLS 1.2, Handshake [length 0025], ClientKeyExchange
>>> TLS 1.2, ChangeCipherSpec [length 0001]
>>> TLS 1.2, Handshake [length 0010], Finished
<<< TLS 1.2, ChangeCipherSpec [length 0001]
<<< TLS 1.2, Handshake [length 0010], Finished
```

```text
# TLS 1.3
>>> TLS 1.3, Handshake [length 00f3], ClientHello
<<< TLS 1.3, Handshake [length 007a], ServerHello
<<< TLS 1.3, Handshake [length 000a], EncryptedExtensions
<<< TLS 1.3, Handshake [length 0abc], Certificate
<<< TLS 1.3, Handshake [length 0050], CertificateVerify
<<< TLS 1.3, Handshake [length 0024], Finished
>>> TLS 1.3, ChangeCipherSpec [length 0001]
>>> TLS 1.3, Handshake [length 0024], Finished
```

把两段序列压成一张表：

| 环节 | TLS 1.2 完整握手 | TLS 1.3 完整握手 | 这一步解决什么 |
| --- | --- | --- | --- |
| 客户端第一飞 | ClientHello：版本、套件、SNI、ALPN、随机数 | ClientHello：同样带这些，再加 `supported_versions`、`key_share` | 报能力、报名字、给出密钥份额 |
| 服务端第一飞 | ServerHello、Certificate、ServerKeyExchange、ServerHelloDone | ServerHello、EncryptedExtensions、Certificate、CertificateVerify、Finished，ServerHello 之后全部加密 | 选版本/套件、出示身份、给密钥份额、证明私钥在手 |
| 客户端第二飞 | ClientKeyExchange、ChangeCipherSpec、Finished | Finished，可与首个应用数据同飞 | 完成密钥确认，开始加密 |
| 服务端收尾 | ChangeCipherSpec、Finished | NewSessionTicket 在握手后发 | 确认握手、发会话票据 |
| 完整握手 RTT | 2-RTT | 1-RTT（前提是 `key_share` 被接受） | 少一个往返 |
| 恢复 | Session ID / Session Ticket，1-RTT | PSK，默认 1-RTT；带 early data 才 0-RTT | 省掉证书和密钥协商 |
| 证书 | 明文传输 | ServerHello 之后加密 | 1.3 少暴露证书内容 |
| 密钥交换 | RSA 或 ECDHE | （EC）DHE 或 PSK，没有 RSA 密钥传输 | 前向保密 |

几个容易写错的地方：

- **TLS 1.2 完整握手是 2-RTT**，不是 1-RTT。ClientHello 一去一回算第一程，ClientKeyExchange/Finished 一去一回算第二程。
- **TLS 1.3 的 1-RTT 有条件**。客户端第一个 `key_share` 猜的群组如果服务器不接受，服务器回 `HelloRetryRequest`，客户端要再发一次 ClientHello，实际多一个往返。本机这次没有触发 HRR，输出里没有它；这是 RFC 8446 的标准路径，未实跑。
- **TLS 1.3 的 `ChangeCipherSpec` 是兼容中间设备的遗留记录**，不是握手消息，别把它算成一个往返。
- **0-RTT 不是「第一次连接就 0-RTT」**。它只发生在恢复连接、客户端带 `early_data` 的时候。0-RTT 数据可能被重放，而且这部分数据不具备完整前向保密；只适合可重放的幂等请求。GET 也不必然安全，取决于服务端语义。这是标准形态，本文没有实跑 0-RTT。
- **TLS 1.2 的会话恢复是 1-RTT**，不是 0-RTT；Session ID 和 Session Ticket 都不会让第一个包直接带应用数据。

表里是 GitHub 的 ECDHE 握手。TLS 1.2 用 RSA 密钥交换时没有 `ServerKeyExchange`，`ClientKeyExchange` 里是客户端用服务器公钥加密的预主密钥；这条老路没有前向保密。

省一个往返到底省多少，要看 RTT。本机对 GitHub 各跑 5 次，HEAD 请求、`--http1.1`、同一时段，只算 `time_appconnect - time_connect`：

```text
强制 1.2（--tlsv1.2 --tls-max 1.2）：
0.495 / 0.767 / 0.463 / 0.451 / 0.522 秒，中位数 0.495 秒

强制 1.3（--tlsv1.3 --tls-max 1.3）：
0.263 / 0.270 / 0.241 / 0.230 / 0.244 秒，中位数 0.244 秒
```

差值 0.251 秒；同一批 TCP connect 大多在 0.24–0.38 秒，和它同量级，差不多就是一个 RTT。它不能推出「1.3 永远快一倍」：如果 RTT 是 10 毫秒，省下的也就是 10 毫秒量级；如果连接本来就复用，或者瓶颈在服务端，握手省下的时间更不是主角。

## 证书验证的五个检查项

「浏览器信任就行」不是一项检查，是一串检查都过了。顺序大致是：链能不能追到受信根、当前时间在不在有效期、名字在不在 SAN 里、用途和算法够不够强、吊销状态怎么看。任何一项失败，浏览器都可能只显示「您的连接不是私密连接」，但底层错误码不同。

| 检查项 | 看什么 | 浏览器（Chrome）失败码 | 本机 curl / OpenSSL 原文 |
| --- | --- | --- | --- |
| 域名匹配 | SAN 里有没有你访问的名字；CN 已不参与匹配 | `ERR_CERT_COMMON_NAME_INVALID` | `SEC_E_WRONG_PRINCIPAL (0x80090322) - 目标主要名称不正确。`；OpenSSL 加 `-verify_hostname` 后是 `verify error:num=62:hostname mismatch` |
| 有效期 | `notBefore` / `notAfter`，注意 UTC 和客户端时钟 | `ERR_CERT_DATE_INVALID` | `SEC_E_CERT_EXPIRED (0x80090328) - 收到的证书已过期。` |
| 链到受信根 | leaf → intermediate → 受信根，中间证书是否发全 | `ERR_CERT_AUTHORITY_INVALID` | `SEC_E_UNTRUSTED_ROOT (0x80090325) - 证书链是由不受信任的颁发机构颁发的。`；OpenSSL 是 `error 20 ... unable to get local issuer certificate` |
| 吊销 | OCSP、CRL、OCSP stapling；浏览器 CRLSet/CRLite 策略 | `ERR_CERT_REVOKED`（未在本机浏览器复现） | `CRYPT_E_REVOKED (0x80092010) - 证书已被吊销。`；OpenSSL 默认不查，显示 `Verification: OK` |
| 算法与密钥强度 | 签名算法、密钥类型/长度；SHA-1、RSA-1024 等 | `ERR_CERT_WEAK_SIGNATURE_ALGORITHM`（未实跑） | `verify error:num=68:CA signature digest algorithm too weak`；`dh key too small`（OpenSSL，badssl 测试站） |

同一条命令的退出码也不一样：过期和吊销在这台机器上是 `curl: (35)`，域名不匹配和根不受信是 `curl: (60)`。别只记 60，先看错误文本。

### 域名：看 SAN，不看 CN

GitHub 这张 leaf 的 SAN 是：

```text
X509v3 Subject Alternative Name:
    DNS:github.com, DNS:www.github.com
```

没有 `*.github.com` 这种通配符。现代客户端只看 SAN，CN 字段即使写了域名也不参与匹配。

通配符只匹配一级。`badssl.com` 的证书 SAN 是：

```text
subject=CN=*.badssl.com
X509v3 Subject Alternative Name:
    DNS:*.badssl.com, DNS:badssl.com
```

`wrong.host.badssl.com` 有两级子域，不匹配 `*.badssl.com`，所以 Schannel 直接报 `SEC_E_WRONG_PRINCIPAL`。但 `openssl s_client` 默认只验链、不验域名：

```text
$ openssl s_client -connect wrong.host.badssl.com:443 -servername wrong.host.badssl.com -brief
Protocol version: TLSv1.2
Verification: OK

$ openssl s_client -connect wrong.host.badssl.com:443 -servername wrong.host.badssl.com -verify_hostname wrong.host.badssl.com -verify_return_error -brief
verify error:num=62:hostname mismatch
...certificate verify failed...
```

第一条命令的 `Verification: OK` 只代表链没问题，不代表域名匹配。**自查域名一定要加 `-verify_hostname`，脚本里再加 `-verify_return_error`**，否则失败也可能返回 0。

### 有效期：90 天不是一年

GitHub 这张 leaf 的实际日期：

```text
notBefore=Oct 30 00:00:00 2026 GMT
notAfter=Jan 27 23:59:59 2027 GMT
```

大约 90 天。Let's Encrypt 默认也是 90 天。公开信任的 TLS 证书上限还在分阶段缩短，CA/Browser Forum SC-081 的时间表是：2026-03-15 起最长 200 天，2027-03-15 起 100 天，2029-03-15 起 47 天。内部私有 CA 可以另定，但「证书一年有效」不是今天的通用规则，更不是 Let's Encrypt 的规则。

过期和「还没生效」在浏览器里都归到 `ERR_CERT_DATE_INVALID`。排查时先看 `notBefore` / `notAfter`，再对一下客户端时钟；时区看错也会误判。

### 链：leaf、中间证书、根

GitHub 服务端实际发了 3 张证书：

```text
$ openssl s_client -connect github.com:443 -servername github.com -showcerts
depth=2 C=GB, O=Sectigo Limited, CN=Sectigo Public Server Authentication Root E46
depth=1 C=GB, O=Sectigo Limited, CN=Sectigo Public Server Authentication CA DV E36
depth=0 CN=github.com
```

三张分别是 leaf、中间证书、以及一张交叉签名的根：`Root E46` 的 issuer 是 `USERTrust ECC Certification Authority`。它不是自签根，而是一座桥：只信任 USERTrust 的旧客户端可以靠它把链接上；已经信任 Sectigo 新根的客户端则觉得第三张多余。所以「服务器绝对不能发根」这句话太绝对，准确说法是：**发 leaf 和能建链的中间证书；根可以不发，但交叉签名根可以是有意为之。**

把 `-showcerts` 里的 PEM 按顺序存成 `leaf.pem`、`intermediate.pem`，用 `openssl verify` 能直接看到缺中间证书的后果：

```text
$ openssl verify -CAfile ca-bundle.crt -untrusted intermediate.pem leaf.pem
leaf.pem: OK

$ openssl verify -CAfile ca-bundle.crt leaf.pem
CN=github.com
error 20 at 0 depth lookup: unable to get local issuer certificate
error leaf.pem: verification failed
```

但「缺中间证书」在不同客户端上表现不一样。`incomplete-chain.badssl.com` 只发 leaf，Schannel 的 curl 仍然返回 200：

```text
$ curl.exe -sS -o NUL -w 'code=%{http_code}\n' https://incomplete-chain.badssl.com/
code=200
```

Windows 的 Schannel 把链补上了（可能走 AIA，也可能命中本地缓存），所以返回 200。OpenSSL 不会默认去抓 AIA，所以同一站点直接失败：

```text
$ openssl s_client -connect incomplete-chain.badssl.com:443 -servername incomplete-chain.badssl.com -brief
Verification error: unable to verify the first certificate
```

结论不是「缺中间证书没关系」，而是「别依赖客户端帮你补」。补链可能多一次网络请求，很多客户端不会做，某些环境还会因为出站限制失败。服务端把 fullchain 配全，才是可复现的做法。

### 吊销：客户端策略，不是协议必答

`revoked.badssl.com` 的证书已被吊销。本机 Schannel 的 curl 直接硬失败：

```text
$ curl.exe -sS -o NUL https://revoked.badssl.com/
curl: (35) schannel: next InitializeSecurityContext failed: CRYPT_E_REVOKED (0x80092010) - 证书已被吊销。
```

同一张证书，OpenSSL 默认不查吊销，所以显示 OK：

```text
$ openssl s_client -connect revoked.badssl.com:443 -servername revoked.badssl.com -brief
Protocol version: TLSv1.2
Verification: OK
```

这不是 OpenSSL「更信任」它，而是 OpenSSL 默认没有做 OCSP/CRL 查询。吊销检查有三种常见形态：CRL（下载列表）、OCSP（在线问 CA）、OCSP stapling（服务端把 CA 的签名响应随握手带来）。浏览器为了性能，并不保证为每张证书实时问 OCSP：Chrome 主要靠 CRLSet，Firefox 有 CRLite/OneCRL，是否硬失败取决于策略、缓存和网络。所以「吊销了一定连不上」不成立。

### 算法与密钥强度

GitHub 这张 leaf 是 ECDSA P-256、`ecdsa-with-SHA256`；TLS 1.2 协商到 `ECDHE-ECDSA-AES128-GCM-SHA256`，TLS 1.3 是 `TLS_AES_128_GCM_SHA256`。这些字段用 `openssl x509 -text` 能看到：

```text
Signature Algorithm: ecdsa-with-SHA256
Public Key Algorithm: id-ecPublicKey
Public-Key: (256 bit)
```

弱算法/弱密钥的失败，本机没有造出「纯弱算法」的样本，因为 badssl 的测试站先被过期或吊销拦住了。OpenSSL 在 `sha1.badssl.com` 上还是给出了算法相关的原文：

```text
verify error:num=68:CA signature digest algorithm too weak
```

`dh1024.badssl.com` 则给出：

```text
error:0A00018A:SSL routines:tls_process_ske_dhe:dh key too small
```

Schannel 这边没有走到算法判断，先报了吊销；所以这一行不能拿 Schannel 的原文冒充「弱算法错误」，只能标未实跑。生产上看到 SHA-1 签名、RSA-1024、过短的 DH 参数，处理方式是换证书/换配置，不是加 `-k`。

### 信任库不同，结论就不同

同一台机器上，不同客户端可能用不同信任库：

- Windows 的 `curl.exe`（Schannel）用 Windows 证书存储；
- Git 自带的 OpenSSL 用 `C:\Program Files\Git\usr\ssl\certs\ca-bundle.crt`；
- Node.js 用内置 CA，可用 `NODE_EXTRA_CA_CERTS` 追加；
- Python `requests` 默认用 `certifi`，可用 `REQUESTS_CA_BUNDLE` 覆盖；
- Java 用 JDK 的 `cacerts`，可用 `-Djavax.net.ssl.trustStore` 指定。

这就是「命令行能访问、代码里报 `unable to get local issuer certificate`」的常见原因：不是证书一定坏了，而是代码用的信任库没有那个根。

### 自签、私有 CA、pinning

自签 leaf 不能直接用于面向公众的生产服务。浏览器不认它，用户只能点「继续访问」；一旦用户习惯了点继续，中间人换一张自签证书也能骗过去。

本地开发可以这样生成一张带 SAN 的自签证书：

```bash
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 30 \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

这张证书只适合本机。内部生产环境要这样做：建一个私有根 CA，根私钥离线保存；用中间 CA 签发带正确 SAN 的 leaf；通过 GPO/MDM 把根证书分发到受管客户端；证书有效期按内部策略缩短；关键服务之间可以做 SPKI pinning，但要准备备份 pin 和轮换计划。HPKP 已经废弃，别把它当浏览器方案。

`curl -k`、`verify=False` 只适合一次性调试。它们关掉的是身份认证，不是「少点一次警告」：中间人可以换内容，你完全看不出来。

## HTTPS 不只是 HTTP 加一层 TLS

分层说法没错：HTTP 报文装在 TLS 记录里，TLS 跑在 TCP 上。但「HTTPS = HTTP + TLS」漏掉了几个真正决定行为的部件。

### ALPN：先谈好说 h2 还是 http/1.1

开头已经看到：同一个 GitHub，Schannel 的 curl 只报 `http/1.1`，OpenSSL 带 `-alpn h2,http/1.1` 就谈成 `h2`。ALPN 是 TLS 握手里的扩展，服务器从客户端给的列表里选一个。它不改变证书验证，但会改变后面的 HTTP 语义、并发模型和头部压缩。

HTTP/3 用 `h3`，跑在 QUIC/UDP 上，TLS 1.3 被嵌进 QUIC。本机这个 curl 的 Features 里没有 HTTP3，所以本文没有 HTTP/3 的实测数据。

### SNI：一个 IP 怎么接待多个域名

TLS 在 HTTP 之前，服务器还不知道你要访问哪个域名。客户端在 ClientHello 的 `server_name` 扩展里明文写上目标域名，服务器按名字选证书。用 IP 直连会出事：

```text
$ curl.exe -sS https://140.82.114.3/
curl: (60) schannel: SNI or certificate check failed: SEC_E_WRONG_PRINCIPAL (0x80090322) - 目标主要名称不正确。
```

证书 SAN 是 `github.com`，不是 `140.82.114.3`，所以域名检查失败。用 `--resolve` 把名字和 IP 对上，SNI 和 Host 都带 `github.com`，就正常：

```text
$ curl.exe -sS -o NUL -w 'code=%{http_code}\n' --resolve github.com:443:140.82.114.3 https://github.com/
code=200
```

SNI 默认是明文，中间设备能看到你访问的域名；目标 IP 和端口也一直可见。ECH（Encrypted Client Hello）能把 SNI 包进加密的内层 ClientHello，但需要 DNS HTTPS 记录和客户端/服务端同时支持，目前不能当成默认能力。想藏域名，不能只靠 TLS 1.3。

### CT：证书透明，让错发证书留下记录

GitHub 这张 leaf 里有 2 个 SCT（Signed Certificate Timestamp）：

```text
CT Precertificate SCTs:
    Signed Certificate Timestamp:
    Signed Certificate Timestamp:
```

CT 日志是只追加的账本，CA 签发证书时把承诺写进去；浏览器对公共信任证书要求足够的 SCT。它能让「CA 错发了一张 github.com 证书」事后被发现，但它不证明站点是好人，也不替代链验证。钓鱼站同样可以拿到一张 CT 齐全的 DV 证书。本文只核对了证书里的 SCT 数量，浏览器策略未实跑。

### HSTS：让浏览器下次别走 HTTP

GitHub 的响应头里有：

```text
Strict-Transport-Security: max-age=31536000; includeSubdomains; preload
```

浏览器收到后，在 `max-age` 秒内（31536000 秒 = 1 年）把该域名强制走 HTTPS，`includeSubDomains` 还覆盖子域；如果某个子域只会说 HTTP，这个指令会把它一起打死。`preload` 是申请加入浏览器内置列表的指令；是否真的在列表里，要看浏览器发行版的内置数据，不能只看响应头。

HSTS 的边界要说清：它只对「已经通过 HTTPS 收到过这个头」的浏览器生效。第一次访问如果走 HTTP，仍可能被中间人截住；preload 解决的是这部分首次访问。`curl` 也不会自动记住 HSTS，除非你给它 `--hsts` 缓存文件。`curl http://github.com/` 看到 301，那是服务器自己的跳转，不是 HSTS 在起作用。本文只核对了响应头，浏览器执行未实跑。

### 混合内容：HTTPS 页面里的 HTTP 子资源

HTTPS 页面如果加载 `http://` 的脚本、样式或 iframe，浏览器按「主动混合内容」拦截；图片、音视频等被动内容可能被自动升级，也可能被拦，别依赖具体行为。GitHub 的 CSP 里有 `upgrade-insecure-requests`，浏览器会把页面里的 `http://` 子资源改写成 `https://` 再请求，但服务端得真的支持 HTTPS，否则还是 404/证书错误。这是标准行为，本文未在浏览器里复现。

### URL 到底藏住了多少

TLS 加密的是两个端点之间的字节流。被动观察者能看到：目标 IP、端口、SNI（默认明文）、包的大小和时序；看不到：HTTP 请求行里的路径、首部、正文。所以「TLS 加密了 URL」这句话要拆开：

- 路径在 TLS 里面，普通中间路由器看不到；
- TLS 终点能看到，比如 CDN、反向代理、负载均衡，或者装了公司根证书的 MITM 代理；
- HTTPS 走普通 HTTP 正向代理时，代理只看到 `CONNECT github.com:443`，看不到路径；只有它做 TLS 中间人，或者你走的是明文 HTTP，路径才暴露。

所以别把「用了 HTTPS」理解成「访问路径对所有人都不可见」。对端和 TLS 终点永远看得到明文。

## 自查清单

下面命令都在本机跑过。`openssl` 指 Git 自带的 3.2.1；`NUL` 在 Linux/macOS 上换成 `/dev/null`。

**1. 看握手全貌**

```bash
curl.exe -v --trace-time -I https://github.com/
```

看 `Connected`、`ALPN: offers`、`ALPN: server accepted`、`using HTTP/1.1`。Schannel 不打印 TLS 版本，别在这份日志里找 `TLSv1.3`；要看版本用下一条。

**2. 看证书链、ALPN、域名验证**

```bash
Write-Output '' | openssl s_client -connect github.com:443 -servername github.com -showcerts -alpn h2,http/1.1
Write-Output '' | openssl s_client -connect github.com:443 -servername github.com -verify_hostname github.com -verify_return_error
```

看 `depth=` 有几层、`BEGIN CERTIFICATE` 有几张、`Verify return code` 是不是 0。`-verify_hostname` 不能省；不加它，`wrong.host.badssl.com` 也会显示 `Verification: OK`。

**3. 算 TLS 握手时间**

```bash
curl.exe -sS -I -o NUL -w 'tcp=%{time_connect} tls=%{time_appconnect} first=%{time_starttransfer}\n' https://github.com/
curl.exe -sS -I -o NUL --tlsv1.2 --tls-max 1.2 -w 'tls12=%{time_appconnect}\n' https://github.com/
curl.exe -sS -I -o NUL --tlsv1.3 --tls-max 1.3 -w 'tls13=%{time_appconnect}\n' https://github.com/
```

`tls - tcp` 才是 TLS 握手。`--tlsv1.2` 只是最低版本，不加 `--tls-max 1.2` 仍可能谈成 1.3；要强制 1.2 必须同时加上限。

**4. 强制 1.2，确认服务端还接受**

```bash
curl.exe -sS -I -o NUL --tlsv1.2 --tls-max 1.2 -w 'code=%{http_code}\n' https://github.com/
```

返回 200 说明服务端还接受 1.2；失败就看错误文本，不要只看 curl 的退出码。反过来，`--tlsv1.3` 是「最低 1.3」，不是「最高 1.3」。

**5. 看证书字段**

```bash
Write-Output '' | openssl s_client -connect github.com:443 -servername github.com 2>$null | openssl x509 -noout -text
```

重点看 `Subject Alternative Name`、`Not Before` / `Not After`、`Signature Algorithm`、`Public-Key`、`CT Precertificate SCTs`、`OCSP` 和 `CA Issuers`。`OCSP`/`AIA` 有地址不代表客户端一定会去查。

## 检查一下

```quiz
{
  "id": "net-09-quiz",
  "title": "HTTPS 三问",
  "questions": [
    {
      "question": "TLS 1.3 的完整握手，在客户端第一个 key_share 就被服务器接受时，是几个 RTT？",
      "options": ["1-RTT", "2-RTT", "0-RTT", "3-RTT"],
      "answer": 0,
      "explain": "完整握手是 1-RTT；只有恢复连接且客户端发送 early data 时才可能是 0-RTT，HelloRetryRequest 会再加一个往返。"
    },
    {
      "question": "浏览器判断证书里的域名是否匹配，主要看哪个字段？",
      "options": ["CN", "OU", "SAN", "serial"],
      "answer": 2,
      "explain": "现代客户端看 SAN（Subject Alternative Name）；CN 已不参与域名匹配。"
    },
    {
      "question": "TLS 1.3 的 0-RTT 数据最大的风险是什么？",
      "options": ["证书链不完整", "被重放", "ALPN 协商失败", "SNI 被加密"],
      "answer": 1,
      "explain": "0-RTT 用预共享密钥直接发数据，缺少抗重放保证；只适合可重放的幂等请求，而且这部分数据不具备完整前向保密。"
    }
  ]
}
```

## 还差的

- 没在公网抓包，也没导入 `SSLKEYLOGFILE` 解密；TLS 记录层和 HTTP/2 帧的细节是协议标准形态，未实跑。
- 没在本机 Chrome/Edge 逐页复现五类证书错误；表里的浏览器列是 Chrome net error 的稳定错误码，未截图。
- 没造弱签名/弱密钥证书；算法强度这一行只有 OpenSSL 在 badssl 上的 `CA signature digest algorithm too weak` 和 `dh key too small`，没有 Schannel 的纯弱算法错误。
- 没测 HTTP/3（QUIC）和 0-RTT 重放；本机 curl 是 Schannel 构建，`curl -V` 的 Features 里没有 HTTP2/HTTP3。
- 没做会话复用/0-RTT 的实测，`openssl s_client -reconnect` 没有纳入证据。
