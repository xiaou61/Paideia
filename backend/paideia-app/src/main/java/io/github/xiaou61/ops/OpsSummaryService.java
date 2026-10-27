package io.github.xiaou61.ops;

import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 把七类指标判成四态并附一句人话原因（WORK-028 REQ-028）。
 *
 * <p><b>判定只在这里做一次</b>：前端与 Grafana 都不重新算阈值。反过来做（阈值写在面板里，
 * 页面只显示颜色）会让同一件事有两个真相，改一个忘另一个。
 *
 * <p>每个 group 独立查询、独立失败：Prometheus 挂了就全部 UNKNOWN，但一个指标的查询失败
 * 只影响它所在的那一组——这是 REQ-025 的隔离要求在摘要层的体现。
 */
@Service
class OpsSummaryService {

    private static final List<String> UNITS = List.of(
            "paideia", "laya", "rustfs", "mysql", "redis-server", "loki", "alloy");

    private final OpsProperties properties;
    private final PrometheusClient prometheus;
    private final Environment environment;

    OpsSummaryService(OpsProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
        this.prometheus = new PrometheusClient(properties.getPrometheusUrl(), properties.getQueryTimeoutMs());
    }

    OpsSummary summary() {
        OpsProperties.Thresholds thresholds = properties.getThresholds();

        OpsSummary.Group host = hostGroup(thresholds);
        OpsSummary.Group services = servicesGroup(thresholds);
        OpsSummary.Group middleware = middlewareGroup();
        OpsSummary.Group external = externalGroup();
        OpsSummary.Group scheduled = scheduledGroup();
        OpsSummary.Group backup = backupGroup(thresholds);

        boolean reachable = prometheus.scalar("vector(1)") != null;
        return new OpsSummary(
                java.time.Instant.now().toString(),
                reachable,
                List.of(host, services, middleware, external, scheduled, switchesGroup(), mcpGroup(), backup));
    }

    /**
     * 外部 MCP 的调用量与被拒原因（WORK-044 REQ-015）。
     *
     * <p>指标名与 `McpMetrics` 一致；Prometheus 抓不到时各项显示"未知"而不是 0——
     * 0 会被误读成"没人用"，而真相是"我们还不知道"。
     */
    private OpsSummary.Group mcpGroup() {
        Double total = scalar("sum(paideia_mcp_tool_calls_total)");
        Double denied = scalar("sum(paideia_mcp_denied_total)");
        Double quota = scalar("sum(paideia_mcp_denied_total{reason=\"quota\"})");
        return new OpsSummary.Group("mcp", "外部 MCP", OpsStatus.OK,
                "累计调用 " + count(total) + "、被拒 " + count(denied) + "（其中配额 " + count(quota) + "）",
                List.of(OpsSummary.Metric.text("tool_calls", count(total)),
                        OpsSummary.Metric.text("denied", count(denied)),
                        OpsSummary.Metric.text("denied_quota", count(quota))));
    }

    /** 聚合查询的结果只有一行、没有标签；查不到返回 null（区分"没人用"与"抓不到"）。 */
    private Double scalar(String query) {
        List<PrometheusClient.Sample> samples = prometheus.query(query);
        return samples.isEmpty() ? null : samples.getFirst().value();
    }

    private static String count(Double value) {
        return value == null ? "未知" : String.valueOf(value.longValue());
    }

    private OpsSummary.Group hostGroup(OpsProperties.Thresholds thresholds) {
        Double load = prometheus.scalar("node_load1");
        Double memTotal = prometheus.scalar("node_memory_MemTotal_bytes");
        Double memAvailable = prometheus.scalar("node_memory_MemAvailable_bytes");
        Double diskTotal = prometheus.scalar("node_filesystem_size_bytes{mountpoint=\"/\"}");
        Double diskAvailable = prometheus.scalar("node_filesystem_avail_bytes{mountpoint=\"/\"}");

        Double diskUsed = ratio(diskTotal == null || diskAvailable == null ? null : diskTotal - diskAvailable, diskTotal);
        OpsStatus diskStatus = OpsStatus.of(diskUsed, thresholds.getDiskUsedWarn(), thresholds.getDiskUsedError());
        OpsStatus loadStatus = OpsStatus.of(load, 2.0 * Runtime.getRuntime().availableProcessors(),
                4.0 * Runtime.getRuntime().availableProcessors());

        return new OpsSummary.Group("host", "主机", OpsStatus.worst(diskStatus, loadStatus),
                "磁盘已用 " + percent(diskUsed) + "，可用内存 " + gigabyte(memAvailable) + "，负载 " + number(load),
                List.of(OpsSummary.Metric.number("diskUsed", diskUsed, "ratio"),
                        OpsSummary.Metric.number("load1", load, "count"),
                        OpsSummary.Metric.number("memoryAvailable", memAvailable, "bytes"),
                        OpsSummary.Metric.number("memoryTotal", memTotal, "bytes")));
    }

    private OpsSummary.Group servicesGroup(OpsProperties.Thresholds thresholds) {
        List<PrometheusClient.Sample> memory = prometheus.query("paideia_unit_memory_bytes");
        List<PrometheusClient.Sample> maximum = prometheus.query("paideia_unit_memory_max_bytes");

        OpsStatus worst = OpsStatus.OK;
        StringBuilder reason = new StringBuilder();
        for (String unit : UNITS) {
            Double used = valueFor(memory, "unit", unit);
            Double max = valueFor(maximum, "unit", unit);
            // 上限为 0 表示该单元没设 MemoryMax。此时不判占比压力（除零会得到 Infinity），
            // 但**仍然要显示它占了多少**——只有 laya 设了上限，其余四个没有，
            // 把"没有上限"显示成"未知"等于把四个真实的读数丢掉。
            Double ratio = max == null || max <= 0 ? null : ratio(used, max);
            OpsStatus status = ratio == null
                    ? (used == null ? OpsStatus.UNKNOWN : OpsStatus.OK)
                    : OpsStatus.of(ratio, thresholds.getUnitMemoryWarn(), thresholds.getUnitMemoryError());
            worst = OpsStatus.worst(worst, status);
            if (!reason.isEmpty()) {
                reason.append("；");
            }
            if (used == null) {
                reason.append(unit).append(" 未知");
            } else if (ratio == null) {
                reason.append(unit).append(' ').append(gigabyte(used)).append("（未设上限）");
            } else {
                reason.append(unit).append(' ').append(percent(ratio));
            }
        }
        return new OpsSummary.Group("services", "服务", worst, "内存：" + reason,
                List.of(OpsSummary.Metric.text("units", reason.toString())));
    }

    private OpsSummary.Group middlewareGroup() {
        Double connections = prometheus.scalar("mysql_global_status_threads_connected");
        Double maxConnections = prometheus.scalar("mysql_global_variables_max_connections");
        Double redisKeys = prometheus.scalar("redis_db_keys");
        // Redis 现在装着、开着、零引用、零键；如实呈现，避免看的人以为它在关键路径上
        return new OpsSummary.Group("middleware", "中间件",
                connections == null && redisKeys == null ? OpsStatus.UNKNOWN : OpsStatus.OK,
                "MySQL 连接 " + number(connections) + "/" + number(maxConnections) + "；Redis 键 "
                        + number(redisKeys) + "（未被应用使用）",
                List.of(OpsSummary.Metric.number("mysqlConnections", connections, "count"),
                        OpsSummary.Metric.number("mysqlMaxConnections", maxConnections, "count"),
                        OpsSummary.Metric.number("redisKeys", redisKeys, "count")));
    }

    private OpsSummary.Group externalGroup() {
        Double laya = probeStatus("laya");
        Double rustfs = probeStatus("rustfs");
        Double firecrawl = probeStatus("firecrawl");
        OpsStatus status = OpsStatus.worst(probeStatusOf(laya), probeStatusOf(rustfs), probeStatusOf(firecrawl));
        return new OpsSummary.Group("external", "外部依赖", status,
                "探活：" + probeText("laya", laya) + "、" + probeText("rustfs", rustfs) + "、"
                        + probeText("firecrawl", firecrawl),
                List.of(OpsSummary.Metric.number("layaUp", laya, "count"),
                        OpsSummary.Metric.number("rustfsUp", rustfs, "count"),
                        OpsSummary.Metric.number("firecrawlUp", firecrawl, "count")));
    }

    /** blackbox 的目标在 prometheus.yml 里用 `instance` 改写成 laya / rustfs / firecrawl。 */
    private Double probeStatus(String target) {
        return prometheus.scalar("probe_success{instance=\"" + target + "\"}");
    }

    private static OpsStatus probeStatusOf(Double up) {
        if (up == null) {
            return OpsStatus.UNKNOWN;
        }
        return up >= 1 ? OpsStatus.OK : OpsStatus.ERROR;
    }

    private static String probeText(String name, Double up) {
        if (up == null) {
            return name + " 未知";
        }
        return up >= 1 ? name + " 通" : name + " 不通";
    }

    /**
     * 定时任务（REQ-016/018）。
     *
     * <p>三种情形必须能区分开：「从未运行」「跑了但上次失败」「最近一次成功」——
     * 它们都表现为"没有新内容"，但处置完全不同。判据是 `last_run_epoch_seconds`：
     * 0 表示从未跑过，非 0 才看 success。
     */
    private OpsSummary.Group scheduledGroup() {
        List<PrometheusClient.Sample> runs =
                prometheus.query("paideia_scheduled_last_run_epoch_seconds");
        List<PrometheusClient.Sample> success =
                prometheus.query("paideia_scheduled_last_success");
        if (runs.isEmpty()) {
            return new OpsSummary.Group("scheduled", "定时任务", OpsStatus.UNKNOWN,
                    "尚未采集到任何任务的执行记录", List.of());
        }

        OpsStatus worst = OpsStatus.OK;
        StringBuilder reason = new StringBuilder();
        for (PrometheusClient.Sample sample : runs) {
            String task = sample.labels().getOrDefault("task", "?");
            Double lastRun = sample.value();
            Double ok = valueFor(success, "task", task);
            OpsStatus status;
            String text;
            if (lastRun == null || lastRun <= 0) {
                status = OpsStatus.UNKNOWN;
                text = task + " 从未运行";
            } else if (ok != null && ok < 1) {
                status = OpsStatus.ERROR;
                text = task + " 上次失败";
            } else {
                status = OpsStatus.OK;
                text = task + " 上次成功";
            }
            worst = OpsStatus.worst(worst, status);
            if (!reason.isEmpty()) {
                reason.append('；');
            }
            reason.append(text);
        }
        return new OpsSummary.Group("scheduled", "定时任务", worst, reason.toString(),
                List.of(OpsSummary.Metric.text("tasks", reason.toString())));
    }

    private OpsSummary.Group backupGroup(OpsProperties.Thresholds thresholds) {
        Double ageHours = prometheus.scalar("paideia_backup_age_seconds / 3600");
        Double success = prometheus.scalar("paideia_backup_success");
        OpsStatus status = success != null && success < 1
                ? OpsStatus.ERROR
                : OpsStatus.of(ageHours, thresholds.getBackupStaleHours(), thresholds.getBackupStaleHours());
        return new OpsSummary.Group("backup", "备份", status,
                ageHours == null ? "尚未采集" : "上次成功备份 " + number(ageHours) + " 小时前",
                List.of(OpsSummary.Metric.number("ageHours", ageHours, "hours"),
                        OpsSummary.Metric.number("success", success, "count")));
    }

    private OpsSummary.Group switchesGroup() {
        boolean essay = environment.getProperty("paideia.ai.essay.enabled", Boolean.class, false);
        boolean decision = environment.getProperty("paideia.ai.decision.enabled", Boolean.class, false);
        boolean sandbox = environment.getProperty("paideia.sandbox.enabled", Boolean.class, false);
        String storage = environment.getProperty("paideia.knowledge.storage", "local");
        boolean smtp = !environment.getProperty("paideia.mail.host", "").isBlank();
        return new OpsSummary.Group("switches", "配置开关", OpsStatus.OK,
                "面经抓取 " + onOff(essay) + "、决策模型 " + onOff(decision) + "、沙箱 " + onOff(sandbox)
                        + "、存储 " + storage + "、邮件 " + (smtp ? "已配置" : "未配置"),
                List.of(OpsSummary.Metric.text("essay", onOff(essay)),
                        OpsSummary.Metric.text("decision", onOff(decision)),
                        OpsSummary.Metric.text("storage", storage),
                        OpsSummary.Metric.text("smtp", smtp ? "configured" : "missing")));
    }

    private static Double valueFor(List<PrometheusClient.Sample> samples, String label, String expected) {
        return samples.stream()
                .filter(sample -> expected.equals(sample.labels().get(label)))
                .map(PrometheusClient.Sample::value)
                .findFirst()
                .orElse(null);
    }

    private static Double ratio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator == 0) {
            return null;
        }
        return numerator / denominator;
    }

    private static String percent(Double value) {
        return value == null ? "未知" : String.format("%.0f%%", value * 100);
    }

    private static String gigabyte(Double bytes) {
        return bytes == null ? "未知" : String.format("%.1fG", bytes / 1024 / 1024 / 1024);
    }

    private static String number(Double value) {
        return value == null ? "未知" : String.format("%.1f", value);
    }

    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }
}
