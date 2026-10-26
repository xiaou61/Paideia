package io.github.xiaou61.ai.internal.tools;

import io.github.xiaou61.ai.internal.feedback.AiFeedbackService;
import io.github.xiaou61.ai.internal.telemetry.AiRunAdminService;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * AI 观测的管理工具组（WORK-039 TASK-004，5 个）。
 *
 * <p>全部只读：统计系统不该成为第二个能被写坏的地方（与 `AiRunAdminController` 同一条裁决）。
 *
 * <p>概览把管理端页面的三块数字（概览、采集健康、积分对账）合成一次返回——它们本来就是
 * "今天 AI 怎么样"的三个分栏，分开调用只会让模型多跑两趟、还可能拼出互相矛盾的时间窗。
 */
public final class AiAdminTools {

    private static final String GROUP = "admin_ai";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SERIES_POINTS = 60;
    private static final int MAX_SPANS = 40;
    private static final int MAX_PAYLOAD_CHARS = 2000;

    private AiAdminTools() {
    }

    /** 概览 + 采集健康 + 积分对账。 */
    @Component
    public static class Overview implements PaideiaTool {

        private final AiRunAdminService runs;

        Overview(AiRunAdminService runs) {
            this.runs = runs;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_ai_overview", GROUP, "AI 调用概览与健康",
                    "一次返回三块数字：调用量/成功率/分位耗时/token/扣分与排行、采集健康"
                            + "（队列积压、丢弃、写失败、采样率）、按天的积分对账。"
                            + "不传时间窗时默认最近 7 天。",
                    schema(Map.of(
                            "from", str("起始时间，ISO-8601；不传默认 7 天前"),
                            "to", str("结束时间，ISO-8601；不传默认现在"),
                            "kind", str("场景过滤（如 assistant / digest / interview）"),
                            "status", str("状态过滤：ok / failed / timeout / cancelled / interrupted"),
                            "model", str("模型过滤"),
                            "targetUserId", num("只看某个账号")),
                            new String[0]),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Instant from = instantOf(arguments, "from");
                Instant to = instantOf(arguments, "to");
                AiRunAdminService.Overview overview = runs.overview(from, to,
                        stringOf(arguments, "kind"), stringOf(arguments, "status"),
                        stringOf(arguments, "model"), longOf(arguments, "targetUserId"));
                AiRunAdminService.Health health = runs.health();
                AiRunAdminService.Reconcile reconcile = runs.reconcile(from, to);

                List<String> lines = new ArrayList<>();
                lines.add("【概览】窗口 " + overview.from() + " ~ " + overview.to()
                        + (overview.clamped() ? "（已收敛到保留期）" : ""));
                lines.add("调用=" + overview.total() + " 成功=" + overview.byStatus().ok()
                        + " 失败=" + overview.byStatus().failed()
                        + " 超时=" + overview.byStatus().timeout()
                        + " 成功率=" + percent(overview.successRate()));
                lines.add("耗时 p50=" + ms(overview.durationP50()) + " p95=" + ms(overview.durationP95())
                        + " 首字 p50=" + ms(overview.ttftP50()) + " p95=" + ms(overview.ttftP95()));
                lines.add("token 输入=" + overview.inputTokens() + " 输出=" + overview.outputTokens()
                        + " 缓存读=" + overview.cacheReadTokens()
                        + " 扣分=" + overview.credits() + "（名义 " + overview.nominalCredits()
                        + "，缓存省下 " + percent(overview.cacheSavedRatio()) + "）");
                lines.add("采样率=" + percent(overview.sampling()));
                lines.add("按场景：" + counts(overview.byKind()));
                lines.add("按模型：" + counts(overview.byModel()));
                lines.add("错误类型：" + counts(overview.errorTypes()));
                lines.add("工具调用：" + counts(overview.tools()));
                lines.add("");
                lines.add("【采集健康】启用=" + health.enabled()
                        + " 队列=" + health.queueDepth() + "/" + health.queueCapacity()
                        + " 丢弃=" + health.dropped() + " 写失败=" + health.writeFailures()
                        + " 采样率=" + percent(health.sampling())
                        + " 最近成功=" + (health.lastSuccessAt() == null ? "无" : health.lastSuccessAt()));
                lines.add("");
                lines.add("【积分对账】阈值=" + reconcile.threshold() + "（|记录-流水| 超过即异常）");
                if (reconcile.days().isEmpty()) {
                    lines.add("窗口内没有对账数据。");
                } else {
                    for (AiRunAdminService.ReconcileDay day : reconcile.days()) {
                        lines.add(day.day() + " 记录=" + day.recorded() + " 流水=" + day.ledger()
                                + " 差=" + day.diff() + (day.abnormal() ? " **异常**" : ""));
                    }
                }
                return ToolResult.ok(join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取 AI 概览失败：" + messageOf(failure));
            }
        }
    }

    /** 分桶趋势。 */
    @Component
    public static class Series implements PaideiaTool {

        private final AiRunAdminService runs;

        Series(AiRunAdminService runs) {
            this.runs = runs;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_ai_series", GROUP, "AI 调用趋势",
                    "按时间分桶的趋势序列（空桶补零）。返回每桶的调用量、成功/失败/超时、"
                            + "分位耗时、token 与扣分。点太多时只返回最后 60 个桶。",
                    schema(Map.of(
                            "from", str("起始时间，ISO-8601"),
                            "to", str("结束时间，ISO-8601"),
                            "kind", str("场景过滤"),
                            "status", str("状态过滤"),
                            "model", str("模型过滤"),
                            "targetUserId", num("只看某个账号")),
                            new String[0]),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                AiRunAdminService.Series series = runs.series(instantOf(arguments, "from"),
                        instantOf(arguments, "to"), stringOf(arguments, "kind"),
                        stringOf(arguments, "status"), stringOf(arguments, "model"),
                        longOf(arguments, "targetUserId"));
                List<AiRunAdminService.SeriesPoint> points = series.points();
                int skip = Math.max(0, points.size() - MAX_SERIES_POINTS);
                List<String> lines = new ArrayList<>();
                lines.add("桶=" + series.bucket() + " 时区=" + series.zone()
                        + " 窗口=" + series.from() + " ~ " + series.to()
                        + " 采样率=" + percent(series.sampling())
                        + " 共 " + points.size() + " 个桶"
                        + (skip > 0 ? "（只显示最后 " + MAX_SERIES_POINTS + " 个）" : ""));
                for (AiRunAdminService.SeriesPoint point : points.subList(skip, points.size())) {
                    lines.add(point.at() + " 调用=" + point.total() + " 成功=" + point.ok()
                            + " 失败=" + point.failed() + " 超时=" + point.timeout()
                            + " p95=" + ms(point.durationP95()) + " 扣分=" + point.credits());
                }
                return ToolResult.ok(join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取趋势失败：" + messageOf(failure));
            }
        }
    }

    /** 调用明细列表。 */
    @Component
    public static class ListRuns implements PaideiaTool {

        private final AiRunAdminService runs;

        ListRuns(AiRunAdminService runs) {
            this.runs = runs;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_ai_list_runs", GROUP, "列出 AI 调用",
                    "分页列出 AI 调用明细，可按时间窗、场景（kind）、状态、模型、账号与 traceId 前缀筛选。"
                            + "返回 traceId、场景、模型、状态、耗时、token 与扣分；"
                            + "看单轮细节用 admin_ai_get_run。",
                    schema(Map.of(
                            "from", str("起始时间，ISO-8601"),
                            "to", str("结束时间，ISO-8601"),
                            "kind", str("场景过滤"),
                            "status", str("状态过滤"),
                            "model", str("模型过滤"),
                            "targetUserId", num("只看某个账号"),
                            "traceId", str("traceId 前缀"),
                            "page", num("页码，从 1 开始"),
                            "size", num("每页条数，1-100，默认 20")),
                            new String[0]),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                PageResult<AiRunAdminService.RunSummary> page = runs.page(
                        instantOf(arguments, "from"), instantOf(arguments, "to"),
                        stringOf(arguments, "kind"), stringOf(arguments, "status"),
                        longOf(arguments, "targetUserId"), stringOf(arguments, "model"),
                        stringOf(arguments, "traceId"), pageOf(arguments));
                if (page.items().isEmpty()) {
                    return ToolResult.ok("没有符合条件的调用（共 " + page.total() + " 条）。");
                }
                List<String> lines = page.items().stream()
                        .map(run -> run.startedAt() + " " + run.traceId() + " [" + run.status() + "] "
                                + run.kind() + "/" + run.model() + " 用户=" + run.userId()
                                + " " + ms(run.durationMs()) + " 轮=" + run.llmRounds()
                                + " 工具=" + run.toolCalls()
                                + " token=" + run.inputTokens() + "/" + run.outputTokens()
                                + " 扣分=" + run.credits()
                                + (run.errorType() == null ? "" : " 错误=" + run.errorType()))
                        .toList();
                return ToolResult.ok("共 " + page.total() + " 条，第 " + page.page() + " 页：\n" + join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取调用列表失败：" + messageOf(failure));
            }
        }
    }

    /** 单轮详情。 */
    @Component
    public static class GetRun implements PaideiaTool {

        private final AiRunAdminService runs;

        GetRun(AiRunAdminService runs) {
            this.runs = runs;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_ai_get_run", GROUP, "读取单轮调用详情",
                    "按 traceId 读一轮的完整细节：概要、耗时构成、步骤表（含父子关系）。"
                            + "includePayload=true 时附完整请求/响应（每段最长 2000 字）。",
                    schema(Map.of(
                            "traceId", str("调用 traceId"),
                            "includePayload", Map.of("type", "boolean",
                                    "description", "是否附请求/响应正文；默认 false")),
                            "traceId"),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                String traceId = stringOf(arguments, "traceId");
                if (traceId == null) {
                    return ToolResult.fail("缺少 traceId");
                }
                AiRunAdminService.RunDetail detail = runs.detail(traceId);
                AiRunAdminService.RunSummary run = detail.run();
                List<String> lines = new ArrayList<>();
                lines.add("traceId=" + run.traceId() + " 状态=" + run.status()
                        + (run.errorType() == null ? "" : " 错误=" + run.errorType()));
                lines.add("场景=" + run.kind() + " harness=" + run.harness() + " 模型=" + run.model()
                        + " 用户=" + run.userId());
                lines.add("开始=" + run.startedAt() + " 结束=" + run.endedAt()
                        + " 耗时=" + ms(run.durationMs()) + " 首字=" + ms(run.ttftMs()));
                lines.add("轮次=" + run.llmRounds() + " 工具调用=" + run.toolCalls()
                        + " token=" + run.inputTokens() + "/" + run.outputTokens()
                        + " 扣分=" + run.credits());
                if (detail.breakdown() != null) {
                    var breakdown = detail.breakdown();
                    lines.add("耗时构成：排队=" + ms(breakdown.queueMs()) + " 模型=" + ms(breakdown.llmMs())
                            + " 工具=" + ms(breakdown.toolMs()) + " 其他=" + ms(breakdown.otherMs())
                            + " 合计=" + ms(breakdown.totalMs()));
                }
                lines.add("步骤 " + detail.spans().size() + " 条：");
                for (AiRunAdminService.SpanView span : detail.spans().stream().limit(MAX_SPANS).toList()) {
                    lines.add("  #" + span.seq() + (span.parentSeq() == null ? "" : " ←#" + span.parentSeq())
                            + " " + span.kind() + " " + span.name() + " [" + span.status() + "] "
                            + ms(span.durationMs()) + " token=" + span.inputTokens() + "/" + span.outputTokens()
                            + (span.errorType() == null ? "" : " 错误=" + span.errorType()));
                }
                if (detail.spans().size() > MAX_SPANS) {
                    lines.add("  …共 " + detail.spans().size() + " 条，只显示前 " + MAX_SPANS + " 条");
                }
                if (Boolean.TRUE.equals(arguments.get("includePayload"))) {
                    if (!detail.payloadEnabled()) {
                        lines.add("载荷采集未开启。");
                    } else if (detail.payloads().isEmpty()) {
                        lines.add("没有保存载荷。");
                    } else {
                        for (AiRunAdminService.PayloadView payload : detail.payloads()) {
                            lines.add("载荷 #" + payload.seq() + " 请求=" + payload.requestBytes()
                                    + "B 响应=" + payload.responseBytes() + "B"
                                    + (payload.truncated() ? "（超限已截断）" : ""));
                            lines.add("请求：\n" + clip(payload.requestBody()));
                            lines.add("响应：\n" + clip(payload.responseBody()));
                        }
                    }
                }
                return ToolResult.ok(join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取调用详情失败：" + messageOf(failure));
            }
        }
    }

    /** 回答反馈列表。 */
    @Component
    public static class ListFeedback implements PaideiaTool {

        private final AiFeedbackService feedback;

        ListFeedback(AiFeedbackService feedback) {
            this.feedback = feedback;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_ai_list_feedback", GROUP, "列出回答反馈",
                    "分页列出学习者对助手回答的赞/踩反馈，可按时间、rating（up/down）、"
                            + "reason、kind 筛选。踩的反馈带原因，是排查回答质量的第一入口。",
                    schema(Map.of(
                            "from", str("起始时间，ISO-8601"),
                            "to", str("结束时间，ISO-8601"),
                            "rating", str("up 或 down"),
                            "reason", str("踩的原因代码"),
                            "kind", str("场景过滤"),
                            "page", num("页码，从 1 开始"),
                            "size", num("每页条数，1-100，默认 20")),
                            new String[0]),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                PageResult<AiFeedbackService.FeedbackView> page = feedback.page(
                        instantOf(arguments, "from"), instantOf(arguments, "to"),
                        stringOf(arguments, "rating"), stringOf(arguments, "reason"),
                        stringOf(arguments, "kind"), pageOf(arguments));
                if (page.items().isEmpty()) {
                    return ToolResult.ok("没有符合条件的反馈（共 " + page.total() + " 条）。");
                }
                List<String> lines = page.items().stream()
                        .map(item -> item.createdAt() + " " + item.rating()
                                + (item.reason() == null ? "" : "(" + item.reason() + ")")
                                + " 用户=" + item.userId() + " 场景=" + item.kind()
                                + (item.traceId() == null ? "" : " trace=" + item.traceId()))
                        .toList();
                return ToolResult.ok("共 " + page.total() + " 条，第 " + page.page() + " 页：\n" + join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取反馈失败：" + messageOf(failure));
            }
        }
    }

    // --------------------------------------------------------------- helpers

    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> num(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static String stringOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    static Long longOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Instant instantOf(Map<String, Object> arguments, String key) {
        String text = stringOf(arguments, key);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException failure) {
            throw new IllegalArgumentException("时间格式应为 ISO-8601：" + text);
        }
    }

    static PageQuery pageOf(Map<String, Object> arguments) {
        Long page = longOf(arguments, "page");
        Long size = longOf(arguments, "size");
        int effectiveSize = size == null ? 20 : (int) Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageQuery.of(page == null || page < 1 ? 1 : page.intValue(), effectiveSize, null);
    }

    static String counts(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return "无";
        }
        return rows.stream()
                .map(row -> {
                    if (row instanceof io.github.xiaou61.ai.internal.telemetry.TelemetrySummaryMapper.CountBy count) {
                        return count.name() + "=" + count.count();
                    }
                    return String.valueOf(row);
                })
                .reduce((left, right) -> left + " " + right)
                .orElse("无");
    }

    static String percent(double ratio) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", ratio * 100);
    }

    static String ms(Integer value) {
        return value == null ? "—" : value + "ms";
    }

    static String clip(String text) {
        if (text == null) {
            return "（空）";
        }
        return text.length() <= MAX_PAYLOAD_CHARS
                ? text
                : text.substring(0, MAX_PAYLOAD_CHARS) + "…（截断）";
    }

    static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
