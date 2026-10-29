---
title: 大 key、热 key 与慢命令：线上问题怎么查
category: redis
order: 8
minutes: 24
level: intermediate
status: published
---

## 先把结论摆出来

Redis 的线上故障，**大多数不是"Redis 不够快"，而是这四类**：

| 问题 | 典型症状 | 先看什么 |
| --- | --- | --- |
| 大 key | 偶发卡顿、网络抖动、删除时阻塞 | `redis-cli --bigkeys`、`MEMORY USAGE` |
| 热 key | 单个节点 CPU 打满，其他节点很闲 | `--hotkeys`、`INFO commandstats` |
| 慢命令 | `SLOWLOG` 里出现 `KEYS` / `HGETALL` / `FLUSHALL` | `SLOWLOG GET` |
| 内存碎片 | `used_memory_rss` 远大于 `used_memory` | `INFO memory` 的碎片率 |

Redis 的命令执行是单线程的，**一条慢命令会挡住后面所有请求**。所以排查的重点不是"平均延迟"，而是"有没有某条命令偶尔很慢"。

## 大 key：定义与危害

业界常用阈值（阿里云/Redis 官方规范接近）：

- String 类型 value > 10 KB；
- Hash / List / Set / ZSet 元素数 > 5000，或总大小 > 10 MB。

为什么危险：

1. **网络阻塞**：一次 `GET` 传 10 MB，千兆网卡上就是几十毫秒，这期间其他命令在排队；
2. **删除阻塞**：`DEL` 一个大 hash 要释放所有元素，可能卡住几秒；4.0+ 用 `UNLINK` 把释放动作放到后台线程；
3. **集群迁移**：迁移一个槽时，大 key 会让迁移长时间卡住；
4. **持久化**：`BGSAVE` 与 AOF 重写都要遍历它。

### 怎么发现

```text
redis-cli --bigkeys
```

它用 `SCAN` 遍历，按类型报告最大的 key。注意它只报"每种类型最大的那一个"，不代表没有别的大 key。

精确看某个 key 的大小：

```text
redis-cli MEMORY USAGE user:1001:profile
```

集群模式要加 `-c`，并且 `--bigkeys` 只能在单个节点上跑——每个主节点都要跑一次。

### 怎么处理

- **拆分**：把一个大 hash 拆成多个小 hash（按字段前缀或业务维度）；
- **异步删除**：用 `UNLINK` 替代 `DEL`；
- **不要一次取全量**：`HGETALL` 改成 `HSCAN` 分批，或按需 `HMGET` 几个字段；
- **压缩**：value 是 JSON 且读多写少时，考虑存压缩后的字节，但会增加 CPU 开销。

## 热 key：单点被打爆

热 key 是**访问频率极高**的 key，比如首页配置、秒杀库存。问题不在大小，而在集中：

- Cluster 下所有请求打到同一个槽所在的节点，那个节点 CPU 打满；
- 主从架构下从节点能分担读，但主节点仍要处理写。

### 怎么发现

```text
# 需要 maxmemory-policy 使用 LFU 系列
redis-cli --hotkeys

# 看每条命令的调用次数与耗时
redis-cli INFO commandstats
```

`MONITOR` 也能看到实时命令，但它会显著拖慢实例，**只能短时间采样，绝不能在高峰期长期开**。

### 怎么处理

1. **本地缓存**：进程内缓存（Caffeine）挡掉绝大部分读，设置短 TTL；
2. **key 打散**：把一个热 key 复制成 N 份（`hot:1` … `hot:N`），读时随机选一个，写时更新所有副本；
3. **读写分离**：读走从节点；
4. **限流**：对访问这个 key 的接口做限流，宁可拒绝一部分也不要拖垮整个实例。

## 慢命令：单线程的代价

Redis 处理命令是串行的。一条 `KEYS *` 在百万 key 的实例上可能跑几秒，这期间所有请求都卡住。

```text
SLOWLOG GET 10
```

输出包含命令、耗时（微秒）、发生时间。默认阈值是 10ms，可以用 `CONFIG SET slowlog-log-slower-than 10000` 调整。

**常见慢命令清单**：

| 命令 | 为什么慢 | 替代 |
| --- | --- | --- |
| `KEYS pattern` | 遍历所有 key | `SCAN cursor MATCH pattern COUNT 100` |
| `HGETALL` 大 hash | 一次返回全部字段 | `HSCAN` 分批 / `HMGET` 指定字段 |
| `SMEMBERS` 大 set | 一次返回全部成员 | `SSCAN` |
| `DEL` 大 key | 同步释放 | `UNLINK` |
| `FLUSHALL` / `FLUSHDB` | 清空整个库 | 用 `SCAN` 分批删，或低峰期执行 |
| 复杂 Lua 脚本 | 脚本在单线程里跑完才处理别的 | 缩小脚本逻辑，避免循环大集合 |

另外 `LATENCY DOCTOR` 会给出一段人类可读的诊断，`LATENCY HISTORY <event>` 能看某个事件的历史峰值。

## pipeline 与 Lua：别用错

**pipeline**：把多条命令一次性发过去，减少网络往返。它**不是事务**——中间某条失败，其他条照样执行。

```text
> SET a 1
> INCR b
> GET a
（三条一起发，按顺序执行，按顺序返回）
```

**Lua 脚本**：在 Redis 里原子执行，适合"读-判断-写"必须一起完成的场景（比如分布式锁的释放）。但脚本执行期间会阻塞其他命令，**脚本里不能有重循环或大范围遍历**。

选择标准：

- 只是减少 RTT → pipeline；
- 需要原子性 → Lua（或 `MULTI/EXEC`，但后者不能根据中间结果做判断）；
- 需要跨 key 事务且 key 很多 → 重新设计数据模型。

## 内存碎片与容量

```text
INFO memory
```

关注两个数：

- `used_memory`：Redis 分配器认为已使用的内存；
- `used_memory_rss`：操作系统看到的实际物理内存。

`mem_fragmentation_ratio = rss / used_memory`：

- 1.0–1.5 正常；
- 大于 1.5 说明碎片较多，可以开 `activedefrag yes`（4.0+），或低峰期重启；
- 小于 1 说明部分内存被换到 swap，性能会很差。

`maxmemory` 一定要设，并选择淘汰策略（见第 3 篇）。不设上限的 Redis 最终会把机器内存吃光，触发 OOM Killer。

## 一个具体例子：一次 `HGETALL` 造成的卡顿

现象：监控显示每秒有几次 200ms 的尖刺，但 CPU 平均只有 20%。

排查：

```text
SLOWLOG GET 5
1) 1) (integer) 128
   2) (integer) 1730000000
   3) (integer) 210000        # 210ms
   4) 1) "HGETALL"
      2) "session:all"
```

`sessions:all` 是一个包含 8 万个字段的 hash，某个定时任务每次全量读取。改成 `HSCAN` 分批后尖刺消失。

**结论**：平均延迟会骗人，必须看慢日志与分位数。

## 检查一下

```quiz
{"id":"redis-08-quiz","title":"运维排查三问","questions":[
 {"question":"删除一个包含十万个字段的大 hash，应该用哪个命令？","options":["DEL","UNLINK","FLUSHDB","EXPIRE"],"answer":1,"explain":"UNLINK 把释放放到后台线程，避免阻塞主线程。"},
 {"question":"替代 KEYS * 的命令是？","options":["SCAN","HGETALL","MONITOR","INFO"],"answer":0,"explain":"SCAN 用游标分批遍历，不会长时间阻塞。"},
 {"question":"mem_fragmentation_ratio 明显大于 1.5 说明什么？","options":["内存不够","碎片较多，物理内存占用高于实际数据","网络延迟高","主从不同步"],"answer":1,"explain":"rss 远大于 used_memory，说明分配器持有大量未归还的碎片。"}
]}
```

## 常见误区

### 误区：大 key 只是占内存

**你以为**：大 key 的唯一问题是浪费空间。
**其实**：更大的问题是**阻塞**——网络传输、删除、迁移、持久化都会因为它变慢，而 Redis 是单线程的。
**怎么验证**：对一个 10 万元素的 hash 执行 `DEL` 并计时，再用 `UNLINK` 对比。

### 误区：pipeline 等于事务

**你以为**：一批命令一起发，要么全成功要么全失败。
**其实**：pipeline 只是减少网络往返，每条命令独立执行、独立失败，没有回滚。
**怎么验证**：pipeline 里放一条会报错的命令（比如对 string 执行 `LPUSH`），观察其他命令是否照常生效。

### 误区：`MONITOR` 可以用来长期监控

**你以为**：开着 `MONITOR` 就能持续观察所有命令。
**其实**：它会把每条命令都输出一遍，吞吐越高开销越大，生产环境长时间开着可能直接把实例拖慢。
**怎么验证**：在压测环境开 `MONITOR` 前后对比 QPS 与延迟；生产上改用 `SLOWLOG` + `--hotkeys` + 指标监控。

## 小结

线上 Redis 排查的顺序是：**先看慢日志，再看大 key 与热 key，最后看碎片与容量**。平均延迟没有意义，尖刺才有；`KEYS`、`HGETALL`、大 `DEL` 是最常见的三个元凶。容量问题靠 `maxmemory` + 淘汰策略兜底，结构问题靠拆分与 `UNLINK` 解决。
