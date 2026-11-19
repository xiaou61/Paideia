---
title: 持久化：RDB 与 AOF
category: redis
order: 4
minutes: 20
level: intermediate
status: published
---

## 强杀之后，那条 SET 还在

问题先定成一个能验的：一条已经回 `+OK` 的写入，什么时候会没？

我在本机 8.6.2 上起了一个临时实例（`127.0.0.1:6415`，`--appendonly yes --appenddirname aofdir --appendfsync no --save ""`），写一个键，然后强杀进程：

```bash
redis-cli -p 6415 CONFIG GET appendfsync
redis-cli -p 6415 SET last_write "survive?"
```

```text
1) "appendfsync"
2) "no"
OK
```

0.4 秒后看增量文件，67 字节，内容已经是这条命令：

```text
*2
$6
SELECT
$1
0
*3
$3
SET
$10
last_write
$8
survive?
```

然后 `taskkill /F /PID 136052`（Windows 上的 `kill -9`，没有收尾），用同一个目录、同一组参数重启：

```bash
redis-cli -p 6415 GET last_write
```

```text
"survive?"
```

我原来以为这里会丢。`appendfsync no` 不 fsync、进程被强杀，按「no 最不安全」的说法，这应该正好是丢数据的现场。

不 fsync 不等于不写。`write()` 已经把 67 字节交给内核页缓存了，进程死掉不影响这份缓存，下一个进程打开同一个文件就读得到。所以这一节的结论很具体：

**杀进程杀不掉数据，丢掉页缓存才丢数据** —— 断电、内核崩、虚拟机被宿主直接切。

这句话把 `everysec` 那 1 秒重新定位了：它保护的不是「进程被 kill」，是「机器没了」。这两个风险在测试环境和线上不是一回事，用前者去验证后者会得到错误的安全感。

（我在这台机器上反复起停了好几个临时实例，端口是 6411/6412/6413/6415，参数都是 `--save ""` 加上按需的 `--appendonly`，用完 `SHUTDOWN NOSAVE`。6379 上有一个不归我的实例，我没碰。)

## AOF 早就不是一个文件了

我原本准备写「AOF 文件的开头是一份 RDB 快照，后面接增量命令」。开目录一看不是：

```text
appendonly.aof.2.base.rdb    119
appendonly.aof.2.incr.aof      0
appendonly.aof.manifest      105
```

manifest 里是清单：

```text
file appendonly.aof.2.base.rdb seq 2 type b
file appendonly.aof.2.incr.aof seq 2 type i startoffset 5004
```

base 文件头 6 个字节是 `52 45 44 49 53 30`，也就是 ASCII 的 `REDIS0` —— 确实是 RDB 格式，但它是**一个独立文件**，不是某个大文件的开头。Redis 7.0 起 AOF 换成多文件形态：一个目录（`appenddirname`，默认 `appendonlydir`）、一个 manifest 记录清单、一个 base 装全量、若干 incr 接增量。

「单个 `appendonly.aof`，前面 RDB 后面接命令」是 4.0–6.2 的形态。8.x 的 `appendonlydir` 里根本没有 `appendonly.aof` 这个文件，按老路径去找会扑空。

`aof-use-rdb-preamble` 这个名字也在误导：它管的不是「文件开头」，是 base 用哪种格式。把这个开关关掉再重写：

```bash
redis-cli -p 6413 CONFIG SET aof-use-rdb-preamble no
redis-cli -p 6413 BGREWRITEAOF
```

```text
appendonly.aof.3.base.aof    211
appendonly.aof.3.incr.aof      0
appendonly.aof.manifest      106
```

base 的第一行从 `REDIS0` 变成了 `*2`（RESP 命令），文件名也从 `.base.rdb` 变成 `.base.aof`。同一个开关，决定的是 base 的编码方式。

| 文件 | 装的是什么 | 什么时候变 |
| --- | --- | --- |
| `*.base.rdb` / `*.base.aof` | 重写那一刻的全量；默认 RDB 二进制，关掉 preamble 则是命令文本 | 每次 `BGREWRITEAOF` 生成新 seq |
| `*.incr.aof` | 重写之后追加的命令 | 一直长；自动重写触发时被并进新 base |
| `*.manifest` | 上面这些文件的清单 | 每次 base 换代时改写 |

这也解释了另一个老说法「AOF 是纯文本，误删还能从文件里捞数据」：默认配置下 base 是二进制 RDB，捞得出来的是 incr 那一段。要全文可读，得先接受 base 变大。

## 快照的代价在 fork 那一下

空实例什么都不改，默认长这样：

```bash
redis-cli -p 6412 CONFIG GET save
redis-cli -p 6412 CONFIG GET appendonly
redis-cli -p 6412 CONFIG GET appendfsync
```

```text
1) "save"
2) "3600 1 300 100 60 10000"
1) "appendonly"
2) "no"
1) "appendfsync"
2) "everysec"
```

`appendonly no` 是默认值 —— AOF 要显式开；`appendfsync everysec` 是开了 AOF 之后才起作用的那一档。

**默认配置只开 RDB，最坏能丢接近 1 小时。** 三条 save 规则里最长的一条是「3600 秒内至少 1 次改动」，一个每分钟写 5 条的实例永远触发不了另外两条（300 秒那条要 100 次改动，60 秒那条要 10000 次）。「最多丢 1 分钟」是写入足够密集时的上限，不是默认配置的下限。

快照本身多贵，量一下：

```bash
redis-benchmark -p 6411 -t set -n 300000 -c 50 -r 300000 -q
redis-cli -p 6411 DBSIZE
redis-cli -p 6411 INFO persistence | grep rdb_changes_since_last_save
redis-cli -p 6411 BGSAVE
redis-cli -p 6411 INFO stats | grep latest_fork_usec
```

```text
SET: 14882.43 requests per second, p50=2.407 msec
(integer) 189986
rdb_changes_since_last_save:300000
Background saving started
latest_fork_usec:70923
```

两个数值得记：

- 30 万次写入落在 189986 个键上，`rdb_changes_since_last_save` 读出来是 300000。它是**写操作次数**（脏计数器），不是键数。当「现在宕机大概丢多少」用会偏高，当「距上次干净快照多远」用是对的。
- `latest_fork_usec:70923`，71 毫秒。13.05 MB 数据 fork 一次要 71 ms，`dump.rdb` 落下来 4179789 字节。

这个 71 ms 不能外推。同一实例重复 BGSAVE 我读到过 121960 / 86245 / 64627 / 182258 微秒，波动四倍；更重要的是这台机器的 `INFO server` 里 `os:` 是 `MSYS_NT-10.0-22000`，MSYS 的 fork 是在用户态复制进程，和 Linux 上写时复制的 fork 不是一回事。Linux 上是另一套实现，我没有 Linux 机器，不猜它的值。

可外推的是趋势 —— 同一个实例把数据写到 23.70 MB 再 BGSAVE，读到 92744 和 182258 微秒。fork 的时间随常驻内存涨，所以大实例的痛点在 fork 那一瞬，而 `rdb_last_bgsave_time_sec`（这里 0 到 1 秒）是子进程写文件的时间，两件事别混。

还有一件我在这台机器上**测不到**的：`rdb_last_cow_size` 和 `current_cow_peak` 全程是 0，BGSAVE 期间我用 Lua 覆盖了 8 万个已有键，这两个数仍然是 0。所以「20 GB 实例 BGSAVE 期间占 40 GB」这类写时复制的数字，这篇不给 —— 我拿不到，不编。

## 三档 fsync 的实际代价

同一个实例，改完 `appendfsync` 立刻用 benchmark 量（20 个并发客户端，值 3 字节，本机普通笔记本磁盘，不是专门的压测机）：

```bash
redis-cli -p 6413 CONFIG SET appendfsync no
redis-benchmark -p 6413 -t set -n 20000 -c 20 -q
redis-cli -p 6413 CONFIG SET appendfsync everysec
redis-benchmark -p 6413 -t set -n 20000 -c 20 -q
redis-cli -p 6413 CONFIG SET appendfsync always
redis-benchmark -p 6413 -t set -n 20000 -c 20 -q
```

```text
appendfsync   SET rps     p50
no            9818.36     1.327 msec
everysec      9514.75     1.351 msec
always        7366.48     2.327 msec
```

我预期 `always` 会掉一个数量级，实际只掉约 25%。原因我不敢下定论：Windows 上的 fsync 走 `FlushFileBuffers`，20 个并发客户端也可能把单次 fsync 摊薄了。能确定的是这台机器上「always 必然慢十倍」不成立，别拿我这张表去估云盘的写入能力。

记的是它护的是什么：

| 配置 | 已应答的写入最多丢多久 | 前提 / 代价 |
| --- | --- | --- |
| `always` | 最多丢正在写的那一条 | 每条命令都 fsync（文档口径）；本机实测只慢 25%，机械盘和网络盘不是这个数 |
| `everysec` | 约 1 秒 | 默认值；每秒 fsync 一次，丢的是上一个 fsync 之后的写入 |
| `no` | 不确定；Linux 上 `vm.dirty_expire_centisecs` 默认 3000 厘秒（30 秒） | 那是内核参数的默认值，不是 Redis 的承诺 |
| 只开 RDB（默认 save） | 最坏接近 1 小时 | 低写入时只有 `3600 1` 那条规则会触发 |

`everysec` 那 1 秒我没法在本机复现 —— 要复现得断电或者让内核崩，`kill -9` 不行（第一节那 67 字节就是证据）。

## 三个运行时的坑

**`CONFIG SET dir` 和 `dbfilename` 在 8.6.2 里改不了。**

```bash
redis-cli -p 6411 CONFIG SET dir "C:\__no_such_dir__"
```

```text
(error) ERR CONFIG SET failed (possibly related to argument 'dir') - can't set protected config
```

`CONFIG GET enable-protected-configs` 是 `no`，这两个键属于受保护配置。老教程里「先 `CONFIG SET dir` 换个盘再 BGSAVE」的写法在 8.x 直接报错，要么写进配置文件重启，要么启动时加 `--enable-protected-configs yes`。我本来想用这条路造一个失败的 BGSAVE 来验「快照坏了就拒写」，被这个报错挡住了。

**`CONFIG SET` 的持久化只在本进程内。** `CONFIG SET appendonly yes` 开了 AOF、`CONFIG SET save ""` 关掉 RDB，都不会写回配置文件。重启后按配置文件来：AOF 回到 `no`，数据退回上一个 RDB 快照。以为「开关拨一次就永久生效」会在这里丢数据。

**快照或 AOF 落盘失败时，默认拒写。**

```bash
redis-cli -p 6412 CONFIG GET stop-writes-on-bgsave-error
```

```text
1) "stop-writes-on-bgsave-error"
2) "yes"
```

上次 BGSAVE 失败后，写命令会被拒（读还能用），`INFO persistence` 里 `rdb_last_bgsave_status` 变 `err`。AOF 那条路径同理，看 `aof_last_write_status`。这两条我只有配置默认值，拒写的实际表现按官方文档写，没在本机复现。

顺手记一下 8.6.2 里 `INFO persistence` 能查什么：`rdb_saves` / `rdb_saves_consecutive_failures` / `rdb_last_cow_size` / `aof_rewrites_consecutive_failures` / `current_cow_peak`。老文章常引用的 `aof_delayed_fsync` 在这个版本的输出里已经没有了，照抄 grep 会一无所获。

| 你敲的命令 | 结果 | 该看哪 |
| --- | --- | --- |
| `CONFIG SET appendonly yes` | 本进程开 AOF，会触发一次重写 | 重启后是否还在 —— 取决于配置文件 |
| `CONFIG SET dir ...` | 报 `can't set protected config` | `CONFIG GET enable-protected-configs` |
| `BGSAVE` | fork 阻塞主线程一次，子进程写文件 | `latest_fork_usec`（fork）与 `rdb_last_bgsave_time_sec`（写文件） |
| `kill -9` 后重启 | 已应答的写入还在（只要机器没断电） | `GET` 一下就知道了 |
| `INFO persistence` | 两次快照之间的脏计数 | `rdb_changes_since_last_save` 是写次数，不是键数 |

## 检查一下

```quiz
{"id":"redis-04-quiz","title":"持久化三问","questions":[
 {"question":"默认配置（save 3600 1 300 100 60 10000）下，一个每分钟只写 5 条的实例只开 RDB，宕机最多丢多少数据？","options":["最多 1 分钟","最多 5 分钟","最多接近 1 小时","不丢"],"answer":2,"explain":"三条规则里只有 3600 秒 1 次改动那条能被这个写入量触发；60 秒那条要 10000 次改动，300 秒那条要 100 次，都到不了。"},
 {"question":"appendfsync no 的实例里，一条 SET 已经回了 OK，随后进程被 taskkill /F 强杀。重启后这条数据最可能是什么状态？","options":["已经丢了，因为 no 不 fsync","还在：write 已经把命令交给内核页缓存，杀进程不影响它","要等下一次 fsync 才可能回来"],"answer":1,"explain":"no 只是不 fsync。命令已经写进文件、落在内核页缓存里，进程死亡不会清空它；会丢的是断电或内核崩溃。"},
 {"question":"在 8.6.2 上执行 CONFIG SET appendonly yes 之后，appendonlydir 目录里默认是什么？","options":["base.rdb、incr.aof 和 manifest 三类文件","单个 appendonly.aof，开头是 RDB 快照，后面接命令","只有一个纯文本的 .aof 文件"],"answer":0,"explain":"7.0 起 AOF 是多文件：manifest 记录清单，base 默认是 RDB 二进制，重写之后的增量进 incr 文件。"}
]}
```

## 还差的

- **`everysec` 那 1 秒没实测。** 要丢页缓存才能看到，我没有能硬断电的机器；文中那条 1 秒是按官方文档写的，第一节只证明了 `kill -9` 不丢。
- **Linux 上的 fork 成本没有数。** 这台机器 `os:` 是 MSYS，fork 是用户态复制，文中 64–182 ms 的绝对值只对这台机器成立，Linux 生产实例上的 `latest_fork_usec` 得自己量。
- **写时复制的内存增量没测到。** `rdb_last_cow_size` 与 `current_cow_peak` 在这个构建上恒为 0，所以「BGSAVE 期间内存翻倍」没有本机证据。
- **落盘失败后的拒写没复现。** `dir`/`dbfilename` 是受保护配置，我造不出失败的 BGSAVE；AOF 写满磁盘的表现同理，只有 `stop-writes-on-bgsave-error yes` 这个默认值是实测的。
- 主从与哨兵下的持久化没展开：切主时 AOF 要不要关、从节点加载的是谁的快照、网络分区后哪一端的数据会覆盖另一端，留给第 7 篇。
