package io.github.xiaou61.ops;

import io.github.xiaou61.ai.OpsDiagnoser;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 把运维数据采成一份可交给模型的证据（WORK-042 REQ-009）。
 *
 * <p><b>所有查询都是常量</b>：模型与管理员的输入不会进入 PromQL/LogQL 字符串。要加一个新指标，
 * 在 {@link #METRICS} 里加一行——这是刻意的，查询目录就是白名单。
 *
 * <p>降级策略：Prometheus 或 Loki 不可达时不让整次诊断失败。摘要本来就会把拿不到的项标成
 * UNKNOWN；日志为空时写"无匹配日志（或 Loki 不可达）"，把不确定性如实交给模型，而不是假装没有日志。
 */
@Service
class OpsEvidenceCollector {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ISO_INSTANT;

    /** 固定指标目录：名字 → 查询、精度、单位。 */
    private static final List<Metric> METRICS = List.of(
            new Metric("targets_down", "(sum(up < 1) or vector(0)) + (sum(probe_success < 1) or vector(0))", 0, "个"),
            new Metric("cpu_percent", "100 * (1 - avg(rate(node_cpu_seconds_total{mode=\"idle\"}[5m])))", 1, "%"),
            new Metric("memory_percent",
                    "100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)", 1, "%"),
            new Metric("disk_percent",
                    "100 * (1 - node_filesystem_avail_bytes{mountpoint=\"/\"}"
                            + " / node_filesystem_size_bytes{mountpoint=\"/\"})", 1, "%"),
            new Metric("http_5xx_percent",
                    "100 * sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[5m]))"
                            + " / clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 0.000001)",
                    2, "%"),
            new Metric("http_p95_seconds",
                    "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))",
                    3, "s"),
            new Metric("jvm_heap_percent",
                    "100 * sum(jvm_memory_used_bytes{area=\"heap\"}) / sum(jvm_memory_max_bytes{area=\"heap\"})",
                    1, "%"),
            new Metric("mysql_connections", "mysql_global_status_threads_connected", 0, "个"),
            new Metric("redis_memory_mb", "redis_memory_used_bytes / 1024 / 1024", 1, "MB"),
            new Metric("scheduled_failures", "count(paideia_scheduled_last_success < 1) or vector(0)", 0, "个"));

    private final OpsProperties properties;
    private final OpsSummaryService summary;
    private final PrometheusClient prometheus;
    private final LokiClient loki;

    /** 生产构造器；另一个带客户端的构造器只给测试用（两个构造器必须标出这一个）。 */
    @Autowired
    OpsEvidenceCollector(OpsProperties properties, OpsSummaryService summary) {
        this(properties, summary,
                new PrometheusClient(properties.getPrometheusUrl(), properties.getQueryTimeoutMs()),
                new LokiClient(properties.getLokiUrl(), properties.getQueryTimeoutMs()));
    }

    OpsEvidenceCollector(OpsProperties properties, OpsSummaryService summary,
                         PrometheusClient prometheus, LokiClient loki) {
        this.properties = properties;
        this.summary = summary;
        this.prometheus = prometheus;
        this.loki = loki;
    }

    OpsDiagnoser.Evidence collect(String trigger, String alertName, String question) {
        OpsProperties.Diagnosis config = properties.getDiagnosis();

        String summaryText = formatSummary(summary.summary());
        String metricsText = formatMetrics();
        String alertsText = firingAlerts();
        String deploysText = formatDeploy();

        int nonLogChars = summaryText.length() + metricsText.length() + alertsText.length() + deploysText.length();
        int logBudget = Math.max(0, config.getMaxEvidenceChars() - nonLogChars);
        String logsText = formatDiagnosisLogs(config.getMaxLogLines(), logBudget);

        return new OpsDiagnoser.Evidence(
                trigger, alertName, question,
                summaryText, metricsText, alertsText, logsText, deploysText);
    }

    /** 工具用的固定指标名清单；名字即白名单。 */
    static List<String> metricNames() {
        return METRICS.stream().map(Metric::name).toList();
    }

    /** 按固定目录读一个指标；名字不在目录里时抛参数异常（调用方转成工具失败）。 */
    String metric(String name) {
        String wanted = name == null ? "" : name.strip();
        Metric metric = METRICS.stream()
                .filter(candidate -> candidate.name().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知指标"));
        Double value = prometheus.scalar(metric.query());
        if (value == null || !Double.isFinite(value)) {
            return metric.name() + " = 不可达";
        }
        return metric.name() + " = " + trimmed(value, metric.decimals()) + " " + metric.unit();
    }

    /**
     * 工具用的日志查询：unit 与 level 都是枚举，拼进查询前先映射成常量。
     * 用户/模型给的值不会成为 LogQL 片段。
     */
    String logs(String unit, String level, int minutes, int limit) {
        String selector = switch (unit == null ? "" : unit) {
            case "paideia" -> "{unit=\"paideia.service\"}";
            case "laya" -> "{unit=\"laya.service\"}";
            default -> throw new IllegalArgumentException("未知单元");
        };
        String filter = switch (level == null ? "" : level) {
            case "error" -> " |~ \"(?i)(error|exception|failed)\"";
            case "warn" -> " |~ \"(?i)(warn|warning)\"";
            case "all" -> "";
            default -> throw new IllegalArgumentException("未知级别");
        };
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(minutes));
        List<LokiClient.LogLine> lines = loki.queryRange(selector + filter, from, to, limit);
        if (lines.isEmpty()) {
            return "无匹配日志（或 Loki 不可达）";
        }
        return formatLogLines(lines, limit, properties.getDiagnosis().getMaxEvidenceChars());
    }

    /** 当前 firing 的告警（最多 10 条）。 */
    String firingAlerts() {
        List<PrometheusClient.Sample> samples = prometheus.query("ALERTS{alertstate=\"firing\"}");
        if (samples.isEmpty()) {
            return "无 firing 告警（或 Prometheus 不可达）";
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (PrometheusClient.Sample sample : samples) {
            if (count++ >= 10) {
                out.append("…还有更多告警\n");
                break;
            }
            out.append("- ").append(sample.labels().getOrDefault("alertname", "?"));
            appendLabel(out, sample.labels(), "severity");
            appendLabel(out, sample.labels(), "instance");
            appendLabel(out, sample.labels(), "job");
            out.append('\n');
        }
        return out.toString();
    }

    private String formatSummary(OpsSummary value) {
        StringBuilder out = new StringBuilder();
        out.append("采集时间=").append(value.collectedAt())
                .append(" Prometheus=").append(value.prometheusReachable() ? "可达" : "不可达").append('\n');
        for (OpsSummary.Group group : value.groups()) {
            out.append('[').append(group.title()).append("] ").append(group.status());
            if (group.reason() != null && !group.reason().isBlank()) {
                out.append(" — ").append(group.reason());
            }
            out.append('\n');
            for (OpsSummary.Metric metric : group.metrics()) {
                out.append("  ").append(metric.name()).append(" = ").append(metricText(metric)).append('\n');
            }
        }
        return out.toString();
    }

    private static String metricText(OpsSummary.Metric metric) {
        if (metric.text() != null) {
            return metric.text();
        }
        if (metric.value() == null) {
            return "—";
        }
        return trimmed(metric.value(), 2) + (metric.unit() == null ? "" : " " + metric.unit());
    }

    private String formatMetrics() {
        StringBuilder out = new StringBuilder();
        for (Metric metric : METRICS) {
            Double value = prometheus.scalar(metric.query());
            out.append(metric.name()).append(" = ");
            if (value == null || value.isNaN() || value.isInfinite()) {
                out.append("不可达").append('\n');
            } else {
                out.append(trimmed(value, metric.decimals())).append(' ').append(metric.unit()).append('\n');
            }
        }
        return out.toString();
    }

    private static void appendLabel(StringBuilder out, Map<String, String> labels, String key) {
        String value = labels.get(key);
        if (value != null && !value.isBlank()) {
            out.append(' ').append(key).append('=').append(value);
        }
    }

    private String formatDiagnosisLogs(int maxLines, int maxChars) {
        if (maxChars <= 0) {
            return "（证据预算已用完，未采集日志）";
        }
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(1));
        List<LokiClient.LogLine> lines = new ArrayList<>();
        // 拆成两条固定查询而不是一条正则：unit 值里有 `.`，拼进正则要转义两层，容易错。
        lines.addAll(loki.queryRange(
                "{unit=\"paideia.service\"} |~ \"(?i)(error|exception|failed|timeout)\"",
                from, to, maxLines));
        lines.addAll(loki.queryRange(
                "{unit=\"laya.service\"} |~ \"(?i)(error|exception|failed|timeout)\"",
                from, to, maxLines));
        if (lines.isEmpty()) {
            return "无匹配日志（或 Loki 不可达）";
        }
        return formatLogLines(lines, maxLines, maxChars);
    }

    private static String formatLogLines(List<LokiClient.LogLine> lines, int maxLines, int maxChars) {
        List<LokiClient.LogLine> sorted = new ArrayList<>(lines);
        sorted.sort((a, b) -> b.at().compareTo(a.at()));
        List<String> rows = new ArrayList<>(sorted.size());
        for (LokiClient.LogLine line : sorted) {
            rows.add(LOG_TIME.format(line.at()) + " " + line.line());
        }
        return OpsLogRedactor.joinRedacted(rows, maxLines, maxChars);
    }

    private String formatDeploy() {
        Double age = prometheus.scalar("time() - max(paideia_deploy_epoch_seconds)");
        if (age == null || !Double.isFinite(age)) {
            return "未记录发版时间（deploy.prom 可能尚未写入）";
        }
        String version = "unknown";
        List<PrometheusClient.Sample> builds = prometheus.query("paideia_build_info");
        if (!builds.isEmpty()) {
            version = builds.getFirst().labels().getOrDefault("version", "unknown");
        }
        return "最近发版 " + humanAge(age.longValue()) + "前，版本 " + version;
    }

    private static String humanAge(long seconds) {
        if (seconds < 3600) {
            return (seconds / 60) + " 分钟";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + " 小时";
        }
        return (seconds / 86_400) + " 天";
    }

    private static String trimmed(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private record Metric(String name, String query, int decimals, String unit) {
    }
}
