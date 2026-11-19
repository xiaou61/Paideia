---
title: 代理、负载均衡与 CDN：请求路上还有谁
category: network-basics
order: 10
minutes: 20
level: intermediate
status: published
---

## 一个 404 的 favicon，在边缘是 HIT

本机 Windows 11，2026-11-19。先看一个公开回显服务的 favicon：

```text
$ curl.exe -sS -I https://postman-echo.com/favicon.ico
HTTP/1.1 404 Not Found
Date: Thu, 19 Nov 2026 06:20:32 GMT
Content-Type: application/json; charset=utf-8
Connection: keep-alive
vary: Accept-Encoding
x-envoy-upstream-service-time: 4
Age: 12
Cache-Control: public, max-age=14400
cf-cache-status: HIT
set-cookie: __cf_bm=...
set-cookie: _cfuvid=...
Server: cloudflare
CF-RAY: a5cd1c11cfd1dc9c-FRA
```

一个不存在的文件返回 404，`cf-cache-status` 却是 `HIT`，`Age` 是 12。我第一次看到时把 `Age` 当成了「这次请求花了 12 秒」，其实它是这份响应在缓存里已经存了约 12 秒（RFC 9111 §5.1）。404 也能被缓存：十几秒前第一次请求是 `EXPIRED`，回源拿到新的 404 后，第二次就 `HIT` 了。你要是在这个时间窗口里把 favicon 补上，边缘可能还在回 404，直到缓存过期或被 purge；具体 TTL 我从外部看不出来，下面有对照。

同一条响应里出现了三层：

- `Server: cloudflare` 和 `CF-RAY: ...-FRA`：CDN 边缘。FRA 是法兰克福的 IATA 代码，Cloudflare 文档用它标记处理请求的数据中心。
- `x-envoy-upstream-service-time: 4`：响应里的 Envoy 头，单位毫秒。它说明 Cloudflare 后面还有一层 Envoy（L7 代理）；具体在哪台机器上，我从外部看不到。它算的是 Envoy 到上游的处理时间加这段网络延迟，不是整个请求的耗时。
- `Content-Type: application/json` 的 404 body：源站应用自己的 404。

下面把这条响应拆开看。正向代理（`curl -x`、企业出口）是客户端侧的另一条链路，不在这条响应里；这篇只跟反向代理和 CDN 走。

## Age 是缓存年龄，CF-Cache-Status 是厂商字段

`Age` 是标准字段，RFC 9111 的定义是「响应在源站生成或验证之后，到这次响应发出为止的估算秒数」。`Age: 12` 不是请求耗时，也不是节点运行时间。它和 `Cache-Control: public, max-age=14400` 一起看：按响应头算，这份缓存还能新鲜约 14388 秒；CDN 自己的 TTL 规则可能更短，下面会看到。响应里同时有 `Expires` 时，`max-age` 优先；如果还有 `s-maxage`，共享缓存看 `s-maxage`，不看 `max-age`。

`cf-cache-status` 不是标准字段，是 Cloudflare 的。`x-cache` 是另一家的名字。值也别背：我这次见到了 `HIT`（命中）、`EXPIRED`（有缓存但过期，回源）、`MISS`（没命中，回源）、`DYNAMIC`（这条链路决定不缓存）。日志和告警不要只匹配 `x-cache`，换个 CDN 就匹配不上了。

`Vary: Accept-Encoding` 也在响应里。它的意思是这份响应的变体取决于请求的 `Accept-Encoding`；缓存键要按这个头区分 gzip/br。漏了它，压缩版本可能发给不支持的客户端；但边缘统一压缩是另一回事，不要看到 `Vary` 就以为源站按编码存了两份。

## 同一个域名，`/headers` 为什么不缓存

换一条路径，还是这个域名：

```text
$ curl.exe -sS -I https://postman-echo.com/headers
HTTP/1.1 200 OK
Date: Thu, 19 Nov 2026 06:19:05 GMT
Content-Type: application/json; charset=utf-8
Content-Length: 137
Connection: keep-alive
etag: W/"89-4inUppSm2qmlxZdAGYO7XGyFmU0"
vary: Accept-Encoding
x-envoy-upstream-service-time: 6
cf-cache-status: DYNAMIC
Set-Cookie: sails.sid=...
set-cookie: __cf_bm=...
set-cookie: _cfuvid=...
Server: cloudflare
CF-RAY: a5cd19ebcaeb566a-FRA
```

`DYNAMIC` 的意思是 Cloudflare 这次决定不缓存。为什么 favicon 能 `HIT`，这个不能？把两条响应的 `Set-Cookie` 对一下：favicon 只有 Cloudflare 自己加的 `__cf_bm` 和 `_cfuvid`；`/headers` 多了一个源站的 `sails.sid`。按 Cloudflare 的文档，源站响应带 `Set-Cookie` 就不缓存；它还只默认缓存扩展名列表里的静态文件，HTML/JSON 不在列表里。`/headers` 两条都占了。

我一开始以为「源站写了 `Cache-Control: public` 就会缓存」。再试一条显式写 `public` 的：

```text
$ curl.exe -sS -D - -o NUL "https://postman-echo.com/response-headers?Cache-Control=public%2C%20max-age%3D60"
HTTP/1.1 200 OK
...
Cache-Control: public, max-age=60
cf-cache-status: DYNAMIC
x-envoy-upstream-service-time: 4
...
```

还是 `DYNAMIC`。`public` 只是告诉共享缓存「这份响应可以被共享」，不是命令它「存」。CDN 还有自己的规则：扩展名、Cookie、Cache Rules。源站不禁止、CDN 也愿意存，才会真正进缓存。反过来说，如果 CDN 规则被改成「忽略 Cookie 和 Authorization，按 URL 缓存」，源站又返回了 `public`，那就可能把个人数据存进共享缓存。默认值不是安全边界，规则才是。

## 缓存键：`?cb=` 这次进了键，`no-cache` 没让边缘回源

把同一个 favicon 的几次请求按时间排一下，都是 `curl -I`，只留关键列：

| 时间 (GMT) | URL | CF-Cache-Status | Age |
| --- | --- | --- | --- |
| 06:20:20 | `/favicon.ico` | EXPIRED | — |
| 06:20:32 | `/favicon.ico` | HIT | 12 |
| 06:20:36 | `/favicon.ico?cb=20261119` | MISS | — |
| 06:25:11 | `/favicon.ico?cb=20261119` | EXPIRED | — |
| 06:25:11 | `/favicon.ico?cb=20261119` | HIT | 0 |
| 06:27:23 | `/favicon.ico` | HIT | 74 |

三件事：

1. `?cb=` 第一次 `MISS`，几分钟后同一个带参 URL 出现 `EXPIRED`，再请求就是 `HIT Age: 0`。这个查询串在这条链路上进了缓存键；它不是「加个参数就回源」的万能开关，也不是「加参数没用」，取决于配置。
2. 同一秒里先 `EXPIRED` 再 `HIT`，说明第一次回源拿到的新 404 立刻被存了下来。
3. 不带参数的 `/favicon.ico` 在 06:20:32 是 `HIT Age: 12`，06:27:23 变成 `HIT Age: 74`，不是约 7 分钟。中间有别的请求回源刷新了这个对象，或者它被重新缓存了；`Age` 是每个缓存对象的年龄，不是全局计时器。公共服务还有别的流量，别从一个 `Age` 推 TTL。

再换请求头：

```text
$ curl.exe -sS -I -H "Cache-Control: no-cache" https://postman-echo.com/favicon.ico
...
Age: 12
cf-cache-status: HIT
```

`Cache-Control: no-cache` 是请求指令，意思是「用之前先验证」；这条链路没有按它回源，还是 `HIT`，`Age` 还是 12。不同 CDN 对客户端指令的处理不一样，我不能从这两条推出「所有 CDN 都这样」。但能推出一个实用结论：发版时别把 `?cb=` 或 `Cache-Control: no-cache` 当成通用刷新手段。要立刻生效，用 CDN 的 purge/invalidate；要长期不踩，静态资源用内容哈希文件名，入口 HTML 短缓存。

`immutable` 是 RFC 8246 的扩展指令，浏览器在新鲜期内连刷新都不验证；它主要约束浏览器，不代替 CDN 的 TTL 配置。静态资源用内容哈希文件名时，它可以和长 `max-age` 一起用；普通 HTML 不要用。

还有一个容易混的点：`Age` 可以来自上层缓存。开了分层缓存（CloudFront 叫 Origin Shield，Cloudflare 叫 Tiered Cache）时，边缘命中的 `Age` 可能是在上层就已经存了很久，可能不等于本地节点什么时候存的。`Age` 要和 `Cache-Control`/`s-maxage` 一起读，单独看不够。

缓存投毒也不是某个头名自带的：一种常见形态是源站把未键控的头反射进响应（比如用 `X-Forwarded-Host` 拼绝对 URL），而缓存键又没包含它。其他形态（键归一化、请求走私）我没展开，也没造环境，未实跑。

## 三层各自能改什么

把上面三条响应按层拆（从响应头推断）：

```text
客户端 → Cloudflare 边缘（TLS 终止、缓存、CF-RAY）
       → Envoy（L7 代理，x-envoy-upstream-service-time）
       → 应用（404 body、sails.sid）
```

`CF-RAY` 的后缀 `FRA` 是 Cloudflare 处理请求的数据中心。`nslookup` 这个域名：

```text
$ nslookup postman-echo.com
...
Name:    postman-echo.com
Address:  162.159.142.41
```

`162.159.142.41` 是 Cloudflare 的 anycast 地址。同一个 IP 从很多位置宣告，BGP 把包带到其中一个边缘；GeoDNS 也可能按解析位置返回不同地址。所以「DNS 把用户导到最近的节点」这句话只能当粗略描述：`CF-RAY` 是 `FRA` 不代表我离法兰克福最近，只代表这次路由落到了那里。

L4 和 L7 的差别不是「快慢」，是「能看到哪一层」：

| | 纯 L4 | L7 |
| --- | --- | --- |
| 看得到 | IP、端口、TCP/UDP；配了 SNI 嗅探才能看 SNI | HTTP 方法、Host、路径、首部、Cookie |
| 能改 | NAT/转发、按地址和端口选后端 | 改首部、按路径/Host 路由、重试、健康检查 |
| 看不到 | 加密后的 HTTP Host | — |
| 连接 | 转发或 NAT，不解析 HTTP | 终止客户端连接，再向后端建连接 |
| 客户端 IP | 取决于是否 SNAT；可用 PROXY protocol | 可用 X-Forwarded-For |

「L4 不能按域名分流」说得太满：Nginx 的 `stream` + `ssl_preread` 能读 ClientHello 里的 SNI，按域名选后端（1.11.5+）。它读不到的是 TLS 里面的 HTTP `Host`。如果 TLS 在 L4 就透传，L4 只能靠 SNI；如果 TLS 在这里终止、还要按 HTTP 内容分流，那就是 L7 的事。

L7 的代价是连接语义变了：客户端到代理是一条 TCP，代理到后端是另一条。后端超时、重试、连接池都和客户端侧独立。客户端超时了，后端可能还在跑；反过来，代理超时了，后端可能已经处理完，客户端却只看到 502/504。排查时先把这两段分开。

`x-envoy-upstream-service-time` 能证明这里有 L7：纯 L4 加不了 HTTP 响应头。应用的 `/headers` 回显里还有 `X-Forwarded-Proto: https`，说明链路上有人加了这个头；它说的是「客户端到加头的那一跳是 HTTPS」，不说明后面到源站用 HTTP 还是 HTTPS。应用如果不信这个头，强制跳 HTTPS 的中间件可能一直循环；如果信错了（内层代理又用 `$scheme` 覆盖成 `http`），也会循环或生成 `http://` 的绝对 URL。这两种症状不一样：一个是跳转循环，一个是降级/混合内容。

CDN 也不只缓存静态文件。它可以在边缘终止 TLS、压缩、和客户端谈 HTTP/2 或 HTTP/3，动态请求可以走优化过的回源链路。动态请求可以不进缓存，但「不缓存」不等于「CDN 没参与」；它仍然在转发、加头、做防护。效果取决于路径和协议，我没做跨境对比，不给倍数。

## 时间账：`first - tls` 不是某一跳的耗时

对同一个 `/headers` 跑三次 `curl -w`：

```text
$ curl.exe -sS -o NUL -w 'dns=%{time_namelookup} tcp=%{time_connect} tls=%{time_appconnect} first=%{time_starttransfer} total=%{time_total}\n' https://postman-echo.com/headers
dns=0.030091 tcp=0.187959 tls=0.364853 first=0.631447 total=0.631525
dns=0.026153 tcp=0.176767 tls=0.344810 first=0.581119 total=0.581191
dns=0.022129 tcp=0.173449 tls=0.349666 first=0.915761 total=0.915828
```

`time_appconnect` 包含 DNS 和 TCP；`tls - tcp` 才是 TLS 握手，这三次约 0.17–0.18 秒。`first - tls` 约 0.23–0.57 秒，里面是请求发出、Cloudflare、Envoy、应用、第一字节回程的总和。`x-envoy-upstream-service-time` 只有几毫秒，不能拿它和 `first - tls` 直接相减出「CDN 花了多少」，因为还隔着客户端到边缘的 RTT。三次样本，别把 0.58/0.92 秒当成这个服务的固定性能。

## X-Forwarded-For：伪造的值在这条链路上没到应用

还是这个 `/headers`，这次带三个头：

```text
$ curl.exe -sS https://postman-echo.com/headers -H "X-Forwarded-For: 1.2.3.4" -H "Forwarded: for=1.2.3.4" -H "X-Custom-Test: hello"
{"headers":{"host":"postman-echo.com","accept-encoding":"gzip, br","forwarded":"for=1.2.3.4","x-custom-test":"hello","accept":"*/*","user-agent":"curl/8.0.1","x-forwarded-proto":"https"}}
```

`Forwarded` 和 `X-Custom-Test` 到了应用，`X-Forwarded-For` 没有出现在回显里。是谁删的，我从外部看不到：可能是 Cloudflare 的规则，也可能是应用不回显。这个负结果本身有用——别假设每一跳都会保留或追加 XFF；以你链路上的回显/日志为准。

Nginx 的 `$proxy_add_x_forwarded_for` 是追加，不是校验。我在本机用一个玩具代理把规则跑出来（会占住一个终端，另开一个窗口跑 curl）：

```text
$ node -e 'const http=require("http");http.createServer((q,s)=>{const x=[q.headers["x-forwarded-for"],q.socket.remoteAddress].filter(Boolean).join(", ");s.end(x)}).listen(18080,"127.0.0.1")'
$ curl.exe -sS -H "X-Forwarded-For: 1.2.3.4" http://127.0.0.1:18080/
1.2.3.4, 127.0.0.1
```

伪造的 `1.2.3.4` 留在最左边，代理把自己的对端地址追加在后面。这就是 `$proxy_add_x_forwarded_for` 的语义：它只追加，不判断左边是谁。应用如果取第一个值做限流或审计，攻击者换个假 IP 就能绕过。

正确的顺序是三层，不是一句「信任代理」：

1. **入口代理覆盖**：`proxy_set_header X-Forwarded-For $remote_addr;`。如果入口前面还有 CDN/负载均衡，`$remote_addr` 是那一跳的地址；先把可信代理段配进 `real_ip`，让 `$remote_addr` 变成真实客户端，再覆盖。否则你会把 CDN 的 IP 写进去，真实 IP 反而丢了。
2. **内层代理追加**：`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`。每一层只追加自己看到的对端，不修改左边。
3. **应用从右往左信任**：只信任已知代理追加的那一段，取最后一个不可信地址。Nginx 的 `real_ip` 模块就是按这个规则做的：

```nginx
# 只信任这些地址发来的 XFF；换成你的入口/CDN 网段
set_real_ip_from 203.0.113.0/24;
real_ip_header X-Forwarded-For;
real_ip_recursive on;
```

`set_real_ip_from` 是信任边界。写成 `0.0.0.0/0` 等于让任何人自选 IP；只写你确实控制的代理段。`real_ip_recursive on` 从右往左跳过可信地址，取第一个不可信的；如果入口把伪造值留在左边，它就会把伪造值当成客户端——所以第 1 步的覆盖顺序不能反。`ngx_http_realip_module` 不是默认编译进去的，装之前看 `nginx -V` 有没有 `--with-http_realip_module`。如果在 Cloudflare 后面，它可能用 `CF-Connecting-IP` 而不是 XFF；以你链路实际带的头和 CDN 文档为准。

`X-Forwarded-Proto` 是另一个方向的坑。入口是 TLS 终止点时，`proxy_set_header X-Forwarded-Proto $scheme;` 是对的：`$scheme` 就是客户端用的 https。到了内层代理，`$scheme` 变成 CDN/入口到内层的 http；如果内层还写 `$scheme`，应用就会把 https 覆盖成 http。内层要么保留入口写的值，要么用 CDN 提供的字段：

```nginx
# map 放在 http {} 里
map $http_x_forwarded_proto $upstream_proto {
    default $http_x_forwarded_proto;
    ''      $scheme;
}
# 放在 location/server 里
proxy_set_header X-Forwarded-Proto $upstream_proto;
```

这个 map 不能放在公网入口：客户端可以自己带 `X-Forwarded-Proto: https`，入口再原样信任，就等于让客户端决定协议。入口用 `$scheme`，内层保留；应用只信任已知代理给的值。内层也不能直接暴露给客户端，否则客户端绕过入口，直接带这个头进来；应用侧如果直接可达（绕过代理），同样不要信这些头。

## 负载均衡：开源 Nginx 没有这些开关

把「负载均衡」当成一个产品名，很容易写出不存在的配置。下面这张表只列我查过文档的差异：

| 能力 | Nginx 开源版 | Envoy | 备注 |
| --- | --- | --- | --- |
| 轮询 | 默认 | `ROUND_ROBIN` | |
| 最少连接/请求 | `least_conn` | `LEAST_REQUEST` | Envoy 看活跃请求数，不是响应时间 |
| 一致性哈希 | `hash $key consistent` | `RING_HASH` / `MAGLEV` | |
| 随机二选一 | `random two`（1.15.1+） | `LEAST_REQUEST` 默认 `choice_count: 2` | P2C |
| 最少响应时间 | Plus 的 `least_time` | 没有同名标准算法 | 开源版没有；这是 Plus 的 |
| 主动 HTTP 健康检查 | Plus 的 `health_check` | 有 | 开源版只有被动 `max_fails`/`fail_timeout` |

Nginx 开源版的被动检查默认是 `max_fails=1`、`fail_timeout=10s`：一次失败就摘 10 秒，之后用真实请求探活。它只在有流量时工作；一台没流量的后端挂了，它不会知道。要主动探 `/healthz`，Nginx 开源版得靠外部探针，或者换 Plus/Envoy/HAProxy。`health_check` 写进开源版不是「不生效」，是 `nginx -t` 直接报未知指令。

`proxy_next_upstream` 的默认值是 `error timeout`，但非幂等请求（POST/PATCH）在已经发给上游之后，默认不会再换一台；要开 `non_idempotent` 才会。Envoy 也一样，没配 `retry_policy` 就不重试。所以「L7 会重试 POST，可能重复下单」不是默认行为；是你打开重试、又没有幂等键时的风险。超时不等于第一台没处理，重发前先想清楚。

会话保持也是配置出来的：Nginx 开源版没有内建 cookie `sticky`，常用 `ip_hash` 或 `hash $cookie_session consistent`；Plus 有 `sticky cookie`，Envoy 用 hash policy。它能解决「登录态在本机内存」，但扩容不均、故障丢会话、NAT 后集中这三个问题还在。能让会话不依赖本机（Redis、数据库、带撤销/轮换的签名令牌）就别开粘性。

## 两个令牌拿到同一份 body，先查什么

我没有一个会串号的线上 CDN 可以复现。下面是最小验证顺序，`api.example.com` 和 `<TOKEN_A>` 都是占位，标准形态，未实跑。

先用两个令牌各拿一次响应头和 body：

```text
curl.exe -sS -D headers-a.txt -o body-a.json https://api.example.com/api/me -H "Authorization: Bearer <TOKEN_A>"
curl.exe -sS -D headers-b.txt -o body-b.json https://api.example.com/api/me -H "Authorization: Bearer <TOKEN_B>"
```

先比 body。如果两份 body 不一样，优先查应用、会话、数据库，不要先怪 CDN。如果两份 body 一样，再看 `headers-a.txt` / `headers-b.txt` 里的四行：

- `Cache-Control`：有没有 `public`、`s-maxage`，还是 `private`/`no-store`；
- `Age`：是不是大于 0；
- `cf-cache-status` / `x-cache`：是 `HIT` 还是 `DYNAMIC`/`BYPASS`/`MISS`；
- `Vary`：有没有把不该共享的维度算进缓存键。

如果 body 一样、`Age > 0`、`Cache-Control: public`，共享缓存是第一个嫌疑。但别急着下结论：`Age` 也可能来自源站自己写的头，或者上层缓存；要再用一个带随机查询串的 URL 请求一次。如果这个 CDN 把查询串算进缓存键，换 URL 就正常，缓存串号的可能性更大；如果查询串不在键里，这个对照无效，得用 purge 或不同路径再验。这个对照我没在真实环境跑过。

修复顺序：

1. 源站认证接口统一返回 `Cache-Control: private, no-store`。`private` 允许浏览器存，`no-store` 连浏览器也不让存；两个一起写不冲突。不要用 `Vary: Authorization` 当默认方案：它把每个令牌变成独立变体，缓存命中率会很难看；认证响应直接 `no-store` 更省事。
2. 在 CDN 上把 `/api/*` 配成绕过缓存，或者加一条「请求带 `Authorization`/响应带源站 `Set-Cookie` 就不缓存」的规则。规则要写出来、测出来，不要依赖「默认应该不会缓存」。
3. purge 已污染的对象，然后用两个令牌再验一次：body 不同，`cf-cache-status` 是 `DYNAMIC`/`BYPASS`，没有 `Age`。

RFC 9111 §3.5 说，共享缓存默认不能拿带 `Authorization` 请求的响应去满足后续请求，除非响应明确允许（`public`、`s-maxage`、`must-revalidate`）。但 CDN 的规则可以覆盖这个默认；标准是底线，不是配置的替代品。

## 检查一下

```quiz
{
  "id": "net-10-quiz",
  "title": "中间链路三问",
  "questions": [
    {
      "question": "响应头里的 Age: 12 表示什么？",
      "options": ["这次请求花了 12 秒", "这份响应在缓存里已经存了约 12 秒", "CDN 节点启动了 12 秒", "TTL 还剩 12 秒"],
      "answer": 1,
      "explain": "Age 是 RFC 9111 定义的缓存年龄，估算响应从源站生成或验证后经过了多久；不是请求耗时，也不是节点运行时间。"
    },
    {
      "question": "Nginx 开源版怎么做 HTTP 健康检查？",
      "options": ["只能靠 max_fails/fail_timeout 被动摘除", "health_check 指令直接可用", "least_time 就是主动检查", "sticky 就是主动检查"],
      "answer": 0,
      "explain": "主动 health_check 是 NGINX Plus 的能力；开源版只有被动检查，默认 max_fails=1、fail_timeout=10s。"
    },
    {
      "question": "入口 Nginx 用 proxy_add_x_forwarded_for 转发，客户端自带 X-Forwarded-For: 1.2.3.4，会发生什么？",
      "options": ["伪造值被丢弃", "只剩 1.2.3.4", "请求被拒绝", "伪造值保留，Nginx 在后面追加真实对端地址"],
      "answer": 3,
      "explain": "proxy_add_x_forwarded_for 只追加 $remote_addr，不校验左边的值；所以入口要先覆盖成 $remote_addr，内层才追加。"
    }
  ]
}
```

## 还差的

- 本机没有 Nginx、Docker 和 Linux 环境；上面的 Nginx 配置是标准形态，没跑过 `nginx -t`，也没起后端做联调。
- 没有自己的 CDN 账号和源站，所有 CDN 观测都来自公开的 `postman-echo.com`；我看不到它的 Cloudflare 规则、源站配置和 purge 行为，`Cache-Control: no-cache` 为什么没回源只能从响应推断。
- 没有复现认证响应串号；缓存投毒、`Vary` 漏项、`X-Forwarded-Host` 反射只写了成立条件，未实跑。
- 没有跑 Envoy、Nginx Plus、HAProxy；主动健康检查、`least_time`、`slow_start`、`sticky` 的差异来自文档，未在本机验证。
- 没有测 HTTP/3、动态加速和跨境回源；本文不给性能倍数，也不把一组 `curl -w` 当成服务基线。