---
title: TCP 为什么需要三次握手
category: network-basics
order: 4
minutes: 18
level: intermediate
status: published
---

## 同一台机器，两个端口，两种失败

先跑两条，目标是我手边这台网关的同一个地址，按常见的 443/80 组合各跑一条。下面用 `https://` 连 443、`http://` 连 80，和题目里的命令形状一致；TCP 连接先发生，443 这条在 TLS 开始前就已经失败了。

```text
$ curl.exe -sS -v --connect-timeout 3 -o NUL https://192.168.8.1:443/
*   Trying 192.168.8.1:443...
* connect to 192.168.8.1 port 443 failed: Connection refused
* Failed to connect to 192.168.8.1 port 443 after 2051 ms: Couldn't connect to server
* Closing connection 0
curl: (7) Failed to connect to 192.168.8.1 port 443 after 2051 ms: Couldn't connect to server

$ curl.exe -sS -v --connect-timeout 3 -o NUL http://192.168.8.1:80/
*   Trying 192.168.8.1:80...
* ipv4 connect timeout after 3000ms, move on!
* Failed to connect to 192.168.8.1 port 80 after 3013 ms: Timeout was reached
* Closing connection 0
curl: (28) Failed to connect to 192.168.8.1 port 80 after 3013 ms: Timeout was reached
```

第一条在 2051 毫秒后回了 `Connection refused`；第二条等到 3013 毫秒的 `--connect-timeout` 才放弃。一个有人回了 RST，一个没有回音。

题目里常见的组合是 443 超时、80 拒绝（`https://host:443` 等到超时，`http://host:80` 很快回 RST）；我先按这个顺序试，结果在这台网关上正好相反——下面是我实际跑到的反例。这个反转就是第一件事：**端口号不决定报错，谁回 RST 才决定。** 换一台机器，顺序可能又反过来；判断方法一样，不要背端口。

`Connection refused` 可能是：目标端口没有程序监听，目标 TCP 栈回 RST；也可能是防火墙或负载均衡按策略回 RST；还可能是本机代理或协议栈在连接前就拒绝。它说明有人回了拒绝（RST），不证明「包到了应用」。

`Timeout was reached` 可能是：SYN 被本机防火墙、云安全组静默丢弃，路由黑洞，服务端半连接队列满后丢 SYN，或者目标关机。它只说明「没收到回音」，不证明「服务器死了」。

三次握手就是在这个分岔口上开始的：客户端发 SYN，等 SYN+ACK；收到就回 ACK，收不到就按计时器重传。

## 三个段各自证明了什么

```sketch
{
  "title": "三个段：第二个段同时确认并发送",
  "rows": [
    [
      { "id": "syn", "title": "SYN", "sub": "seq=x，ACK 位=0", "tone": "blue" },
      { "id": "synack", "title": "SYN+ACK", "sub": "ACK 位=1，ack=x+1，seq=y", "tone": "blue" },
      { "id": "ack", "title": "ACK", "sub": "ACK 位=1，ack=y+1，可捎带数据", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "syn", "to": "synack" },
    { "from": "synack", "to": "ack" }
  ]
}
```

```text
客户端                                服务端
  |  SYN, seq=x, ACK 标志=0            →  |  客户端能发；我的 ISN 是 x
  |  ←  SYN+ACK, seq=y, ack=x+1, ACK 标志=1 |  服务端能发；收到了 x
  |  ACK, seq=x+1, ack=y+1, ACK 标志=1 →  |  收到了 y；客户端已 ESTABLISHED
```

第二段是 `SYN+ACK`，不是单独的 `SYN`：`ACK` 标志位是 1，表示确认号有效；`ack=x+1` 是对客户端 SYN 的确认；`seq=y` 是服务端自己的 SYN。一个段干了两件事。这里的 `ACK=1` 是控制位，不是确认号 1。

| 段 | 发送时状态 | 对方由此知道 |
| --- | --- | --- |
| 1. SYN(x) | 客户端 SYN-SENT | 客户端能发；客户端的 ISN 是 x |
| 2. SYN+ACK(y, x+1) | 服务端 SYN-RECEIVED | 服务端能收能发，且收到了 x；服务端自己的 ISN 是 y，但还不知道 y 有没有被收到 |
| 3. ACK(y+1) | 客户端 ESTABLISHED | 客户端收到了 y；服务端收到后进入 ESTABLISHED |

为什么不是两次：第二次之后，客户端已经能确认服务端收到了自己的 SYN，也收到了服务端的 SYN；但服务端还不知道自己的 SYN+ACK 有没有到达客户端。它如果这时就开始发数据，而 SYN+ACK 丢了，客户端还在 SYN-SENT，不会把数据当成已建立连接的一部分。第三个 ACK 是给服务端的「我收到了你的 y」。RFC 9293 还把「防止旧的重复连接请求造成混淆」列为三次握手的主要原因：迟到的旧 SYN 可能让服务端回 SYN+ACK，但客户端不在 SYN-SENT，会用 RST 或 challenge ACK 拒掉，不会建立一条假连接。

为什么三次是最小：如果每个方向都单独发 SYN、单独回 ACK，是四段：客户端 SYN → 服务端 ACK → 服务端 SYN → 客户端 ACK。TCP 把中间两个合成 `SYN+ACK`，所以是三个。**省下的是第二段的合并，不是第三步捎带数据。** 第三步捎带数据是额外优化：客户端发完 ACK 已经 ESTABLISHED，可以把请求数据放在同一个段里；这只省下一个单独的段，不是省下一个往返。

两端不是同一时刻连上：客户端发出第三个 ACK 就 ESTABLISHED；服务端收到它（或收到带 ACK 的数据）才 ESTABLISHED。忽略处理时间，客户端是 1 个 RTT，服务端是 1.5 个 RTT。

把它想成隔街喊话也行：三次里有一次是「我听见你了，我也报一下我的名字」。但喊话只能解释为什么要回音，解释不了序号、ACK 和重传。

## 初始序号：两边各选各的，不是一个数

握手不是让两端把初始序号对成一个数。客户端选 x，服务端选 y；两个数由两端各自选，不需要相同，也可能碰巧相同。握手要做的是：每一方都拿到对方的 ISN，并回一个确认。RFC 9293 图 6 的例子就是 x=100、y=300：第二个段是 `SEQ=300, ACK=101, SYN,ACK`，第三个段是 `SEQ=101, ACK=301, ACK`。

ISN 不是纯随机。RFC 6528 的公式是 `ISN = M + F(localip, localport, remoteip, remoteport, secretkey)`：M 是一个大约每 4 微秒加一的时钟，F 是带密钥的伪随机函数。它把四元组和密钥混进去，所以不可预测，但整体随时间大致递增。不是 `rand()`，也不是每次从 0 开始。

为什么不能从 0 开始：上一轮连接的残包可能还在网络里（旧 SYN、旧数据）；如果新连接也从同一个序号开始，接收方可能把旧包当成新连接的一部分。ISN 让新连接的序号空间避开旧包；TIME_WAIT 的 2MSL 再兜一层，但只靠它不够（服务端重启、连接被 RST 掉都可能绕过）。

32 位序号会回绕；比较时按模 2^32 算，不能直接比大小。

## ACK 号不是「到此为止」

ACK=200 不是「第 200 号及之前的都收到了」，而是「下一个期望的字节序号是 200」：200 之前的字节都收到了，200 还没收到。RFC 9293 的原话是，ACK 字段是发送方期望收到的下一个序号。

SYN 和 FIN 各占一个序号。客户端 SYN 的 seq=x，SYN 本身占掉 x，所以第一个数据字节是 x+1；服务端回 `ack=x+1`，即使它一个数据字节都没收到。FIN 同理：FIN 的 seq=N 会被确认成 N+1。

TCP 不要求每收到一个字节或一个段就立刻回 ACK。它用累积 ACK：收到 100..199 和 300..399，但 200..299 缺了，接收方仍然可以回 ACK=200；SACK 选项可以额外告诉发送方「300..399 我收到了」。延迟 ACK 也是正常行为：RFC 9293 说实现 SHOULD 支持，延迟必须小于 0.5 秒，并且至少每两个全尺寸段回一次。所以抓包里看不到「每个数据包后面跟一个 ACK」是正常的。

第三个 ACK 可以带数据：它仍然是一个 ACK，只是顺便把应用数据放在同一个段里；服务端看到 `ack=y+1` 就完成握手，数据照常交付。

## 第三个 ACK 丢了，客户端不会重发 SYN

这是最容易被「丢包就重传」带错的地方。三个段丢的地方不同，后续也不同：

| 丢的段 | 谁在等 | 谁会重传 |
| --- | --- | --- |
| 第一个 SYN | 客户端在 SYN-SENT | 客户端重传 SYN；RFC 6298 建议初始 RTO 为 1 秒，重传后翻倍 |
| SYN+ACK | 客户端在 SYN-SENT；服务端在 SYN-RECEIVED | 服务端重传 SYN+ACK；客户端如果自己的 SYN 计时器也到了，会重发 SYN |
| 第三个 ACK | 客户端已经 ESTABLISHED；服务端还在 SYN-RECEIVED | **服务端重传 SYN+ACK**；客户端再回 ACK，或它已发的数据携带 ACK |

第三个 ACK 丢了，客户端那边已经完成握手：它在发出 ACK 的瞬间进入 ESTABLISHED，可以立刻发 HTTP 请求。它不会退回 SYN-SENT，也不会重发 SYN。服务端没收到 ACK，仍然停在 SYN-RECEIVED，按自己的重传计时器再发一个 SYN+ACK。客户端收到这个重复的 SYN+ACK，会再回一个 ACK（或者它已经发出的数据段里本来就带着 ACK）。服务端收到任何一个可接受的 ACK，才进入 ESTABLISHED。

如果客户端紧接着发了数据，而数据先到了服务端，数据段里的 ACK 也会让服务端完成握手；如果数据也丢了，服务端会继续重传 SYN+ACK，客户端的数据重传也会带 ACK。

最坏的情况：服务端的 SYN+ACK 重传一直丢，客户端以为连接已建立，开始发数据；服务端已经超时把半连接清掉，没有这条连接的 TCB，可能回 RST。于是客户端看到「刚连上就断」。这也是为什么第三个 ACK 的丢失不能靠「客户端重发 SYN」解释。

这一段是 RFC 9293 状态机的标准形态；本机没装 Wireshark，我没抓到真实的重复 SYN+ACK，抓包条件写在后面。

## 半连接队列与 SYN 洪水

服务端收到 SYN 后，不是「什么都不记」。它要为这条半连接分配一个 TCB（或至少一个队列条目），回 SYN+ACK，状态置为 SYN-RECEIVED。第三个 ACK 没来，这个条目就占着位置，直到重传超时或被清理。队列有限；满了以后，不同协议栈的行为不同：有的丢新 SYN，有的回 RST，有的覆盖最老的条目（RFC 4987 明确说这是实现相关的）。所以「队列满了」不一定表现为 timeout，也可能表现为 refused。

SYN 洪水：攻击者伪造大量源地址发 SYN，服务端为每个都回 SYN+ACK、占一个半连接条目；伪造地址不会回 ACK，条目一直占着。RFC 4987 说这种攻击的目标是端主机的半连接 backlog，不是链路带宽；打满后正常用户连不上。

SYN Cookie：服务端不存半连接状态，而是把四元组、一个时间或计数器和一个密钥做哈希，算出一个值放进 SYN+ACK 的 ISN；ACK 回来时用同样的输入重算并比对。它是带密钥的哈希，不是把整条连接的信息可逆地编码进序号再还原。它缓解的是「半连接队列被占满」，不减少 SYN 到达网卡、协议栈解析和计算哈希的 CPU 或带宽开销；所以它缓解队列耗尽，但不能让洪水消失。另外，SYN Cookie 要把 MSS、窗口缩放等信息编进去才能不影响选项协商，具体实现有取舍。

其他手段：SYN cache（把半连接放哈希表，限制每个桶的数量）、增大 backlog、缩短 SYN-RECEIVED 计时器、在防火墙或负载均衡上做 SYN 代理。它们各自有代价，不是一句「开 Cookie 就完了」。

## 失败时你看到什么

| 你看到 | 可能是 | 抓包或命令该看什么 |
| --- | --- | --- |
| `Connection refused` | 目标端口没监听，目标栈回 RST；也可能是防火墙或负载均衡策略拒绝，或本机代理、协议栈拒绝 | 有没有 SYN？RST 从哪个 IP 回来？RST 的 seq 是多少？ |
| `Timeout was reached` | SYN 发出后没有任何回音：本机或云防火墙静默丢、路由黑洞、服务端半连接队列满后丢 SYN、目标关机 | 有没有重复 SYN？有没有 SYN+ACK？如果连 SYN 都没有，先查本机防火墙或代理 |
| `Network is unreachable` | 本机路由表没有去这个地址的路，或路由器回了 ICMP unreachable | `route print` 或 `Get-NetRoute`；看有没有 ICMP；先 ping 网关 |
| 连上就断，抓包看到 RST | 握手成功了，之后应用层或中间设备发 RST（协议不对、被限流、负载均衡主动断） | RST 在哪个方向、seq 是多少、RST 之前有没有应用数据 |
| `curl: (7) Couldn't connect to server` | Windows 上 curl 对连接失败的统称；具体原因要看上一行 | 上面那行是 `Connection refused` 还是别的？ |

这张表里每一项都写了「可能」，因为同一个报错不只有一个来源。`refused` 不代表包到了应用，`timeout` 也不代表服务器死了。要判断，得看包：SYN 有没有发出去、有没有 SYN+ACK、RST 从哪来。

Wireshark 过滤：`tcp.flags.syn == 1` 只能看到 SYN 和 SYN+ACK，**看不到第三个 ACK**，因为第三个 ACK 的 SYN 位是 0。正确做法是先抓 `tcp.port == 443`，找到第一条 SYN 的 `tcp.stream`，再用 `tcp.stream eq N` 看完整握手；要看 RST 用 `tcp.flags.reset == 1`，要看重传用 `tcp.analysis.retransmission`。本机没装 Wireshark，这段过滤条件是标准形态，未实跑。

如果抓包在你自己的机器上：先看有没有 SYN。如果连 SYN 都没有，问题在本机（防火墙、代理、路由）而不是服务端。看到 SYN 但没有 SYN+ACK，可能是路径丢包、云安全组、服务端 backlog 满。看到 SYN → RST，说明有人回了拒绝；RST 的源 IP 可能是目标，也可能是防火墙或负载均衡。看到 SYN → SYN+ACK → ACK，握手已经完成，后面再断就是另一个问题。

`Test-NetConnection -Port` 只给 `TcpTestSucceeded` 的 True/False，不告诉你 RST 还是超时；要区分，还是得看 `curl.exe -v` 的那一行，或者抓包。

## 跑一遍

Windows 上没有 `grep`，也没有 `/dev/null`；把这两处换掉：

```text
curl.exe -sS -v -o NUL https://example.com/ 2>&1 | Select-String -Pattern 'Connected to|ALPN|HTTP/'
```

实际输出（这台机器，2026-11-19）：

```text
* Connected to example.com (172.66.147.243) port 443 (#0)
* ALPN: offers http/1.1
* ALPN: server accepted http/1.1
* using HTTP/1.1
> GET / HTTP/1.1
< HTTP/1.1 200 OK
```

`Connected to` 这行没有时间戳；要看它什么时候出现，加 `--trace-time`：

```text
curl.exe -sS --trace-time -v --connect-timeout 5 -o NUL https://example.com/ 2>&1 | Select-String -Pattern 'Trying|Connected to'
```

```text
12:46:45.159970 *   Trying 172.66.147.243:443...
12:46:45.327511 * Connected to example.com (172.66.147.243) port 443 (#0)
```

两条时间戳相减：327511 - 159970 = 167541 微秒，约 167.5ms。这是这一次到 example.com 的 TCP 握手（加一点本机调度）。换个时间、换个网络会变，不要当常数。

用 `-w` 也能看分段，但它是累计值：`connect` 包含 DNS，`tls` 包含前面的 connect；要看差值，不要看单个绝对值。

```text
curl.exe -sS -o NUL -o NUL -w 'num_connects=%{num_connects} connect=%{time_connect} tls=%{time_appconnect} first=%{time_starttransfer}\n' https://example.com/ https://example.com/
```

实际输出：

```text
num_connects=1 connect=0.164878 tls=0.401755 first=0.544799
num_connects=0 connect=0.000000 tls=0.000000 first=0.149955
```

第一个请求 `num_connects=1`，第二个 `num_connects=0`：第二条复用了同一条连接，所以 connect 和 tls 都是 0。`first` 从 0.545s 降到 0.150s，差的是 TCP+TLS 握手，不是服务端突然变快。单次数字会变，但 `num_connects` 的 1 → 0 是结构性的。

## 握手时间与 keep-alive

先看这台机器的两组 ping（2026-11-19，Wi-Fi）：

```text
$ ping.exe -n 4 192.168.8.1
Pinging 192.168.8.1 with 32 bytes of data:
Reply from 192.168.8.1: bytes=32 time=29ms TTL=255
Reply from 192.168.8.1: bytes=32 time=46ms TTL=255
Reply from 192.168.8.1: bytes=32 time=104ms TTL=255
Reply from 192.168.8.1: bytes=32 time=7ms TTL=255

Ping statistics for 192.168.8.1:
    Packets: Sent = 4, Received = 4, Lost = 0 (0% loss),
Approximate round trip times in milli-seconds:
    Minimum = 7ms, Maximum = 104ms, Average = 46ms

$ ping.exe -n 4 example.com
Pinging example.com [172.66.147.243] with 32 bytes of data:
Reply from 172.66.147.243: bytes=32 time=133ms TTL=48
Reply from 172.66.147.243: bytes=32 time=135ms TTL=48
Reply from 172.66.147.243: bytes=32 time=146ms TTL=48
Reply from 172.66.147.243: bytes=32 time=186ms TTL=48

Ping statistics for 172.66.147.243:
    Packets: Sent = 4, Received = 4, Lost = 0 (0% loss),
Approximate round trip times in milli-seconds:
    Minimum = 133ms, Maximum = 186ms, Average = 150ms
```

同城 5ms 是假设；跨洲 150ms 也只是常见量级，不是这次实测出来的规律。这台机器到 example.com 的 ping 平均正好是 150ms，但那是一个具体目标，不是「跨洲就该 150ms」。同一个目标多跑几次也会跳，别把 5/150 当常数。

一次握手，客户端视角是 1 个 RTT：SYN 出去，SYN+ACK 回来。第三个 ACK 发出去时客户端就 ESTABLISHED，不用再等一个 RTT。服务端视角是 1.5 个 RTT：它要等第三个 ACK 到。

TTFB 里包含握手，只对新建连接成立。复用连接时，TTFB 从发请求开始算，不含 TCP/TLS 握手；所以拿 TTFB 对比两个服务时，先确认是不是都复用了连接。

keep-alive 省的不只是「少一次握手」这一点。它省掉的是：TCP 三次握手、TLS 握手（同一连接上）、慢启动重新爬升，以及相应的 RTT；连服务端为建连做的那部分计算（TLS 密钥交换、socket 初始化）也一起省了。服务端处理业务请求的 CPU 仍然要花，这部分不会因为复用连接而消失。上面的 `num_connects` 输出就能看到：第一个请求 1 条新连接，第二个 0 条；connect 和 tls 都是 0。

## 动手

- 把 `192.168.8.1` 换成你手边的网关或一台你控制的主机，先跑 `curl.exe -sS -v --connect-timeout 3 -o NUL http://<host>:443/` 和 `:80/`，看哪一个是 refused、哪一个是 timeout。不要拿别人的地址扫。
- 用 `curl.exe -sS --trace-time -v -o NUL https://example.com/ 2>&1 | Select-String -Pattern 'Trying|Connected to'` 算一次握手时间；再把同一个 URL 写两遍，加 `-w 'num_connects=%{num_connects} ...'`，看第二条是不是 0。
- 如果你装了 Wireshark，抓 `tcp.port == 443`，找到第一条 SYN 的 `tcp.stream`，再用 `tcp.stream eq N` 看第三个 ACK；顺手确认 `tcp.flags.syn == 1` 过滤会漏掉它。

## 检查一下

```quiz
{"id":"net-04-quiz","title":"三次握手三问","questions":[
 {"question":"第三个 ACK 丢了，接下来最可能发生什么？","options":["客户端退回 SYN-SENT，重发 SYN","客户端已经 ESTABLISHED，服务端停在 SYN-RECEIVED 并重发 SYN+ACK，客户端再回 ACK","双方都认为连接已建立，什么也不发生","服务端立刻发 RST，客户端收到后重连"],"answer":1,"explain":"客户端发出第三个 ACK 后就已经进入 ESTABLISHED，不会退回 SYN-SENT。服务端没收到 ACK，仍停在 SYN-RECEIVED，会重传 SYN+ACK；客户端再回 ACK，或者它已经发出的数据段携带 ACK，服务端才进入 ESTABLISHED。"},
 {"question":"客户端和服务端的初始序号（ISN）是什么关系？","options":["必须相等，否则连接建立失败","服务端沿用客户端的 ISN","由客户端指定，服务端必须接受","双方各自选自己的，不需要相同；各自确认收到对方的 ISN"],"answer":3,"explain":"握手不是让两个数相等，而是让每一方拿到对方的 ISN 并回确认。RFC 9293 的示例就是客户端 x=100、服务端 y=300，第三个段 ACK=301。"},
 {"question":"抓包里看到 ACK=200，最准确的解释是？","options":["第 200 号字节已经收到","200 号及之前的字节都收到了","下一个期望的字节序号是 200，说明 200 之前的都收到了","接收窗口是 200 字节"],"answer":2,"explain":"ACK 号是下一个期望的序号；ACK=200 表示 200 之前的字节都收到了，200 本身还没收到。"}
]}
```

## 还差的

- 没抓到第三个 ACK 丢失的真实包：本机没装 Wireshark 或 tshark，`pktmon` 也连不上驱动；这一段是按 RFC 9293 状态机写的标准形态。
- 没做服务端的半连接队列实验：没有改 `backlog`、`tcp_syn_retries`，也没有在真实服务器上打过 SYN 洪水；SYN Cookie 的代码路径没有实测，只按 RFC 4987 说明。
- 没做跨网络或跨洲的 RTT 对比：只测了本机 Wi-Fi 到网关和 example.com；5ms 不是这次实测，150ms 只是到 example.com 的平均值，不能当「跨洲」的通用基准。
- 没验证不同操作系统或负载均衡的 RST 行为：表里的可能是要在具体环境里抓包确认的，Windows、Linux、云负载均衡的默认行为不一样。
- 没讲 TCP Fast Open、MPTCP、QUIC 的握手差异；这一篇只讲经典 TCP 的三次握手。
