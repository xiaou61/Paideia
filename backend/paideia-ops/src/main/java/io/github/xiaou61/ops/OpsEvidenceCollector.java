package io.github.xiaou61.ops;

import io.github.xiaou61.ai.OpsDiagnoser;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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

    /** 证据里的时间都用运维看到的本地钟点（服务器与使用方都在 +08:00）。 */
    private static final DateTimeFormatter LOG_CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    /**
     * 三段日志查询（WORK-086）：异常 / 生命周期 / 上下文。顺序有意义——预算不够时
     * 前两段先落地，上下文段是锦上添花。
     */
    private static final List<String> LOG_SEGMENTS = List.of(
            " |~ \"(?i)(error|exception|failed|timeout)\"",
            " |~ \"(?i)(started|starting|stopping|shutdown|restart|killed|oom|exit)\"",
            "");

    /** 生命周期段与上下文段的条数上限：它们是佐证，不参与"报了什么错"的主体。 */
    private static final int LIFECYCLE_LOG_LIMIT = 20;
    private static final int CONTEXT_LOG_LIMIT = 2;

    /** Spring Boot 的启动完成行："Started PaideiaApplication in 28.4 seconds"。 */
    private static final Pattern RESTART_LINE =
            Pattern.compile("(?i)started\\s+\\S*application\\s+in\\s+");

    private static final String LOG_ERROR_QUERY =
            "sum(count_over_time({unit=\"paideia.service\"} |~ \"(?i)(error|exception|failed)\" [5m]))";

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
                    // 没有 5xx 时分子为空，`x / y` 整条也会变空；`or vector(0)` 让它落成 0。
                    "100 * ((sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[5m]))"
                            + " or vector(0)) / clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 0.000001))",
                    2, "%"),
            new Metric("http_p95_seconds",
                    "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))",
                    3, "s"),
            new Metric("jvm_heap_percent",
                    "100 * sum(jvm_memory_used_bytes{area=\"heap\"}) / sum(jvm_memory_max_bytes{area=\"heap\"})",
                    1, "%"),
            new Metric("mysql_connections", "mysql_global_status_threads_connected", 0, "个"),
            new Metric("redis_memory_mb", "redis_memory_used_bytes / 1024 / 1024", 1, "MB"),
            new Metric("scheduled_failures", "count(paideia_scheduled_last_success < 1) or vector(0)", 0, "个"),
            // 以下 9 项是 WORK-086 补的"运行态"（REQ-003）：连接池、GC、线程、负载与重启/内存
            // 杀进程是那次事故里真实存在、却完全没进证据的信号。
            new Metric("process_uptime", "time() - process_start_time_seconds", 0, "秒"),
            new Metric("hikari_pending", "max(hikaricp_connections_pending)", 0, "连接"),
            new Metric("hikari_acquire_max", "max(hikaricp_connections_acquire_seconds_max)", 3, "秒"),
            new Metric("hikari_timeouts", "sum(increase(hikaricp_connections_timeout_total[1h]))", 0, "次/小时"),
            new Metric("jvm_gc_pause_max", "max(jvm_gc_pause_seconds_max)", 3, "秒"),
            new Metric("jvm_threads_blocked", "sum(jvm_threads_states_threads{state=\"blocked\"})", 0, "线程"),
            new Metric("node_load1", "node_load1", 2, ""),
            new Metric("node_oom_kill", "increase(node_vmstat_oom_kill[1h])", 0, "次/小时"),
            new Metric("mysql_threads", "mysql_global_status_threads_connected", 0, "连接"));

    private final OpsProperties properties;
    private final OpsSummaryService summary;
    private final PrometheusClient prometheus;
    private final LokiClient loki;
    private final TempoClient tempo;
    private final PatternQueryService patterns;

    /** 生产构造器；其余带客户端的构造器只给测试用。 */
    @Autowired
    OpsEvidenceCollector(OpsProperties properties, OpsSummaryService summary, PatternQueryService patterns) {
        this(properties, summary,
                new PrometheusClient(properties.getPrometheusUrl(), properties.getQueryTimeoutMs()),
                new LokiClient(properties.getLokiUrl(), properties.getQueryTimeoutMs()),
                new TempoClient(properties.getTempoUrl(), properties.getQueryTimeoutMs()),
                patterns);
    }

    OpsEvidenceCollector(OpsProperties properties, OpsSummaryService summary,
                         PrometheusClient prometheus, LokiClient loki) {
        this(properties, summary, prometheus, loki,
                new TempoClient(properties.getTempoUrl(), properties.getQueryTimeoutMs()), null);
    }

    OpsEvidenceCollector(OpsProperties properties, OpsSummaryService summary,
                         PrometheusClient prometheus, LokiClient loki, TempoClient tempo) {
        this(properties, summary, prometheus, loki, tempo, null);
    }

    OpsEvidenceCollector(OpsProperties properties, OpsSummaryService summary,
                         PrometheusClient prometheus, LokiClient loki, TempoClient tempo,
                         PatternQueryService patterns) {
        this.properties = properties;
        this.summary = summary;
        this.prometheus = prometheus;
        this.loki = loki;
        this.tempo = tempo;
        this.patterns = patterns;
    }

    OpsDiagnoser.Evidence collect(String trigger, String alertName, String question) {
        return collect(trigger, alertName, question, null);
    }

    OpsDiagnoser.Evidence collect(String trigger, String alertName, String question, String context) {
        return collect(trigger, alertName, question, context, null);
    }

    /**
     * 采集证据。窗口为 null 时退回"从触发时刻往前一段"的默认窗口（WORK-086）：
     * 调用方拿得到事件首末时间就该传进来，否则报告只能看到触发之后的那一小时。
     */
    OpsDiagnoser.Evidence collect(String trigger, String alertName, String question,
                                  String context, OpsEvidenceWindow window) {
        OpsProperties.Diagnosis config = properties.getDiagnosis();
        OpsEvidenceWindow evidenceWindow = window == null ? defaultWindow(config) : window;
        // 日志只打一次 Loki：运行态要跟"日志里启动了几次"交叉验证，两边各查一次会拉到
        // 不同瞬间的数据，反而制造出并不存在的差异。
        // 证据预算整体为 0 时连查询都不发：预算配置错了不该顺带压垮 Loki。
        Map<String, List<LokiClient.LogLine>> unitLogs = config.getMaxEvidenceChars() <= 0
                ? Map.of()
                : collectUnitLogs(evidenceWindow, config.getLogsPerUnit());

        String summaryText = formatSummary(summary.summary());
        String metricsText = formatMetrics() + formatRuntime(evidenceWindow, countRestarts(unitLogs));
        String baselinesText = formatBaselines();
        String contextText = context == null ? "" : context.strip();
        String alertsText = firingAlerts();
        String deploysText = formatDeploy();
        String tracesText = formatTraces();
        // 模板聚类证据读的是本地表（同步任务的产物），不额外打 Loki；为 null（测试/未装配）时留空。
        String patternsText = patterns == null ? "" : patterns.evidenceText(Instant.now());

        int nonLogChars = summaryText.length() + metricsText.length() + baselinesText.length()
                + contextText.length()
                + alertsText.length() + patternsText.length()
                + tracesText.length() + deploysText.length();
        int logBudget = Math.max(0, config.getMaxEvidenceChars() - nonLogChars);
        String logsText = renderLogs(unitLogs, evidenceWindow, config.getSignatureTop(), logBudget);

        return new OpsDiagnoser.Evidence(
                trigger, alertName, question,
                summaryText, metricsText, baselinesText, contextText,
                alertsText, logsText, patternsText, tracesText, deploysText);
    }

    private OpsEvidenceWindow defaultWindow(OpsProperties.Diagnosis config) {
        return OpsEvidenceWindow.of(null, null, Instant.now(),
                config.getEvidenceWindowMinutes(), config.getEvidenceWindowMaxMinutes());
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
            return metric.name() + " = 无数据";
        }
        return metric.name() + " = " + trimmed(value, metric.decimals()) + " " + metric.unit();
    }

    /**
     * 工具用的日志查询：unit 必须命中配置里的白名单，level 是枚举。
     * 用户/模型给的值不会成为 LogQL 片段（WORK-049：单元列表从代码移到配置）。
     */
    String logs(String unit, String level, int minutes, int limit) {
        String wanted = unit == null ? "" : unit.strip();
        if (!properties.getDiagnosis().getUnits().contains(wanted)) {
            throw new IllegalArgumentException("未知单元");
        }
        String selector = "{unit=\"" + wanted + ".service\"}";
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
            // 我们的告警都由 Grafana 求值，不进 Prometheus 的 ALERTS——这里查不到不等于没告警。
            return "（Prometheus 侧无 firing 记录；本项目的告警由 Grafana 求值，"
                    + "触发告警名见上，Grafana 的实况以运维页的告警列表为准）\n"
                    + "无 firing 告警（或 Prometheus 不可达）\n";
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
                out.append("无数据").append('\n');
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

    /**
     * 基线：当前值 vs 1h/24h/7d 同时段。样本不足写“基线不足”，不把缺失当成 0。
     * 录制规则在 deploy/ops/prometheus-rules.yml；这里只读结果。
     */
    private String formatBaselines() {
        StringBuilder out = new StringBuilder();
        metricBaseline(out, "cpu", "paideia:baseline_cpu", "%");
        metricBaseline(out, "http_5xx_percent", "paideia:baseline_5xx", "%");
        metricBaseline(out, "http_p95_seconds", "paideia:baseline_p95", "s");
        logBaseline(out);
        sloSummary(out);
        return out.toString();
    }

    /**
     * SLO 燃尽率（WORK-043 TASK-020）。
     *
     * <p>为什么放在证据里：SLO 告警的 alertname 只说"快烧/慢烧"，模型看不到具体倍数就只能猜。
     * 这里把五个目标的当前 SLI 与燃尽率一起给它，报告才能直接引用数字。
     * 录制规则在 `deploy/ops/prometheus-rules.yml` 的 `paideia-slo` 组。
     */
    private void sloSummary(StringBuilder out) {
        // 目标名 → (SLI 序列, 燃尽率序列, 单位)。只放 1h/6h 两个窗口：快烧看 1h、慢烧看 6h。
        String[][] specs = {
                {"http_availability", "paideia:sli_http_availability:ratio1h", "paideia:slo_burn_rate:http_availability_1h"},
                {"http_latency", "paideia:sli_http_latency:ratio1h", "paideia:slo_burn_rate:http_latency_1h"},
                {"ai_success", "paideia:sli_ai_success:ratio1h", "paideia:slo_burn_rate:ai_success_1h"},
                {"login_success", "paideia:sli_login_success:ratio1h", "paideia:slo_burn_rate:login_success_1h"},
        };
        for (String[] spec : specs) {
            Double sli = prometheus.scalar(spec[1]);
            Double burn = prometheus.scalar(spec[2]);
            out.append("slo_").append(spec[0]).append(' ');
            // **NaN 也是"不可计算"**：分子分母都没有样本时 Prometheus 给的是 NaN 而不是空，
            // 只判 null 会把 NaN 写成 "sli_1h=NaN%"——模型会当成一个真实数值去推理
            // （2026-10-27 生产演练的报告里就出现过 "slo_ai_success sli_1h=NaN%"）。
            if (sli == null || sli.isNaN() || sli.isInfinite()) {
                out.append("= 无流量（SLI 不可计算）\n");
                continue;
            }
            out.append("sli_1h=").append(trimmed(sli * 100, 3)).append('%')
                    .append(" burn_1h=").append(burn == null || burn.isNaN() || burn.isInfinite()
                            ? "—" : trimmed(burn, 2) + "x")
                    .append('\n');
        }
        Double scheduled = prometheus.scalar("paideia:sli_scheduled_success:ratio5m");
        out.append("slo_scheduled_success sli=")
                .append(scheduled == null ? "无数据" : trimmed(scheduled * 100, 1) + "%")
                .append('\n');
    }

    private void metricBaseline(StringBuilder out, String name, String prefix, String unit) {
        Double current = prometheus.scalar(prefix + ":current");
        Double samples = prometheus.scalar(prefix + ":samples7d");
        Double baseline = prometheus.scalar(prefix + ":baseline");
        if (current == null || samples == null || samples < 100 || baseline == null) {
            out.append(name).append(" = 基线不足（samples_7d=")
                    .append(samples == null ? "无数据" : trimmed(samples, 0))
                    .append("）\n");
            return;
        }
        Double oneHour = prometheus.scalar(prefix + ":1h");
        Double day = prometheus.scalar(prefix + ":24h");
        Double sevenDay = prometheus.scalar(prefix + ":7d");
        Double ratio = prometheus.scalar(prefix + ":ratio");
        Double z = prometheus.scalar(prefix + ":z");
        out.append(name).append(" current=").append(trimmed(current, 2)).append(unit)
                .append(" 1h=").append(valueOrDash(oneHour, unit))
                .append(" 24h=").append(valueOrDash(day, unit))
                .append(" 7d_same_hour=")
                .append(sevenDay == null ? "—（回退）" : trimmed(sevenDay, 2) + unit)
                .append(" baseline=").append(trimmed(baseline, 2)).append(unit)
                .append(" ratio=").append(ratio == null ? "—" : trimmed(ratio, 2) + "x")
                // z 是 WORK-049 加的方差口径：比值看不出「平时就抖」与「突然不抖了」的区别
                .append(" z=").append(z == null || !Double.isFinite(z) ? "—" : trimmed(z, 2))
                .append(" samples_7d=").append(trimmed(samples, 0))
                .append('\n');
    }

    private void logBaseline(StringBuilder out) {
        Instant now = Instant.now();
        Double current = loki.scalar(LOG_ERROR_QUERY, now);
        Double week = loki.scalar(LOG_ERROR_QUERY + " offset 1w", now);
        if (current == null) {
            out.append("log_errors = 无数据\n");
            return;
        }
        out.append("log_errors current=").append(trimmed(current, 0));
        if (week == null || week <= 0) {
            out.append(" 7d_same_hour=基线不足\n");
        } else {
            out.append(" 7d_same_hour=").append(trimmed(week, 0))
                    .append(" ratio=").append(trimmed(current / week, 2)).append("x\n");
        }
    }

    private static String valueOrDash(Double value, String unit) {
        return value == null || !Double.isFinite(value) ? "—" : trimmed(value, 2) + unit;
    }

    /** 最近 1 小时的错误/慢 trace；最多 3 条，每条只带最慢 span 与白名单属性。 */
    private String formatTraces() {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(1));
        List<TempoClient.TraceSummary> traces = tempo.search(from, to, 3);
        if (traces.isEmpty()) {
            return "无匹配 trace（或 Tempo 不可达）";
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (TempoClient.TraceSummary trace : traces) {
            if (count++ >= 3) {
                break;
            }
            out.append("trace=").append(trace.traceId())
                    .append(" root=").append(trace.rootName())
                    .append(" duration=").append(trace.durationMs()).append("ms");
            if (trace.error()) {
                out.append(" error=true");
            }
            out.append('\n');
            tempo.trace(trace.traceId()).ifPresent(detail -> {
                TempoClient.Span slowest = detail.slowest();
                if (slowest != null) {
                    out.append("  slowest=").append(slowest.name())
                            .append(" ").append(slowest.durationMs()).append("ms")
                            .append(" status=").append(slowest.status());
                    Map<String, String> attrs = TempoTraceRedactor.filter(slowest.attributes());
                    if (!attrs.isEmpty()) {
                        out.append(" attrs=").append(attrs);
                    }
                    out.append('\n');
                }
            });
        }
        return out.toString();
    }

    /**
     * 诊断用的日志证据：窗口由事件首末时间决定，每个单元分三段捞（WORK-086）。
     *
     * <p>三段的意义不同：异常段回答"报了什么错"，生命周期段回答"进程是不是重启过"，
     * 上下文段给"没有 error 关键字但发生在同一时刻"的行一个机会。只捞 error 会漏掉
     * 系统级重启前的 Stopping、以及只为佐证时间线的普通行。
     */
    private Map<String, List<LokiClient.LogLine>> collectUnitLogs(OpsEvidenceWindow window, int perUnitLines) {
        Map<String, List<LokiClient.LogLine>> byUnit = new LinkedHashMap<>();
        for (String unit : properties.getDiagnosis().getUnits()) {
            byUnit.put(unit, queryUnit(unit, window, perUnitLines));
        }
        return byUnit;
    }

    private String renderLogs(Map<String, List<LokiClient.LogLine>> unitLogs,
                              OpsEvidenceWindow window, int signatureTop, int maxChars) {
        if (maxChars <= 0) {
            return "（证据预算已用完，未采集日志）";
        }
        List<String> rows = new ArrayList<>();
        rows.add("窗口 " + LOG_CLOCK.format(window.start()) + "–" + LOG_CLOCK.format(window.end())
                + "（" + window.source() + "）");
        for (Map.Entry<String, List<LokiClient.LogLine>> entry : unitLogs.entrySet()) {
            String unit = entry.getKey();
            List<LokiClient.LogLine> lines = entry.getValue();
            rows.add("[" + unit + "]");
            if (lines.isEmpty()) {
                rows.add("（该 unit 在窗口内无日志；可能未接入 Loki，alloy 白名单待核实）");
                continue;
            }
            rows.addAll(renderSignatures(lines, signatureTop));
        }
        return OpsLogRedactor.joinRedacted(rows, rows.size(), maxChars);
    }

    /** 日志里"启动了几次"：与 Prometheus 的 {@code changes()} 互相印证。 */
    private static int countRestarts(Map<String, List<LokiClient.LogLine>> unitLogs) {
        int count = 0;
        for (List<LokiClient.LogLine> lines : unitLogs.values()) {
            for (LokiClient.LogLine line : lines) {
                if (RESTART_LINE.matcher(line.line()).find()) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 运行态：进程活了多久、窗口内重启几次、有没有被 OOM kill（WORK-086 REQ-003）。
     *
     * <p>重启次数用两个来源交叉验证：`changes(process_start_time_seconds[窗口])` 是权威计数，
     * 但它只给数字；日志里有具体时间点。两者不一致时把两个数字都写出来——这本身就是线索
     * （比如进程被外部重启但日志还没落盘，或 Loki 少采了一段）。
     */
    private String formatRuntime(OpsEvidenceWindow window, int restartLogCount) {
        int minutes = Math.max(1, window.minutes());
        StringBuilder out = new StringBuilder("运行态（窗口 ").append(minutes).append(" 分钟）：\n");

        Double uptime = prometheus.scalar("time() - process_start_time_seconds");
        out.append("  进程已运行 ").append(uptime == null || !Double.isFinite(uptime)
                ? "无数据" : humanAge(uptime.longValue())).append('\n');

        Double restarts = prometheus.scalar("changes(process_start_time_seconds[" + minutes + "m])");
        String restartText = restarts == null || !Double.isFinite(restarts)
                ? "无数据" : String.valueOf(restarts.longValue());
        out.append("  进程重启 ").append(restartText).append(" 次")
                .append("（窗口内 changes）");
        boolean mismatch = restarts != null && Double.isFinite(restarts)
                && restarts.longValue() != restartLogCount;
        if (mismatch) {
            out.append("；日志记录 ").append(restartLogCount).append(" 次启动（两者不一致，"
                    + "以日志时间点为准，并注意 Loki 可能少采）");
        } else if (restarts == null || !Double.isFinite(restarts)) {
            out.append("；日志记录 ").append(restartLogCount).append(" 次启动");
        }
        out.append('\n');

        Double oom = prometheus.scalar("increase(node_vmstat_oom_kill[" + minutes + "m])");
        out.append("  内存 OOM kill ").append(oom == null || !Double.isFinite(oom)
                ? "无数据" : String.valueOf(oom.longValue())).append(" 次")
                .append("（窗口内 increase）\n");
        return out.toString();
    }

    /** 三段查询后按 (时间, 原文) 去重：上下文段几乎一定会把异常段的行再捞回来一次。 */
    private List<LokiClient.LogLine> queryUnit(String unit, OpsEvidenceWindow window, int perUnitLines) {
        String selector = "{unit=\"" + unit + ".service\"}";
        List<LokiClient.LogLine> collected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Integer> limits = List.of(
                perUnitLines,
                Math.max(0, Math.min(LIFECYCLE_LOG_LIMIT, perUnitLines)),
                Math.max(0, Math.min(CONTEXT_LOG_LIMIT, perUnitLines)));
        for (int i = 0; i < LOG_SEGMENTS.size(); i++) {
            int limit = limits.get(i);
            if (limit <= 0) {
                continue;
            }
            for (LokiClient.LogLine line : loki.queryRange(
                    selector + LOG_SEGMENTS.get(i), window.start(), window.end(), limit)) {
                if (seen.add(line.at() + "\u0000" + line.line())) {
                    collected.add(line);
                }
            }
        }
        return collected;
    }

    /**
     * 按签名聚合后逐条渲染：同一签名只留"首次 … 末次 ×次数 + 一条样例"。
     *
     * <p>错误行不受 {@code signatureTop} 限制——按次数排序会把"只出现一次但正是原因"
     * 的异常挤掉，那恰好是最该给模型看的那条。
     */
    static List<String> renderSignatures(List<LokiClient.LogLine> lines, int signatureTop) {
        List<LokiClient.LogLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing(LokiClient.LogLine::at));

        Map<String, Signature> bySignature = new LinkedHashMap<>();
        for (LokiClient.LogLine line : sorted) {
            String signature = OpsLogSignature.of(line.line());
            if (signature.isEmpty()) {
                continue;
            }
            Signature existing = bySignature.get(signature);
            if (existing == null) {
                bySignature.put(signature, new Signature(line.at(), line.at(), 1, line.line()));
            } else {
                bySignature.put(signature, existing.bumped(line.at(), line.line()));
            }
        }

        List<String> rows = new ArrayList<>(bySignature.size());
        int kept = 0;
        for (Signature stat : bySignature.values()) {
            if (kept >= signatureTop && !stat.isError()) {
                continue;
            }
            kept++;
            rows.add(stat.render());
        }
        if (kept < bySignature.size()) {
            rows.add("（另有 " + (bySignature.size() - kept) + " 个非异常签名已省略）");
        }
        return rows;
    }

    /** 一个签名的聚合结果；样例取最后一次出现的那行（往往带最完整的错误上下文）。 */
    private record Signature(Instant first, Instant last, int count, String sample) {

        private static final Pattern ERROR_HINT =
                Pattern.compile("(?i)(error|exception|failed|timeout|fatal)");

        boolean isError() {
            return ERROR_HINT.matcher(sample).find();
        }

        Signature bumped(Instant at, String line) {
            return new Signature(first, at, count + 1, line);
        }

        String render() {
            String head = count == 1
                    ? LOG_CLOCK.format(first)
                    : LOG_CLOCK.format(first) + " … " + LOG_CLOCK.format(last) + " ×" + count;
            return head + " " + sample.strip();
        }
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
