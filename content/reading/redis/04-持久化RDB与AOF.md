---
title: 持久化：RDB 与 AOF
category: redis
order: 4
minutes: 22
level: intermediate
status: published
---

## 先把结论摆出来

**RDB 是定时拍快照，丢"上次快照到现在"的数据；AOF 是记账本，丢"还没来得及刷盘"的那几条。**

两者不是二选一，现在默认用**混合持久化**：AOF 文件的开头是一份 RDB 快照，后面接着追加增量命令。恢复时先读快照再重放增量，又快又不丢太多。

一句话选型：**能容忍丢几十秒数据就用 RDB 为主；一条都不能丢就 AOF + `everysec`，并接受它比 RDB 慢一点、文件大一点。**

## RDB：定时给内存拍张照

RDB（Redis Database）把某一刻的全部数据序列化成一个二进制文件，通常是 `dump.rdb`。

```bash
redis-cli SAVE      # 同步生成，期间阻塞所有命令——别在生产用
redis-cli BGSAVE    # fork 一个子进程去写，主进程继续服务
redis-cli LASTSAVE  # 上次成功生成的时间戳
redis-cli CONFIG GET save
```

`BGSAVE` 用 `fork()` 复制一个子进程，子进程把内存里的数据写进文件。关键点：**fork 本身会短暂阻塞主进程**，数据量越大、内存页越多，这一下越久（通常几十毫秒到几百毫秒）。这是 RDB 最容易被忽略的代价。

`CONFIG GET save` 看到的是触发条件，默认类似：

```text
save 3600 1      # 3600 秒内至少 1 个键变化
save 300 100     # 300 秒内至少 100 个键变化
save 60 10000    # 60 秒内至少 10000 个键变化
```

任一条满足就自动 `BGSAVE`。

**RDB 丢什么**：最后一次成功快照之后的所有写入。如果 `save 60 10000` 生效，最坏情况是丢了接近 60 秒的数据；如果那一刻写入量不足 10000，可能丢得更久。

**RDB 的优点**：文件紧凑（二进制、压缩过），恢复快（直接读进内存，不用重放命令），适合做备份和冷备。

## AOF：把每条写命令记下来

AOF（Append Only File）记的是**写命令本身**，像一本流水账。重启时把账本从头念一遍，数据就回来了。

```bash
redis-cli CONFIG SET appendonly yes
redis-cli CONFIG GET appendfsync
redis-cli BGREWRITEAOF    # 重写：把流水账压成能重建当前状态的最短命令集
```

为什么需要重写：你对同一个键 `INCR` 了十万次，账本里就有十万条命令，但实际只需要一条 `SET key 100000`。重写就是生成这份"最短等价命令集"。

**AOF 丢什么**，取决于 `appendfsync`：

| 取值 | 行为 | 最坏丢多少 |
| --- | --- | --- |
| `always` | 每条命令都 `fsync` 到磁盘 | 几乎不丢（丢当前这条） |
| `everysec` | 每秒 `fsync` 一次（后台线程） | 约 1 秒 |
| `no` | 交给操作系统决定，通常 30 秒左右 | 不确定，可能几十秒 |

`fsync` 是"让操作系统把缓冲区真正写到磁盘"的系统调用。不 `fsync`，数据只在操作系统的页缓存里，机器断电就没了。

三个取值的真实取舍：

- `always`：最安全，但每条命令都要等一次磁盘写入。机械盘上 QPS 会掉到几千，SSD 上也会明显下降；
- `everysec`：默认值，也是绝大多数场景的答案。丢 1 秒，换回接近全速；
- `no`：最快，但"什么时候落盘"完全不归你管，只在能容忍大量丢失时用。

**AOF 的优点**：丢得少，可读（纯文本命令），误删后还能从文件里捞出数据。**缺点**：文件比 RDB 大得多，恢复要重放命令，比读 RDB 慢。

## 混合持久化：现在默认的形态

Redis 4.0 之后，开启 AOF 时默认使用混合格式（`aof-use-rdb-preamble yes`）：AOF 文件的开头是一份 RDB 快照（重写那一刻的全量），后面接 AOF 格式的增量命令。

恢复时先加载前半段快照（快），再重放后半段增量（少）。既有 RDB 的恢复速度，又有 AOF 的低丢失。

```bash
redis-cli CONFIG GET aof-use-rdb-preamble   # 默认 yes
redis-cli INFO persistence                  # 看 rdb_* 与 aof_* 各项指标
```

## 到底会丢多少：四种配置对比

| 配置 | 最坏丢失 | 恢复速度 | 文件大小 |
| --- | --- | --- | --- |
| 只开 RDB（默认 save） | 约 1 分钟 | 快 | 小 |
| RDB + AOF `everysec` | 约 1 秒 | 中 | 大 |
| RDB + AOF `always` | 约 0 | 中 | 大 |
| 都不开 | 重启后全没 | — | — |

`INFO persistence` 里几个字段值得记住：

```bash
redis-cli INFO persistence | grep -E "rdb_last_bgsave_status|aof_last_write_status"
```

- `rdb_last_bgsave_status:ok` —— 上次快照成功。如果是 `err`，说明你的备份一直是坏的；
- `aof_last_write_status:ok` —— AOF 写入正常。如果 `err`（常见原因是磁盘满），Redis 会拒绝写入；
- `rdb_changes_since_last_save` —— 距上次快照改了多少个键，这个数就是"现在宕机会丢多少"。

## 一个具体例子：验证"丢多少"

在本机起一个实例，关掉 RDB、只留 AOF `everysec`：

```bash
redis-cli CONFIG SET save ""
redis-cli CONFIG SET appendonly yes
redis-cli CONFIG SET appendfsync everysec
redis-cli SET k1 v1
redis-cli INFO persistence | grep aof_last_write_status
```

然后 `kill -9` 掉进程再重启（`kill -9` 模拟断电，正常 `SHUTDOWN` 会做一次收尾刷盘）：

```bash
redis-cli GET k1     # 大概率还在
```

改成 `appendfsync no`，重复一遍，`kill -9` 之后 `k1` 就可能不见了。这就是 `everysec` 与 `no` 的差别。

## fork 与写时复制：为什么大实例的 BGSAVE 很痛

`BGSAVE` 和 `BGREWRITEAOF` 都要 `fork`。`fork` 之后父子进程共享内存页，只有写操作才复制那一页（copy-on-write，写时复制）。

后果：

- **写入越频繁，复制越多**，内存可能瞬间翻倍。一个 20 GB 的实例，写入压力大时 `BGSAVE` 期间可能占到 40 GB；
- **fork 本身阻塞主进程**。数据量越大越久，`INFO stats` 里的 `latest_fork_usec` 就是上一次 fork 花的时间（微秒）；
- 开了透明大页（THP）时，复制以 2 MB 为单位，浪费更严重。Redis 官方建议关掉 THP。

所以大实例上要做的不是"把快照调得更频繁"，而是**拆实例**：单实例控制在几 GB，fork 快、复制少。

## 检查一下

```quiz
{"id":"redis-04-quiz","title":"持久化三问","questions":[
 {"question":"只开 RDB、save 配置为「60 秒内 10000 次改动」，宕机最多丢多少数据？","options":["不丢","最多丢 1 秒","最多丢约 60 秒（写入量不足时可能更久）","全部丢失"],"answer":2,"explain":"快照是定时的，最后一次成功快照之后的写入全丢。"},
 {"question":"appendfsync everysec 与 always 的核心区别是？","options":["everysec 不写磁盘","everysec 每秒 fsync 一次，最多丢约 1 秒；always 每条都 fsync，几乎不丢但更慢","always 只对 RDB 生效","两者没有区别"],"answer":1,"explain":"区别在 fsync 的频率，也就是「断电时最多丢多少」。"},
 {"question":"混合持久化的 AOF 文件里装的是什么？","options":["只有 RDB 快照","只有命令流水","开头一份 RDB 快照，后面接增量命令","压缩后的 JSON"],"answer":2,"explain":"先读快照再重放增量，兼顾恢复速度与丢失量。"}
]}
```

## 常见误区

### 误区：开了持久化就不会丢数据

**你以为**：配了 AOF，数据就安全了。
**其实**：`everysec` 会丢约 1 秒；`no` 丢得更多；RDB 丢一整个快照周期。只有 `always` 接近"不丢"，但它有性能代价，且仍可能丢"当前这一条"。
**怎么验证**：`appendfsync no` 下写入后立刻 `kill -9`，重启看键还在不在。

### 误区：AOF 一定比 RDB 安全

**你以为**：AOF 记每条命令，所以更可靠。
**其实**：AOF 文件更大、恢复更慢，而且如果磁盘写满，AOF 写入失败会让 Redis 直接拒绝写入（`aof_last_write_status:err`）。RDB 在小数据量、可容忍分钟级丢失时反而更省事。
**怎么验证**：`INFO persistence` 看 `aof_last_write_status` 与 `rdb_last_bgsave_status`，两者都要盯。

### 误区：BGSAVE 不阻塞

**你以为**：带 B 前缀就是完全后台，对主进程零影响。
**其实**：`fork()` 那一下是阻塞的，而且写时复制会让内存占用上涨。大实例上这一次阻塞可能达到几百毫秒，表现为一次明显的延迟尖峰。
**怎么验证**：`INFO stats | grep latest_fork_usec` 看上次 fork 耗时；`INFO persistence` 里 `rdb_last_bgsave_time_sec` 是总时长。

## 小结

RDB 定时快照，丢一个周期；AOF 记流水，丢"没刷盘的那几条"。`everysec` 是默认也是多数场景的答案。现在默认用混合持久化：快照开头 + 增量追加。fork 与写时复制是大实例的主要成本，单实例别开太大。

下一篇讲缓存三大问题——穿透、击穿、雪崩，三个词经常被混着用，但解法完全不同。
