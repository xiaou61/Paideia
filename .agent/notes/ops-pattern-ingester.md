# Loki pattern ingester 探针与结论（WORK-085 TASK-001）

## 目的

WORK-085 的模板聚类落点选在 **Loki/Alloy 侧**（不新增常驻进程、不在应用里重造 Drain）。
Loki 3.x 自带 `pattern_ingester`（Drain 算法，把流式日志压成模板并聚合样本）。
本笔记记录：怎么开启、哪些字段是坑、`/loki/api/v1/patterns` 的真实行为，以及它对检测逻辑的硬约束。

**状态：2026-11-19 已在线上服务器（codenest-online，Loki 3.7.9）实测完毕，下面每条都是实测结论而非推断。**

## 版本与形态

- Loki **3.7.9**，单机 monolithic（`deploy/ops/loki.service`，`/usr/local/bin/loki -config.file=/etc/loki/loki-config.yaml`）。
- pattern ingester 是内建模块，单机只靠配置文件即可启用，不需要 Helm、不需要额外进程、不需要独立 ring（`lifecycler.ring.kvstore.store: inmemory`）。

## 正确配置（`deploy/ops/loki-config.yaml`）

```yaml
limits_config:
  metric_aggregation_enabled: true     # 模板样本聚合的**真正开关**，在 limits 里
  pattern_persistence_enabled: true    # 把模板写回 Loki 日志流（本项目未使用，留作后续选项）

pattern_ingester:
  enabled: true
  metric_aggregation:
    loki_address: 127.0.0.1:3100       # 唯一必填项；sample_period 默认 10s
```

### 三个踩过的坑（都有报错/实测证据）

1. **`metric_aggregation` 下没有 `enabled` 字段**。首版写成 `metric_aggregation.enabled: true` 会让 Loki
   **直接起不来**：`loki -config.file=... -verify-config` 报
   `failed parsing config: ... field enabled not found in type aggregation.Config`。
   3.7.9 的 `aggregation.Config` 只有 `sample_period / loki_address / push_period / timeout /
   http_client_config / use_tls / basic_auth / backoff_config`。
2. **也没有 `downsample_period`**（报 `field downsample_period not found`）。命令行 flag 名才是
   `-downsample-period`，配置字段名是 `sample_period`（默认 10s）。
3. **开关在 `limits_config`**：`metric_aggregation_enabled` 决定 `MetricAggregationEnabled(userID)`；
   `pattern_persistence_enabled` 控制是否把模板写回日志流。两个都与 `pattern_ingester.metric_aggregation`
   下的字段无关。**改完必须 `loki -verify-config` 过一遍再重启**，否则一次重装（install-ops.sh）就会让 Loki 起不来。

## 内存

`loki.service` 由 `MemoryMax=512M` 上调到 `1024M`：Drain 状态与模板样本都在内存。实测重启后
`MemoryCurrent` 约 38MB 起、稳定在百 MB 级，1024M 有余量。

## `/loki/api/v1/patterns` 的真实行为（决定性）

请求：`GET /loki/api/v1/patterns?query={unit="xxx.service"}&start=<纳秒>&end=<纳秒>`

- **时间参数必须是纳秒**（`epochSecond` 直接拼 `000000000`）。传毫秒会报
  `this data is no longer available, it is past now - max_query_lookback (30d)`。
- 真实返回（线上抓到的原文）：

```json
{"status":"success","data":[{"pattern":"handled GET /api/probe/x -> 200 in <_>","level":"info",
  "samples":[[1795058170,2],[1795058180,10],[1795058190,10],[1795058200,10],[1795058210,8]]}]}
```

  `data` 是**数组**（不是 `{series:[]}`），`samples` 是 `[epoch秒, 条数]` 对，10 秒一格，`<_>` 是数字占位符。

- **硬约束：`minClusterSize = 30`**。`pkg/pattern/ingester_querier.go` 里写死 `minClusterSize = 30`，
  `prunePatterns()` 会把「查询窗口内该模板样本值总和 < 30」的 series 全部丢掉
  （`loki_pattern_ingester_query_pruned_total` 涨、`retained_total` 不涨），`maxPatterns = 300` 是另一个上限。
  **→ 低流量或高变化的日志在短窗口里永远查不到。** 实测：向 `unit="opsprobe3.service"` 推 40 行完全相同的日志，
  立刻返回 1 条模板（samples 合计 40）；而 `unit="paideia.service"` 查 60 分钟窗口为空（约 7 行/分钟、
  模板分散，单模板凑不到 30 条）。
- 内存侧只按 `retain_for`（默认 **3h**）修剪样本，`sweepInstance` 不会每分钟删流；所以可查历史 ≈ 3 小时。
- `stream.Push` 有**单调性守卫**：`if entry.Timestamp.UnixNano() < s.lastTS { continue }` —— 用倒序时间戳
  造的探针数据会被整批丢弃（第一轮 10 行探针因此全部无效）。造数据必须时间递增。

## 应用侧怎么用（受上面约束决定的设计）

- `LokiClient.patterns(query, from, to, maxSeries)` 返回 `List<PatternSeries(pattern, List<PatternSample(at,count)>)`，
  **保留每条采样点的时间**；不能求和成一条，否则几小时的数据会落进同一个检测桶。
- `PatternSyncService` 用 `paideia.ops.patterns.lookback-minutes`（默认 **60**）作为查询窗口——必须显著大于
  检测窗口（默认 15m），否则凑不到 30 条；然后按**每条采样点自己的时间**折算 15 分钟桶 upsert
  （`count = VALUES(count)` 幂等，重复同步不会翻倍）。
- `PatternAnomalyDetector` 只看**最近一个完整窗口**（`bucketStart(now) - window`）：当前桶是半截的，
  拿它和完整基线比会把正常低计数误判成 `DISAPPEARED`。
- 接口不可达 / 404 / 非 success → 返回空列表，同步按「本轮 0 条」降级，不中断已有模板。

## 备选方案（暂不做）

`pattern_ingester.pattern_persistence.loki_address` 可让 Loki 把模板以
`ts=%d count=%d detected_pattern="%s"` 的日志写回 `{kind="patterns"}` 流（30 天留存、不受 `minClusterSize` 限制），
但流标签只有 `kind + service_name`（本项目所有 journal 流的 `service_name` 都是 `systemd-journal`），
**会丢 unit 维度**。等需要更长历史时再评估。

## 上线后怎么验（已按此验证过一次）

1. `loki -config.file=/etc/loki/loki-config.yaml -verify-config` 必须 exit 0。
2. 重启后 `/config` 里能看到 `pattern_ingester.enabled: true` 与 `limits_config.metric_aggregation_enabled: true`；
   `journalctl -u loki -n 200` 无 `pattern_ingester` 报错、无 OOM。
3. 造确定性数据验接口（时间必须递增）：

```bash
NOW=$(date +%s)
# 推 40 行完全相同的日志到 unit="laya.service"（该 unit 已在 paideia.ops.patterns.units 里）
# 然后等一轮同步（默认 5 分钟，首轮 2 分钟后启动），查库：
mysql -uroot paideia -e "SELECT unit,LEFT(pattern,60),last_seen,last_count FROM ops_log_templates ORDER BY last_seen DESC LIMIT 5;"
mysql -uroot paideia -e "SELECT unit,bucket_start,count FROM ops_log_template_buckets ORDER BY bucket_start DESC LIMIT 10;"
```

4. 观察内存：`systemctl show loki -p MemoryCurrent`，确认 1G 上限够用。

## 结论

- 配置与代码侧全部落地并在线上验证通过；**唯一需要长期记住的限制是 `minClusterSize = 30`**：
  本项目日志量小，短窗口查不到模板是正常现象，不是故障。
