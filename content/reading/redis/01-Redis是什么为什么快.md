---
title: Redis 是什么，为什么快
category: redis
order: 1
minutes: 20
level: beginner
status: published
---

## 一条 PING 等了 4.80 秒

想直观看「单线程」，最省事的办法是让服务端哪也不去。我起了一个用完就扔的实例（Redis 8.6.2，Windows 构建，`127.0.0.1:6391`，`save ""`、`appendonly no`），照着一篇旧教程敲：

```text
$ redis-cli -p 6391 debug sleep 5
(error) ERR DEBUG command not allowed. If the enable-debug-command option is set to "local",
you can run it from a local connection, otherwise you need to set this option in the
configuration file, and then restart the server.
```

`DEBUG` 默认是关的（8.6.2 实测，旧文里不加任何配置就能用）。给启动参数加上 `--enable-debug-command yes` 重启之后，两个连接：A 执行 `DEBUG SLEEP 5`，200 毫秒后 B 发 `PING`。

```text
PING during DEBUG SLEEP 5 -> PONG after 4.80s
```

`PING` 在服务端自己的账上是 1.6 微秒（`cmdstat_ping` 的 `usec_per_call`）。这 4.8 秒既不是网络慢，也不是 PING 变复杂了：主线程在 `nanosleep` 里，它读不到 B 发来的字节，也回不出一个字。

由此得到本文唯一要记住的执行模型：

```sketch
{
  "title": "主线程一遍一遍走的就是这四步",
  "rows": [
    [
      { "id": "read", "title": "读命令", "sub": "问内核哪个连接有数据", "tone": "blue" },
      { "id": "exec", "title": "执行", "sub": "一次一条，中间不放人进来", "tone": "blue" },
      { "id": "out", "title": "写进输出缓冲区", "sub": "剩下的发送交给内核", "tone": "green" },
      { "id": "next", "title": "下一条", "sub": "等的人只能继续等", "tone": "green" }
    ]
  ],
  "arrows": []
}
```

「单线程」说的是执行命令的这条主线程。进程里还有别的线程：`UNLINK` 的延迟释放、`appendfsync everysec` 时的后台 `fsync`、RDB 落盘用的 fork 子进程——它们搬字节、刷盘、释放内存，不执行你的命令。网络读写默认也在主线程：这个实例上 `CONFIG GET io-threads` 是 `1`，`INFO server` 里 `io_threads_active` 是 `0`。6.0 起那套 I/O 线程**要显式配出来才有**，不是默认并行。

## 客户端等 0.103 毫秒，服务端记了 0.3～1.5 微秒

单客户端、不开流水线，打 2000 次 GET（`redis-benchmark -p 6391 -n 2000 -c 1 -t get`）：

```text
throughput summary: 6250.00 requests per second
latency summary (msec):
        avg       min       p50       p95       p99       max
      0.120     0.056     0.103     0.231     0.343     0.823
```

再去看服务端自己的账本，同一条命令：

```text
$ redis-cli -p 6391 info commandstats | grep cmdstat_get
cmdstat_get:calls=3000,usec=4539,usec_per_call=1.51
```

我几次量到 0.35、0.66、1.51 微秒。客户端 p50 是 0.103 毫秒（103 微秒），差了两个数量级：**这条 GET 有 98% 以上的时间不在 Redis 里，而在「一次请求、一次答复」这条路上。**

那么内存买到的到底是什么？不是让每条命令本身变快——0.66 微秒这个量级，内存里的哈希表命中已经是主要成本，再优化也省不下多少。内存省掉的是另一类操作：需要把数据从磁盘翻出来的那些。Redis 是个把键值都摊在内存里的数据库（值可以是字符串、hash、列表这类结构），这个实例的 `GET` 只碰内存，不碰磁盘。**我没有在同一台机器上装一个磁盘数据库做同条件 A/B**，所以「省掉磁盘等待」是设计事实加这个 0.3～1.5 微秒的执行量级，不是这组数字证明的。

代价是内存。灌 20 万个 `user:<n>` → `v` 的小键，`used_memory` 从 1,146,246 涨到 14,241,646，**摊到每个键 65.5 字节**（键名、值、dict 表项、分配器开销全算在内）。这就是「全放内存」在账单上的样子，也是第 3 篇讲淘汰策略的起点。

## 50 个客户端之后，p50 从 0.103 变成 1.447 毫秒

```text
$ redis-benchmark -p 6391 -n 100000 -c 50 -t get
throughput summary: 22512.38 requests per second
latency summary (msec):
        avg       min       p50       p95       p99       max
      1.543     0.248     1.447     2.647     3.423     8.015
```

命令没变慢，是排队变长了。50 条连接同时有请求在等那一条主线程：总吞吐从 6,250 涨到 22,512 次/秒，单条 p50 从 0.103 涨到 1.447 毫秒。同一个机制，两个读法——**它靠串行换来简单和原子性，付出的是尾延迟。**

一个线程怎么伺候 50 条（线上是几千条）连接？把连接交给内核，用多路复用接口问「谁有数据」，只处理就绪的那几条。这台机器的 `INFO server` 里写的是：

```text
multiplexing_api:select
```

不是 `epoll`——那是 Linux 构建。这不只是名字不同：`select` 有描述符数量上限，每次调用还要把集合拷进内核。所以本文所有吞吐和延迟都只属于这台机器；换到 Linux，数字会变，机制不会。

把请求攒成批还能再提一截：`-P 16` 时总吞吐 231,750 次/秒，摊到每条 4.3 微秒。省的是系统调用和往返次数，不是命令执行——命令仍是一条一条跑的，`INCR` 的原子性、`MULTI` 的隔离都不受影响。

## 同一份数据：DEL 117 毫秒，UNLINK 0.3 毫秒

上面那张延迟表看着挺稳，因为每条命令都不干活。让某条命令真干一次活，另一条连接在旁边 `PING` 计时：

| 我做的事（同一实例，20 万条数据） | 调用方等 | 旁边 PING 等了 | 服务端记的执行时间 | 该换成 |
| --- | --- | --- | --- | --- |
| `DEL big:del`（20 万字段的 hash） | 117.4 ms | 112.1 ms | 105.8 ms | `UNLINK` |
| `UNLINK big:unlink`（同样的 hash） | 0.3 ms | 0.6 ms | 0.0125 ms | — |
| `KEYS u:*`（20 万个 key） | 233.5 ms | 53.0 ms | 67.8 ms | `SCAN ... COUNT 1000` |
| 200 轮 `SCAN COUNT 1000`（覆盖同样的 key） | 合计 398.5 ms | 单次最坏 5.2 ms | 1.07 ms 每轮 | — |

前三行各说一件事，第四行是一笔取舍：

- `DEL` 真的挡人。调用方 117 毫秒，旁边那条 `PING` 也等了 112 毫秒：释放 20 万个字段的内存，是在主线程里一个字段一个字段做完的。
- `UNLINK` 只登记「这个对象待会儿删」，释放交给后台，0.3 毫秒就返回了。内存稍后才掉下去——我量到 `used_memory` 在 0.5 秒内从 18.8 MB 降到 0.96 MB。这段时间里内存还没还回来。
- `KEYS` 的 233 毫秒不能全算在命令头上。服务端执行记 67.8 毫秒，旁边 `PING` 等了 53 毫秒（我在 20 毫秒后才发它），剩下 160 多毫秒是在把 20 万个 key 的回包传回给调用方。**执行期挡所有人，传回包只挡那一个连接。**

第四行是取舍，不是优化：`SCAN` 把总时间从 233 毫秒拉长到 398 毫秒，但把最坏的单次停顿从 67.8 毫秒压到 5.2 毫秒。分批换的是「不出现一个几百毫秒的空洞」，不是「更快」。

判断慢在哪一层，按这个顺序量，别猜：

| 现象 | 先量什么 | 对上了说明什么 |
| --- | --- | --- |
| 所有请求都慢，`PING` 也慢 | 另开一条连接 `PING`；`SLOWLOG GET 10` | 主线程被一条命令占住：`KEYS`、大 `DEL`、长 Lua |
| 只有一个接口慢，`PING` 正常 | `INFO commandstats` 里该命令的 `usec_per_call` | 慢在这条命令本身，或它返回的数据量 |
| 平均正常，p99 有尖刺 | `SLOWLOG GET 50`，对照 `INFO commandstats` | 偶发的 O(N) 命令或大 key，不是容量不够 |
| 服务端 `usec_per_call` 很低，客户端还是慢 | 客户端到服务器的往返、连接数、有没有流水线 | 时间在网络上，不在 Redis 里 |

`slowlog-log-slower-than` 默认 10000 微秒，也就是 10 毫秒（这个实例上 `CONFIG GET` 确认过）。我让服务端睡 200 毫秒，慢日志里就有这一条：

```text
$ redis-cli -p 6391 slowlog get 1
1) 1) (integer) 21
   2) (integer) 1795074422
   3) (integer) 202841
   4) 1) "debug"
      2) "sleep"
      3) "0.2"
   5) "127.0.0.1:53461"
```

第三项是耗时，单位微秒，202841 就是 202.8 毫秒——比 200 毫秒的睡眠多一点，是命令收尾的时间也算在里面。

## 十条连接同时 INCR，结果是 10000

```text
10 threads x 1000 INCR in 0.163s -> counter=10000
```

`INCR` 没做什么特殊处理，它只是把「读、加、写」放进同一条命令，中间没人能插进来。单条命令的原子性来自串行执行，不来自锁。

把同样的逻辑拆成两条命令就不是这样了。两个连接各自「先 GET，加一，再 SET」：

```text
two threads GET+SET (both read before write) -> nv=11
```

我这一跑丢了 1 次（11 而不是 12），丢不丢取决于两个连接谁先谁后——这就是竞态：**单线程保证单条命令不被打断，不保证你的两条命令之间没有别人。**

`MULTI/EXEC` 把一组命令攒起来，执行期间不插队：

```text
SET str abc            -> OK
MULTI                  -> OK
INCR str               -> QUEUED
SET k1 v1              -> QUEUED
EXEC                   -> 1) (error) ERR value is not an integer or out of range
                          2) OK
GET k1                 -> "v1"
```

`INCR` 报了错，`SET k1` 照样生效——**EXEC 不回滚**。能挡住的只有「排队阶段就被拒」这一类（另有一种是 `WATCH` 发现键被改过时，让 `EXEC` 直接放弃）：

```text
MULTI                  -> OK
BOGUSCMD               -> (error) ERR unknown command 'BOGUSCMD'
SET k2 v2              -> QUEUED
EXEC                   -> (error) EXECABORT Transaction discarded because of previous errors.
GET k2                 -> (nil)
```

所以准确的说法是：`MULTI/EXEC` 给的是隔离（执行期间别人进不来），不是原子性（要么全做要么全不做）。要「要么全做」得自己写补偿；要修上面那种丢更新，标准做法是换成 `INCR`、或者 `WATCH` 加重试、或者把判断和写入放进一个 Lua 脚本——脚本里同样不许别人插队，但脚本里的前一半命令出错不会撤销前一半。

## 跑一遍

Windows 上用 PowerShell 起临时实例，别去碰已经在跑的那个 6379：

```bash
redis-server --port 6391 --bind 127.0.0.1 --save "" --appendonly no --enable-debug-command yes
```

```bash
# 另开一个窗口确认
redis-cli -p 6391 ping
# PONG
```

```bash
# 窗口 A：占住主线程 5 秒
redis-cli -p 6391 debug sleep 5
```

```bash
# 窗口 B：同时计时（bash 用 time，PowerShell 用 Measure-Command）
time redis-cli -p 6391 ping
# real    0m4.8xxs
```

```bash
# 测延迟分布，再对一遍服务端账本
redis-benchmark -p 6391 -n 2000 -c 1 -t get
redis-cli -p 6391 info commandstats | grep cmdstat_get
```

```bash
# 灌 20 万个 key，对比 KEYS 与 SCAN
seq 1 200000 | sed 's/^/SET u:/' | sed 's/$/ v/' | redis-cli -p 6391 --pipe
time redis-cli -p 6391 keys 'u:*' > /dev/null
# 这次计时含 redis-cli 启动和输出，比文中的 233 ms 大；要看纯往返就自己计
```

```bash
# 收尾：别把这个实例留着
redis-cli -p 6391 shutdown nosave
```

## 动手

1. 在自己的机器上量「客户端 vs 服务端」的差：`redis-benchmark -n 2000 -c 1 -t get` 拿 p50，再拿 `INFO commandstats` 的 `usec_per_call` 除一下。服务端占比大的，瓶颈在那条命令本身；像本文这样只占百分之一二的，去查网络、连接数和有没有流水线。
2. 造一个 20 万字段的 hash，`DEL` 一次、`UNLINK` 一次，中间用另一条连接 `PING` 计时。你会看到 `UNLINK` 之后 `used_memory` 还要过一会儿才降。
3. 翻自己的代码，找一处 `KEYS` 或大 `DEL`。改成分批或异步后，量「最坏单次停顿」而不是平均值——平均值恰好看不出这种空洞。

## 检查一下

```quiz
{"id":"redis-01-quiz","title":"三道量出来的题","questions":[
 {"question":"DEBUG SLEEP 5 执行期间，另一条连接的 PING 等了 4.80 秒才回。最直接的解释是？","options":["网络拥塞，PING 被丢包后重发","PING 命令在这一版里变慢了","主线程正在执行那条睡眠命令，期间读不到也回不了别的连接","Redis 把两个连接合并到了同一个 socket"],"answer":2,"explain":"执行命令的只有一条主线程；它在睡眠里时，别的连接发来的字节只能等它回来。PING 本身在服务端只记 1.6 微秒。"},
 {"question":"同一实例上单客户端 GET 的 p50 是 0.103 毫秒，而 cmdstat_get 的 usec_per_call 只有 0.3 到 1.5 微秒。这组数字说明什么？","options":["键空间查找本身只占百分之一二，时间花在一次请求往返上","服务端统计漏掉了真正的查找耗时","客户端时钟不准，p50 被放大了","GET 的回复太小，服务端没有计入统计"],"answer":0,"explain":"两者差两个数量级，说明开销不在 Redis 内部；要把服务端时间单独拎出来，只能读 commandstats。"},
 {"question":"两个连接各自执行「GET 后加一再 SET」，结果偶尔少加一次。正确的修法是？","options":["给这个 key 加过期时间","打开 AOF 持久化就不会丢","用 MULTI 把 GET 和 SET 包起来，事务一定能修好","换成一条 INCR，或用 WATCH 加重试，或放进 Lua 脚本"],"answer":3,"explain":"单条命令的原子性来自串行执行，两条命令之间有窗口。普通 MULTI 里 SET 的参数在排队前就算好了，仍会写回旧值。"}
]}
```

## 还差的

1. 没做同条件 A/B：我没有在这台机器上装一个磁盘数据库跑同样的查询，所以「内存省掉磁盘等待」是设计事实，不是本文这组数字证明的。
2. 只在一台 Windows（8.6.2，`multiplexing_api:select`）上量过。Linux/epoll 下的吞吐、尾延迟、fd 上限都没测，文中的 rps 不能直接搬到生产。
3. 没开过 I/O 线程（`io-threads` 默认 1）。开 2 或 4 之后吞吐和 p99 怎么变、它和「命令仍串行」的边界在哪，我没有数据。
4. 没测持久化开着时的表现。`appendfsync everysec` 的后台刷盘、RDB fork 之后的写时复制，会不会在 p99 上留下尖刺，我不知道。
5. 只测了单实例。集群、代理、跨槽命令下 `MULTI`、`KEYS`、`UNLINK` 的语义与阻塞行为都不一样，本文的结论不覆盖那些部署形态。
