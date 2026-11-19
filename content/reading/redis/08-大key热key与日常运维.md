---
title: 大 key、热 key 与慢命令：线上问题怎么查
category: redis
order: 8
minutes: 20
level: intermediate
status: published
---

## `--bigkeys` 的头名写着 32 字节

我起了一个用完就扔的实例（Redis 8.6.2，Windows 构建，`127.0.0.1:6399`，`save ""`、`appendonly no`，`redis_mode:standalone`），灌进两样东西：一个 10 万字段的 hash `session:all`（每个字段是一份会话 JSON，值约 80 字节），和 20 万个 `k:<n>` 小键。`DBSIZE` 是 200001。

```bash
redis-cli -p 6399 --bigkeys
```

尾部摘要（原样）：

```text
Keys sampled: 200001

-------- summary -------

Total key length in bytes is 1488904 (avg len 7.44)

Biggest   hash found "session:all" has 100000 fields
Biggest string found "k:199966" has 32 bytes

0 lists with 0 items (00.00% of keys, avg size 0.00)
1 hashs with 100000 fields (00.00% of keys, avg size 100000.00)
0 streams with 0 entries (00.00% of keys, avg size 0.00)
200000 strings with 6400000 bytes (100.00% of keys, avg size 32.00)
0 sets with 0 members (00.00% of keys, avg size 0.00)
0 zsets with 0 members (00.00% of keys, avg size 0.00)
```

我当时对这张榜的理解是「占内存最多的 key 排第一」，所以榜一就是凶手。这个理解是错的，两个原因：

**一，它的单位不统一。** hash 那行报的是字段数（`100000 fields`，来自 `HLEN`），string 那行报的是字节数（`32 bytes`，来自 `STRLEN`）。两把尺子并排放在一起，谁大谁小根本无从比较。一个 13 MB 的 hash 在这张摘要里贡献的字节数是 0——只有上面那行「Total key length in bytes」，而它算的是**键名**长度（我核过：200001 个键、平均 7.44 字节，对得上 1488904）。

**二，每种类型只留最大的一个。** 它一边扫一边滚动更新，最后每个类型只剩一个头名。你的 5 个 8 万字段的 hash 扫过去，榜上只有最大的那一个，第二名不会出现。

「大 key 危险」这句话本身没错，但判据不该是绝对尺寸。常被引用的那套「String 超过 10 KB、集合超过 5000 个元素就算大 key」，我没找到能对上慢日志的出处，而且它跟真正的症状对不上：卡人的不是「这个 key 有 13 MB」，而是「某条命令**一次**要吐 13 MB 出去」。判据应该是后者。

## 它走了 19266 次往返，服务端只干了 0.38 秒

`--bigkeys` 慢不慢、安不安全，看它内部发了什么最清楚。同一次运行前后 `CONFIG RESETSTAT`，然后读账本：

```text
cmdstat_scan:calls=19266,usec=201154,usec_per_call=10.44
cmdstat_type:calls=200001,usec=85797,usec_per_call=0.43
cmdstat_strlen:calls=200000,usec=96127,usec_per_call=0.48
cmdstat_hlen:calls=1,usec=1,usec_per_call=1.00
```

一次 `--bigkeys` = 19266 次 `SCAN` + 每个 key 一次 `TYPE` + 一次 `STRLEN`（或者 `HLEN`/`LLEN`/`SCARD`，取决于类型）。服务端把它们加完是 201 + 86 + 96 ≈ 383 毫秒，而墙上量到 7936 毫秒。**八成以上的时间不是在算，是在等：19266 次来回，一趟一趟问。**

这台机器的 `multiplexing_api` 是 `select`（第 01 篇提过），往返成本被放大了；换到 Linux/epoll 墙钟会短不少，但那 383 毫秒的服务端账不变。顺带说清一件事：`--bigkeys` 不是一条 O(N) 的阻塞命令，它自己就走 `SCAN` 分批，所以它不像 `KEYS` 那样一卡到底——但它仍然把每个 key 都摸了一遍，`-i 0.1` 可以让它每 100 轮 `SCAN` 停 0.1 秒，给业务让路。**别在业务高峰跑**，理由不是它会锁死，而是这 8 秒里它一直在和你抢 `TYPE`、`STRLEN` 的调用。

## 13 MB 藏在一个没有字节数的榜里

换成量字节的工具，答案立刻出来：

```text
$ redis-cli -p 6399 memory usage session:all
(integer) 13326450
```

13,326,450 字节。再看一眼分布（`--keystats`，它会给出每个 key 的字节数和百分位）：

```bash
redis-cli -p 6399 --keystats
```

```text
--- Top size per type ---
hash       session:all is 12.71M
string     k:199966 is 71B

Key size Percentile Total keys
-------- ---------- -----------
     66B    0.0000%          10
     71B   50.0000%      200000
  12.71M  100.0000%      200001
Note: 0.01% size precision, Mean: 137B, StdDeviation 29.10K
```

注意最后那行：平均 137 字节，标准差 29.10K。这个「平均」被 20 万个小键拖成了个位数百分比，而站在 100% 那一档的 12.71M 才是要抓的人。**一张给平均值的容量表会把大 key 藏起来，一张给百分位的表不会。** 代价是它慢：200001 个 key，每个都要发一次 `MEMORY USAGE`，墙上 14192 毫秒。

`MEMORY USAGE` 报的是这个 key 连同键名、值对象和分配开销一共占的字节，不是你塞进去的值多大。验法：同一个值 `hello`，键名 9 个字符时返回 45，键名换成 80 个字符返回 116——差的 71 正好是多出来的键名。

## 每字段 133 字节，这个数是编码算出来的

`session:all` 13,326,450 字节 ÷ 10 万个字段 = 每字段 133 字节，而字段里的值只有 80 字节。多出来的部分不是浪费，是编码的账。同一套造法，只改字段数和值的长度：

| 同一个 hash | `OBJECT ENCODING` | `MEMORY USAGE` | 每字段 |
| --- | --- | --- | --- |
| 512 字段 × 10 字节值 | `listpack` | 9656 | 18.9 字节 |
| 513 字段 × 10 字节值 | `hashtable` | 31254 | 60.9 字节 |
| 512 字段 × 65 字节值 | `hashtable` | 55274 | 108 字节 |
| 10 万字段 × 约 80 字节值 | `hashtable` | 13326450 | 133 字节 |

第一行到第二行只多了一个字段，成本翻 3.2 倍：编码从 `listpack` 掉到 `hashtable`，每字段的固定开销（一个 dictEntry、一个 SDS 头、一次独立分配）全冒出来了。阈值本身第 02 篇量过——这台机器 `CONFIG GET hash-max-listpack-entries` 是 512、`hash-max-listpack-value` 是 64，512 个字段还是 `listpack`，513 个才翻。

所以估容量别拿「值多大」当字节数。要问的是「这台机器上这个形态的每字段成本是多少」：`MEMORY USAGE` 除以元素数，拿你自己的数据量一遍，比背数字可靠。

## 那个 38 毫秒的洞，慢日志里写着

`session:all` 一次取全量：

```text
$ redis-cli -p 6399 hgetall session:all > /dev/null
$ redis-cli -p 6399 slowlog get 1
1) 1) (integer) 10
   2) (integer) 1795090949
   3) (integer) 37979
   4) 1) "hgetall"
      2) "session:all"
   5) "127.0.0.1:37661"
   6) ""
```

六个位置：自增序号、发生的 unix 时间、**执行耗时（微秒）**、命令与参数、客户端地址、客户端名（没设过名字就是空串）。

37979 微秒 = 38.0 毫秒。这段时间主线程不做别的——机制第 01 篇用 `DEBUG SLEEP` 加旁边那条 `PING` 量过，这里不重复，只说一个交叉验证：同一个数在 `INFO commandstats` 里也是 37979（`cmdstat_hgetall` 的 `usec`），两个账本记的是同一段执行时间，都不含网络。把 13 MB 回包传回客户端是另一段，客户端这边量到 296 毫秒——**执行期挡所有人，回包只挡那一条连接**，这条界线第 01 篇划的，我这里只是又撞到一次。

参数就那么两个，改之前先看现在是多少：

| 参数 | 我这台的值（`CONFIG GET`） | 改它会怎样 |
| --- | --- | --- |
| `slowlog-log-slower-than` | `10000`（微秒） | 调小记更多轻命令，慢日志本身开始占内存；设 `0` 记全部，负数关闭 |
| `slowlog-max-len` | `128` | 只管保留条数，满了覆盖最旧的；不改变「记不记」 |
| `lazyfree-lazy-user-del` | `no` | 是 `no` 时 `DEL` 在主线程里逐个字段释放；改 `yes` 等于让所有 `DEL` 走后台（同步版的代价第 01 篇量过） |

慢日志里还能顺藤摸到几件我自己踩出来的事。`copy session:all session:copy2` 记了 129904 微秒（130 毫秒）——复制一个 10 万字段的 hash 也是按字段数付钱，这就是「大 key 拖慢迁移/复制」的具体样子；`keys f:0` 记了 23338 微秒，模式窄到只匹配一个 key，它照样把整个字典走了一遍。

## 热 key：`--hotkeys` 先要一个前提

同一个实例上，这个工具连跑都跑不起来：

```text
$ redis-cli -p 6399 --hotkeys
Error: ERR An LFU maxmemory policy is not selected, access frequency not tracked.
Please note that when switching between policies at runtime LRU and LFU data will take some time to adjust.
```

我这台启动时是 `noeviction`，每个键根本没记频次。切过去，再真打 2 万次读：

```bash
redis-cli -p 6399 config set maxmemory-policy allkeys-lfu
# 20,000 次 HGET session:all s:1（--pipe 灌进去，墙上 200 毫秒）
```

```text
$ redis-cli -p 6399 object freq session:all
(integer) 99
$ redis-cli -p 6399 object freq k:1
(integer) 2

$ redis-cli -p 6399 --hotkeys
-------- summary -------

Sampled 200001 keys in the keyspace!
hot key found with counter: 98	keyname: "session:all"
hot key found with counter: 1	keyname: "k:199966"
...
```

读法：摘要按计数从大到小排，**第一行就是最热的那个**；后面那些 counter 是 1 的，是扫的过程中被碰过的冷键，不是热 key。它把 20 万个键全扫了一遍，墙上 4943 毫秒。计数怎么涨、怎么随时间衰减，第 03 篇讲过（8 位对数计数器、`lfu-log-factor`、`lfu-decay-time`），这里只要记住一句：**切到 LFU 之后的访问才开始积累**，`--hotkeys` 看到的是「自从开了 LFU 以来」，不是历史。

同一个实例上我还顺手量了 `MONITOR`，因为旧文里那句「一开就显著拖慢实例」跟我的经验对不上。20 条连接、10 万次 `PING`：平时 20903 次/秒、p50 0.711 毫秒；开着一个 `MONITOR` 观察者再跑，17259 次/秒、p50 0.903 毫秒。掉 17%，不是「拖垮」。但这不代表可以常开：它的开销跟「被观察命令的参数大小 × 条数」成正比，我这轮的 `PING` 没有参数，是最便宜的情况；观察者读得慢，那些行会堆在服务端的输出缓冲里（这一条我没测，是机制，不是数据）。而且它给不了频次——想找热 key 还是得 LFU。

## 把它从主线程上搬下来：HSCAN 的账

`HGETALL` 换成 `HSCAN` 分批，`INFO commandstats` 里两边一起看：

| 做法 | 调用次数 | 服务端合计 | 单次 |
| --- | --- | --- | --- |
| `HGETALL session:all` | 1 | 37979 微秒 | 38.0 毫秒 |
| `HSCAN session:all ... COUNT 1000` | 101 | 121503 微秒 | 1.2 毫秒 |

HSCAN 的总账是 HGETALL 的 3.2 倍——13 MB 要分 101 次发，每次还得重新走一遍游标。但它把**最坏的窗口从 38 毫秒压到 1.2 毫秒**。这和第 01 篇 `KEYS`/`SCAN` 那笔账是同一个取舍：分批换的是「没有几百毫秒的空洞」，不是「更快」。

如果瓶颈不是「一次吐太多」，而是「同一个 key 每秒被读几十万次」，换 HSCAN 没用，那是热 key，不是大 key。分流的三条**标准形态**（这部分我没在这台机器上验证：单实例没有「某个节点 CPU 被打满」这回事）：

- **进程内缓存挡读**：本地缓存几秒的 TTL，热 key 的读根本不进 Redis。代价是这几秒读到旧值。
- **把 key 复制成 N 份**（`session:1` … `session:N`），读随机挑一份，写要写全 N 份。代价是写放大 N 倍，N 份之间还有短暂不一致。
- **读走从节点**（第 07 篇讲的主从）：能摊读压力，但主节点仍要收全部写。

三条都只是分摊和降低概率，没有一个能把「这个 key 的写」摊开——只要还需要强一致，写就还是单点。

## 什么时候先看哪

| 你看到的现象 | 先跑什么 | 对上了说明什么 |
| --- | --- | --- |
| 平均延迟正常，p99 每隔几十秒冒一次尖刺 | `SLOWLOG GET 50` | 有 O(N) 命令在跑：看 `argv` 里是 `hgetall`、`keys` 还是 `copy` |
| 内存涨得比 key 数快 | `--keystats` 看 100% 那一档，再对可疑 key 跑 `MEMORY USAGE` 和 `OBJECT ENCODING` | 有个 key 的每字段成本比你估的高，多半是编码掉到 `hashtable` 了 |
| 一个核打满、QPS 封顶，命令本身不慢 | 先 `CONFIG GET maxmemory-policy` 确认是 LFU 系列，再 `OBJECT FREQ` / `--hotkeys` | 压力集中在一个 key 上，不是实例不够大 |
| 删除、复制、改结构时卡一下 | 看慢日志 `argv` 里是不是 `del`、`copy` | 按元素数付钱的命令，换后台释放或分批（第 01 篇的账） |

## 跑一遍

Windows 上用 PowerShell 起临时实例，别碰已经在跑的 6379：

```bash
redis-server --port 6399 --bind 127.0.0.1 --save "" --appendonly no
```

```bash
# 10 万字段的 hash：在服务端一次塞完，不用 10 万次往返
redis-cli -p 6399 eval "for i=1,100000 do redis.call('hset','session:all','s:'..i,string.rep('x',80)) end return redis.call('hlen','session:all')" 0
# 100000（我这边 675 毫秒）

# 20 万个小键（第 01 篇用过这条）
seq 1 200000 | sed 's/^/SET k:/' | sed 's/$/ vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv/' | redis-cli -p 6399 --pipe
redis-cli -p 6399 dbsize
# 200001
```

```bash
redis-cli -p 6399 --bigkeys                    # 约 8 秒，看那两行单位不同的头名
redis-cli -p 6399 memory usage session:all     # 13326450
redis-cli -p 6399 --keystats                   # 约 14 秒，看 100% 那一档
```

```bash
redis-cli -p 6399 hgetall session:all > /dev/null
redis-cli -p 6399 slowlog get 1                # 第 3 项就是 38 毫秒那次

redis-cli -p 6399 config set maxmemory-policy allkeys-lfu
redis-cli -p 6399 object freq session:all      # 冷 key 是个位数
redis-cli -p 6399 --hotkeys                    # 约 5 秒，第一行最热
```

```bash
redis-cli -p 6399 shutdown nosave
```

## 动手

1. 在你自己的实例上把 `--bigkeys` 的「Biggest hash … has N fields」和 `MEMORY USAGE` 并排放，算一下「每字段多少字节」。这个数字比任何阈值都好用。
2. 造 512 和 513 两个字段的 hash，各自 `MEMORY USAGE` 除以字段数，看编码翻转那一刻成本跳多少；再 `CONFIG GET hash-max-listpack-*` 看你这台机器的阈值（别用我的 512）。
3. 打开 LFU，把你最热的那个接口对应的 key 用 `OBJECT FREQ` 排序。它排第一而你以为不是的话，说明缓存或分片该上了。

## 检查一下

```quiz
{"id":"redis-08-quiz","title":"三道查现场的题","questions":[
  {"question":"--bigkeys 摘要里同时出现「Biggest hash found session:all has 100000 fields」和「Biggest string found k:199966 has 32 bytes」。这两行数字能直接比吗？","options":["能，都是这个 key 占的字节数","能，hash 那行是字节数、string 那行是字段数","不能，一个来自 HLEN、一个来自 STRLEN，同一个 hash 的字节数它根本不报","不能，因为 hash 那行的字段数是抽样估算出来的"],"answer":2,"explain":"类型不同、量纲也不同，摘要里唯一以字节计的是 Total key length，而它只算键名。要字节数得另跑 MEMORY USAGE：我量到 13326450。"},
  {"question":"同一个模板的 hash，512 个字段时 MEMORY USAGE 是 9656，加到 513 个就变成 31254。主要原因是？","options":["多了一个字段，就多了一份该字段的值","编码从 listpack 翻成 hashtable，每字段的固定开销变了","hashtable 会预先分配 512 个空桶","字段数和内存应该是线性的，第二次的数一定量错了"],"answer":1,"explain":"listpack 把字段和值挤在一块连续内存里，翻转成 hashtable 后每字段要自己的 dictEntry、SDS 头和独立分配，每字段成本从 18.9 字节跳到 60.9 字节。"},
  {"question":"你想找实例上的热 key，但 --hotkeys 直接报 access frequency not tracked。下一步最该做的是？","options":["把 --hotkeys 换成 --memkeys，它不依赖淘汰策略","多开几个 MONITOR 观察者，总有一个能看到高频命令","把 MONITOR 的输出重定向到文件，长期开着慢慢看","先 CONFIG GET maxmemory-policy，切到 LFU 系列策略，再用 OBJECT FREQ 或 --hotkeys"],"answer":3,"explain":"频次只在 LFU 系列策略下记录，别的策略下 OBJECT FREQ 和 --hotkeys 都会报这个错。MONITOR 能看命令但不是频次统计，我实测它还要掉 17% 吞吐。"}
]}
```

## 还差的

1. 没跑集群。`--bigkeys`/`--hotkeys` 在 Cluster 上是不是每个主节点各跑一次、`-c` 下行为如何，我没验证；本文所有数字都来自单实例。
2. 热 key 那三条分流只有形态和代价，没有数据。单实例测不出「一个节点 CPU 打满」，要验证得上 3 主 3 从。
3. `MONITOR` 我只量了无参数 `PING`（-17% 吞吐）。真实业务命令参数更大，掉多少我不知道。
4. 内存碎片没碰。`mem_fragmentation_ratio` 和 `activedefrag` 是另一条线，这篇只讲「怎么把那个 key 找出来」。
5. `UNLINK` 之后内存什么时候真的降下去、各个 `lazyfree` 选项的差别，我只引了第 01 篇的数，没在这里重测。
