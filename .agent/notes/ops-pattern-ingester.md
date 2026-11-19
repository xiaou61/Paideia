# Loki pattern ingester 探针与结论（WORK-085 TASK-001）

## 目的

WORK-085 的模板聚类落点选在 **Loki/Alloy 侧**（不新增常驻进程、不在应用里重造 Drain）。
Loki 3.x 自带 `pattern_ingester`（Drain 算法，把流式日志压成模板并聚合样本）。
本笔记记录：能不能只靠 Loki 配置开启、它的开关陷阱、以及上线前必须在服务器上验什么。

## 版本与形态

- 本项目 Loki 版本：`deploy/ops/install-ops.sh` 的 `LOKI_VERSION=3.7.9`（单机 monolithic 部署，`deploy/ops/loki.service`，`/usr/local/bin/loki -config.file=/etc/loki/loki-config.yaml`）。
- pattern ingester 是 Loki 内建模块，**单机模式仅靠配置文件即可启用**，不需要 Helm、不需要额外进程。
- 我们对 `deploy/ops/loki-config.yaml` 的改动（已落盘）：

```yaml
pattern_ingester:
  enabled: true
  metric_aggregation:
    enabled: true            # 默认 false，必须显式打开，否则只解析不聚合样本
    loki_address: 127.0.0.1:3100
    downsample_period: 10s
```

## 两个容易踩的开关陷阱

1. `pattern_ingester.metric_aggregation.enabled` **默认是 false**。只开 `pattern_ingester.enabled` 而不开
   `metric_aggregation.enabled` 时，Loki 会解析模板但不把「每个模板每段时间的样本数」聚合出来，
   `/loki/api/v1/patterns` 查到的很可能是空或不带 samples 的结果。
2. `limits_config.metric_aggregation_enabled` 是**另一个功能**（把 bytes/count 之类的聚合样本写成
   `__aggregated_metric__` 流），与 pattern 无关，不要拿它当 pattern 的开关。

## 内存

`loki.service` 由 `MemoryMax=512M` 上调到 `1024M`：pattern ingester 的 Drain 状态与模板样本在内存里，
512M 对「模板 + 聚合样本」偏紧。改的是 systemd 的 cgroup 上限，不改 Loki 自身配置。

## 应用侧怎么用

- `LokiClient.patterns(query, from, to, maxSamples)` 调 `GET /loki/api/v1/patterns?query=&start=&end=`（实验性接口），
  解析 `data[].pattern` 与 `data[].samples[[ts,count]]`，按 count 求和、取最后时间戳，按 count 倒序截断。
- 接口失败 / 非 success / 非数组 → 返回空列表（与 `queryRange` 同语义：不可达不抛异常，降级为空）。
- `PatternSyncService.syncOnce` 每轮把模板与桶写进 `ops_log_templates` / `ops_log_template_buckets`。

## 上线前必须在服务器上验证（本工作项未在服务器实测）

1. 重启 Loki 后看 journal：`journalctl -u loki -n 200` 无 `pattern_ingester` 相关报错、无 OOM。
2. 直接打接口确认数据结构与线上版本一致（字段名、时间戳单位）：
   `curl -s 'http://127.0.0.1:3100/loki/api/v1/patterns?query=%7Bunit%3D%22paideia.service%22%7D&start=<ns>&end=<ns>' | head`
   —— 期望 `{"status":"success","data":[{"pattern":"...","samples":[[<ts>,<count>],...]}]}`。
3. 若该实验接口在本版本不可用（返回 404 / 结构不同）：降级读取 pattern ingester 写出的 `loki_patterns_*` 指标
   或改用 `range` 查询自行聚合；`PatternSyncService` 已把「接口不可达」降级为「本轮 0 条」，不会中断已有模板。
4. 观察内存：`systemctl show loki -p MemoryCurrent`，以及 Prometheus 里 Loki 的 RSS，确认 1G 上限够用。

## 结论

- 代码/配置侧已完成（TASK-001），但**服务器实测未做**，列为上线前检查项。
- 降级路径明确：接口不可用时同步空转，异常检测以 DB 里已有模板为驱动，不会因 Loki 接口抖动而误报。
