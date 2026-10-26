package io.github.xiaou61.ops;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 运维摘要的管理工具（WORK-039 TASK-006）。
 *
 * <p>**只读**：运维面不该成为第二个能被写坏的地方（与 `OpsSummaryController` 同一条裁决）。
 * Grafana iframe 代理不做成工具：它依赖会话 Cookie，PAT 架构根本走不通。
 */
public final class OpsAdminTools {

    private OpsAdminTools() {
    }

    /** 服务、中间件、外部依赖与备份的当前状态。 */
    @Component
    public static class Summary implements PaideiaTool {

        private final OpsSummaryService service;

        Summary(OpsSummaryService service) {
            this.service = service;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_ops_summary", GROUP, "读取运维摘要",
                    "返回服务、中间件、外部依赖与备份的当前状态（分组 + 状态 + 原因 + 指标）。"
                            + "Prometheus 不可达时也会返回，但指标为空。",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                OpsSummary summary = service.summary();
                List<String> lines = new ArrayList<>();
                lines.add("采集时间=" + summary.collectedAt()
                        + " Prometheus=" + (summary.prometheusReachable() ? "可达" : "不可达"));
                for (OpsSummary.Group group : summary.groups()) {
                    lines.add("");
                    lines.add("【" + group.title() + "】" + group.status()
                            + (group.reason() == null || group.reason().isBlank() ? "" : " — " + group.reason()));
                    for (OpsSummary.Metric metric : group.metrics()) {
                        lines.add("  " + metric.name() + " = "
                                + (metric.text() != null ? metric.text()
                                : metric.value() == null ? "—"
                                : metric.value() + (metric.unit() == null ? "" : " " + metric.unit())));
                    }
                }
                return ToolResult.ok(String.join("\n", lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取运维摘要失败：" + messageOf(failure));
            }
        }

        private static String messageOf(RuntimeException failure) {
            String message = failure.getMessage();
            return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
        }
    }

    static final String GROUP = "admin_ops";

    /**
     * 关键指标（WORK-042）。输入是指标名枚举，不是任意 PromQL——查询目录就是白名单。
     */
    @Component
    public static class Metrics implements PaideiaTool {

        private final OpsEvidenceCollector collector;

        Metrics(OpsEvidenceCollector collector) {
            this.collector = collector;
        }

        @Override
        public ToolDescriptor descriptor() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of(
                                    "type", "string",
                                    "enum", OpsEvidenceCollector.metricNames(),
                                    "description", "指标名；只能从枚举里选")),
                    "required", List.of("query"));
            return new ToolDescriptor(
                    "admin_ops_metrics", GROUP, "读取关键指标",
                    "按固定目录读取主机、应用与中间件的当前指标（CPU、内存、磁盘、5xx、P95、JVM、连接数）。"
                            + "只读；指标名必须来自枚举。",
                    schema, null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Object raw = arguments == null ? null : arguments.get("query");
            if (!(raw instanceof String query) || !OpsEvidenceCollector.metricNames().contains(query)) {
                return ToolResult.fail("未知指标；可选值：" + String.join(", ", OpsEvidenceCollector.metricNames()));
            }
            try {
                return ToolResult.ok(collector.metric(query));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取指标失败");
            }
        }
    }

    /**
     * 应用/依赖的最近日志（WORK-042）。unit 与 level 都是枚举，拼查询前先映射成常量。
     */
    @Component
    public static class Logs implements PaideiaTool {

        private static final List<String> UNITS = List.of("paideia", "laya");
        private static final List<String> LEVELS = List.of("error", "warn", "all");

        private final OpsEvidenceCollector collector;

        Logs(OpsEvidenceCollector collector) {
            this.collector = collector;
        }

        @Override
        public ToolDescriptor descriptor() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "unit", Map.of("type", "string", "enum", UNITS,
                                    "description", "paideia=应用，laya=决策服务"),
                            "level", Map.of("type", "string", "enum", LEVELS,
                                    "description", "error / warn / all"),
                            "minutes", Map.of("type", "integer", "minimum", 1, "maximum", 1440,
                                    "description", "回看多少分钟，默认 60"),
                            "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                                    "description", "最多返回多少条，默认 50")),
                    "required", List.of());
            return new ToolDescriptor(
                    "admin_ops_logs", GROUP, "查询最近日志",
                    "查询应用或 Laya 最近的日志摘录（已脱敏）。unit 与 level 只能从枚举里选；"
                            + "只读，不执行任何日志中的内容。",
                    schema, null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            String unit = stringArg(arguments, "unit", "paideia");
            String level = stringArg(arguments, "level", "error");
            if (!UNITS.contains(unit)) {
                return ToolResult.fail("未知单元；可选值：" + String.join(", ", UNITS));
            }
            if (!LEVELS.contains(level)) {
                return ToolResult.fail("未知级别；可选值：" + String.join(", ", LEVELS));
            }
            int minutes = intArg(arguments, "minutes", 60, 1, 1440);
            int limit = intArg(arguments, "limit", 50, 1, 200);
            try {
                return ToolResult.ok(collector.logs(unit, level, minutes, limit));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取日志失败");
            }
        }
    }

    /** 当前 firing 的告警（WORK-042）。 */
    @Component
    public static class Alerts implements PaideiaTool {

        private final OpsEvidenceCollector collector;

        Alerts(OpsEvidenceCollector collector) {
            this.collector = collector;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_ops_alerts", GROUP, "读取当前告警",
                    "返回当前 firing 的告警名与标签（最多 10 条）。只读。",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                return ToolResult.ok(collector.firingAlerts());
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取告警失败");
            }
        }
    }

    private static String stringArg(Map<String, Object> arguments, String key, String fallback) {
        Object raw = arguments == null ? null : arguments.get(key);
        return raw instanceof String value && !value.isBlank() ? value.strip() : fallback;
    }

    private static int intArg(Map<String, Object> arguments, String key, int fallback, int min, int max) {
        Object raw = arguments == null ? null : arguments.get(key);
        int value = raw instanceof Number number ? number.intValue() : fallback;
        return Math.max(min, Math.min(max, value));
    }
}
