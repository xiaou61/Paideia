package io.github.xiaou61.ai.internal.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.internal.billing.BillingProperties;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 管理端统计服务（WORK-022 T10，WORK-036 扩展）。
 *
 * <p><b>时间窗归一化在这里做一次</b>：缺省 7 天、上限 `retention-days`（默认 90）。
 * 不设上限的统计接口等于给了一张全表扫描的入口；收敛时明确返回 `clamped`，
 * 让"我怎么只看到 90 天"这件事有个可读的答案。
 *
 * <p>响应里**永远没有正文、提示词、工具参数**。这不是"注意一下"，是 AC-012 的断言对象。
 */
@Service
public class AiRunAdminService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 桶的格式化：小时桶用它，日桶用 {@link #DAY_FMT}。**与 SQL 里的 DATE_FORMAT 必须字面一致**。 */
    private static final String HOUR_FMT = "%Y-%m-%d %H:00";
    private static final String DAY_FMT = "%Y-%m-%d";
    private static final DateTimeFormatter HOUR_PARSER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY_PARSER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AiRunMapper runs;
    private final AiRunSpanMapper spans;
    private final AiRunPayloadMapper payloads;
    private final TelemetrySummaryMapper summary;
    private final TelemetryProperties properties;
    private final RunWriteQueue queue;
    private final BillingProperties billing;

    AiRunAdminService(AiRunMapper runs, AiRunSpanMapper spans, AiRunPayloadMapper payloads,
                      TelemetrySummaryMapper summary, TelemetryProperties properties,
                      RunWriteQueue queue, BillingProperties billing) {
        this.runs = runs;
        this.spans = spans;
        this.payloads = payloads;
        this.summary = summary;
        this.properties = properties;
        this.queue = queue;
        this.billing = billing;
    }

    /** 归一化后的时间窗。 */
    public record Window(Instant from, Instant to, boolean clamped) {
    }

    /**
     * 概览（WORK-022 REQ-008 + WORK-036 A 组）。
     *
     * @param cacheReadTokens    缓存命中的输入 token（WORK-036 REQ-003）
     * @param cacheSavedRatio    缓存命中 ÷（缓存命中 + 输入）；全为零时 0，不是 NaN
     * @param nominalCredits     按当前价目表把 token 折算出来的积分（WORK-036 REQ-004）
     * @param credits            实扣积分，与 {@code nominalCredits} 的差代表缓存与调价的偏差
     * @param sampling           当前采样率；< 1 时成功率会系统性偏低，界面必须提示
     */
    public record Overview(Instant from, Instant to, boolean clamped,
                    long total, StatusCounts byStatus,
                    double successRate,
                    Integer durationP50, Integer durationP95,
                    Integer ttftP50, Integer ttftP95,
                    long inputTokens, long outputTokens, long cacheReadTokens,
                    long credits, long nominalCredits, double cacheSavedRatio,
                    double sampling,
                    List<TelemetrySummaryMapper.CountBy> byKind,
                    List<TelemetrySummaryMapper.CountBy> byModel,
                    List<TelemetrySummaryMapper.CountBy> errorTypes,
                    List<TelemetrySummaryMapper.CountBy> tools) {
    }

    /**
     * 一个时间桶（WORK-036 REQ-001）。
     *
     * <p>{@code at} 是桶的**起点**；分位数在没有数据时是 null——"0 毫秒"与"没数据"不是一回事。
     */
    public record SeriesPoint(Instant at, long total, long ok, long failed, long timeout,
                       Integer durationP50, Integer durationP95, Integer ttftP50,
                       long inputTokens, long outputTokens, long cacheReadTokens, long credits) {
    }

    public record Series(Instant from, Instant to, boolean clamped, String bucket, String zone,
                         double sampling, List<SeriesPoint> points) {
    }

    /** 采集健康（WORK-036 REQ-005）。数字来自进程内存，重启清零。 */
    public record Health(boolean enabled, double sampling, int queueDepth, int queueCapacity,
                         long dropped, long writeFailures, Instant lastSuccessAt) {
    }

    /** 一天的账实对比（WORK-036 REQ-007）。{@code diff = recorded - ledger}。 */
    public record ReconcileDay(String day, long recorded, long ledger, long diff, boolean abnormal) {
    }

    public record Reconcile(Instant from, Instant to, int threshold, List<ReconcileDay> days) {
    }

    /** 一轮的耗时构成（WORK-036 REQ-008）。四段之和等于 {@code totalMs}，{@code otherMs} 已夹到非负。 */
    public record Breakdown(int queueMs, int llmMs, int toolMs, int otherMs, int totalMs) {
    }

    /**
     * 按状态的计数。**用固定字段而不是 Map**：契约快照按 JSON 形状比对，
     * 而 Map 的键取决于这一轮出现过哪些状态——同一段代码在不同数据上会得到不同形状，
     * 那种"快照漂移"不是缺陷，是形状没定死（2026-10-19 实测踩到）。
     */
    public record StatusCounts(long ok, long failed, long timeout, long cancelled, long interrupted, long running) {
    }

    public record RunSummary(String traceId, long userId, Long conversationId, String kind, String harness,
                      String model, String status, String errorType,
                      Instant startedAt, Instant endedAt,
                      Integer durationMs, Integer ttftMs, int llmRounds, int toolCalls,
                      int inputTokens, int outputTokens, int credits) {
    }

    public record SpanView(int seq, Integer parentSeq, String kind, String name, String status,
                    String errorType, Instant startedAt, Integer durationMs, Integer ttftMs,
                    int inputTokens, int outputTokens, Map<String, Object> attrs) {
    }

    /**
     * 一轮的详情。
     *
     * @param payloads  各 LLM 步的完整请求与响应（WORK-030 阶段 B）。**按 seq 对应步骤**，
     *                  前端展开某一步时按 seq 取。未开启采集时为空，`payloadEnabled` 告诉页面该说什么。
     * @param breakdown 耗时构成（WORK-036 REQ-008）：排队 / LLM / 工具 / 其它四段
     */
    public record RunDetail(RunSummary run, Map<String, Object> attrs, List<SpanView> spans,
                            List<PayloadView> payloads, boolean payloadEnabled,
                            Breakdown breakdown) {
    }

    /**
     * 一份完整载荷。
     *
     * @param truncated 内容超上限（>1MB），正文为空但字节数与标记都在
     */
    public record PayloadView(int seq, String requestBody, String responseBody,
                       int requestBytes, int responseBytes, boolean truncated) {
    }

    /** 时间窗：缺省 7 天；跨度超过保留期时收敛到保留期。 */
    public Window window(Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(7, ChronoUnit.DAYS) : from;
        Instant floor = end.minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        boolean clamped = start.isBefore(floor);
        return new Window(clamped ? floor : start, end, clamped);
    }

    public Overview overview(Instant from, Instant to) {
        return overview(from, to, null, null, null, null);
    }

    /**
     * 概览（WORK-036 REQ-002）：四个筛选与明细页同一套语义。
     *
     * <p>**筛选必须一致**：同一组合下概览的 {@code total} 要等于明细列表的 {@code total}，
     * 否则"两页对不上"会变成一个反复被质疑的谜。
     */
    public Overview overview(Instant from, Instant to, String kind, String status,
                             String model, Long userId) {
        Window window = window(from, to);
        String k = blankToNull(kind);
        String s = blankToNull(status);
        String m = blankToNull(model);
        // **带筛选**：AC-003 要求"同一组合下概览的 total 等于明细的 total"。
        // 用全量 countByStatus 会让概览永远显示全平台总数（2026-10-23 实测踩到）。
        List<TelemetrySummaryMapper.CountBy> statuses =
                summary.countByStatusFiltered(window.from(), window.to(), k, s, m, userId);
        long ok = 0;
        long failed = 0;
        long timeout = 0;
        long cancelled = 0;
        long interrupted = 0;
        long running = 0;
        for (TelemetrySummaryMapper.CountBy row : statuses) {
            switch (row.name()) {
                case "ok" -> ok = row.count();
                case "failed" -> failed = row.count();
                case "timeout" -> timeout = row.count();
                case "cancelled" -> cancelled = row.count();
                case "interrupted" -> interrupted = row.count();
                case "running" -> running = row.count();
                default -> { }
            }
        }
        StatusCounts byStatus = new StatusCounts(ok, failed, timeout, cancelled, interrupted, running);
        long terminal = ok + failed + timeout + cancelled + interrupted;
        TelemetrySummaryMapper.Percentiles duration = summary.durationPercentiles(window.from(), window.to(), k, s, m, userId);
        TelemetrySummaryMapper.Percentiles ttft = summary.ttftPercentiles(window.from(), window.to(), k, s, m, userId);
        TelemetrySummaryMapper.TotalsFiltered totals =
                summary.totalsFiltered(window.from(), window.to(), k, s, m, userId);
        long input = totals == null ? 0 : totals.inputTokens();
        long cacheRead = totals == null ? 0 : totals.cacheReadTokens();
        Long nominal = nominalCredits(window.from(), window.to(), k, s, m, userId);
        return new Overview(window.from(), window.to(), window.clamped(),
                terminal, byStatus,
                terminal == 0 ? 0 : (double) ok / terminal,
                duration == null ? null : duration.p50(),
                duration == null ? null : duration.p95(),
                ttft == null ? null : ttft.p50(),
                ttft == null ? null : ttft.p95(),
                input,
                totals == null ? 0 : totals.outputTokens(),
                cacheRead,
                totals == null ? 0 : totals.credits(),
                nominal == null ? 0 : nominal,
                cacheSavedRatio(input, cacheRead),
                properties.getSampling(),
                // 排行也必须带同一组筛选：否则按 kind/model 筛过之后，
                // 顶部卡片是筛选后的、下面四个排行还是全平台的，同一屏两个口径。
                summary.countByKind(window.from(), window.to(), k, s, m, userId),
                summary.countByModel(window.from(), window.to(), k, s, m, userId),
                summary.countByErrorType(window.from(), window.to(), k, s, m, userId),
                summary.countByTool(window.from(), window.to()));
    }

    /**
     * 缓存节省比例（WORK-036 REQ-003 / AC-004）。
     *
     * <p>分母是"缓存命中 + 输入"——两者共同构成"本来要送进模型、但命中缓存的部分可以省"的总量。
     * 全为 0 时返回 0 而不是 NaN：界面上 NaN 会渲染成 "NaN%"，比没有数字更糟。
     */
    static double cacheSavedRatio(long inputTokens, long cacheReadTokens) {
        long denominator = inputTokens + cacheReadTokens;
        return denominator <= 0 ? 0 : (double) cacheReadTokens / denominator;
    }

    /**
     * 名义成本（WORK-036 REQ-004）：按当前价目表把 token 折算成积分。
     *
     * <p>与实扣的差**不是 bug 而是信息**：缓存命中不计费、价目表可能在窗口内被改过，
     * 两者都会让名义值高于实扣。界面上必须同时显示，否则"扣分对不上"会被当成计费错误。
     */
    private static int saturateToInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private Long nominalCredits(Instant from, Instant to, String kind, String status,
                                String model, Long userId) {
        long total = 0;
        for (TelemetrySummaryMapper.ModelTokens row
                : summary.tokensByModel(from, to, kind, status, model, userId)) {
            // SUM 是 long：直接强转 int 在 90 天窗口 + 长上下文下会溢出成负数，
            // 而 creditsFor 对 <=0 一律返回 0 —— 名义成本会静默变成 0。饱和到 int 上限。
            total += billing.creditsFor(row.name(), saturateToInt(row.inputTokens()),
                    saturateToInt(row.outputTokens()));
        }
        return total;
    }

    /**
     * 按时间分桶的序列（WORK-036 REQ-001）。
     *
     * <p>两次查询（计数一次、分位数两次）在内存里按桶合并。**不拼成一条**：MySQL 里把
     * 聚合与窗口塞进同一层会让执行计划退化，且读起来像谜语。
     */
    public Series series(Instant from, Instant to, String kind, String status,
                         String model, Long userId) {
        Window window = window(from, to);
        ZoneId zone = properties.zoneId();
        int offsetMinutes = zone.getRules().getOffset(window.from()).getTotalSeconds() / 60;
        boolean hourly = Segmentation.of(window.from(), window.to()) == Segmentation.Granularity.HOUR;
        String fmt = hourly ? HOUR_FMT : DAY_FMT;
        List<java.time.Instant> edges = Segmentation.boundaries(window.from(), window.to(), zone);

        String k = blankToNull(kind);
        String s = blankToNull(status);
        String m = blankToNull(model);

        Map<java.time.Instant, SeriesPoint> byBucket = new HashMap<>();
        for (TelemetrySummaryMapper.BucketRow row : summary.countByBucket(
                window.from(), window.to(), k, s, m, userId, fmt, offsetMinutes)) {
            java.time.Instant at = parseBucket(row.bucket(), hourly, zone);
            if (at == null) {
                continue;
            }
            byBucket.put(at, new SeriesPoint(at, row.total(), row.ok(), row.failed(), row.timeout(),
                    null, null, null,
                    row.inputTokens(), row.outputTokens(), row.cacheReadTokens(), row.credits()));
        }
        Map<java.time.Instant, TelemetrySummaryMapper.BucketPercentiles> durations = new HashMap<>();
        for (TelemetrySummaryMapper.BucketPercentiles row : summary.durationPercentilesByBucket(
                window.from(), window.to(), k, s, m, userId, fmt, offsetMinutes)) {
            java.time.Instant at = parseBucket(row.bucket(), hourly, zone);
            if (at != null) {
                durations.put(at, row);
            }
        }
        Map<java.time.Instant, TelemetrySummaryMapper.BucketPercentiles> ttfts = new HashMap<>();
        for (TelemetrySummaryMapper.BucketPercentiles row : summary.ttftPercentilesByBucket(
                window.from(), window.to(), k, s, m, userId, fmt, offsetMinutes)) {
            java.time.Instant at = parseBucket(row.bucket(), hourly, zone);
            if (at != null) {
                ttfts.put(at, row);
            }
        }

        List<SeriesPoint> points = new ArrayList<>(Math.max(edges.size() - 1, 0));
        for (Segmentation.Bucket<SeriesPoint> bucket : Segmentation.fill(edges, byBucket, null)) {
            SeriesPoint base = bucket.value();
            TelemetrySummaryMapper.BucketPercentiles duration = durations.get(bucket.at());
            TelemetrySummaryMapper.BucketPercentiles ttft = ttfts.get(bucket.at());
            points.add(new SeriesPoint(bucket.at(),
                    base == null ? 0 : base.total(),
                    base == null ? 0 : base.ok(),
                    base == null ? 0 : base.failed(),
                    base == null ? 0 : base.timeout(),
                    duration == null ? null : duration.p50(),
                    duration == null ? null : duration.p95(),
                    ttft == null ? null : ttft.p50(),
                    base == null ? 0 : base.inputTokens(),
                    base == null ? 0 : base.outputTokens(),
                    base == null ? 0 : base.cacheReadTokens(),
                    base == null ? 0 : base.credits()));
        }
        return new Series(window.from(), window.to(), window.clamped(),
                hourly ? "hour" : "day", zone.getId(), properties.getSampling(), points);
    }

    /**
     * SQL 给的桶字符串按 zone 还原成时刻。解析失败返回 null（那一桶被忽略，不炸整个响应）。
     *
     * <p><b>日桶要用 {@link LocalDate#parse} 而不是 {@code LocalDateTime.parse}</b>：
     * "2026-10-23" 没有时分秒，`LocalDateTime.parse` 会直接抛——那会让所有日桶被静默跳过，
     * 于是 7 天窗口返回一条全零的折线（2026-10-23 实测踩到）。
     */
    private static java.time.Instant parseBucket(String bucket, boolean hourly, ZoneId zone) {
        if (bucket == null) {
            return null;
        }
        try {
            return hourly
                    ? java.time.LocalDateTime.parse(bucket, HOUR_PARSER).atZone(zone).toInstant()
                    : LocalDate.parse(bucket, DAY_PARSER).atStartOfDay(zone).toInstant();
        } catch (RuntimeException unparseable) {
            return null;
        }
    }

    /** 采集健康（WORK-036 REQ-005）。读进程内存，不进数据库。 */
    public Health health() {
        return new Health(properties.isEnabled(), properties.getSampling(),
                queue.depth(), queue.getCapacity(),
                queue.dropped(), queue.writeFailures(), queue.lastSuccessAt());
    }

    /**
     * 积分对账（WORK-036 REQ-007）。
     *
     * <p>记录侧取 {@code ai_runs.credits}；流水侧取 {@code ai_credit_ledger} 里**消耗类**的净额取负。
     * `RESERVE` 与 `ADJUST` 必须排除：前者只动余额、不写流水行；后者是管理员发分，不是消耗。
     */
    public Reconcile reconcile(Instant from, Instant to) {
        Window window = window(from, to);
        int offsetMinutes = properties.zoneId().getRules()
                .getOffset(window.from()).getTotalSeconds() / 60;
        Map<String, Long> recorded = new LinkedHashMap<>();
        for (TelemetrySummaryMapper.DayAmount row : summary.creditsByDay(window.from(), window.to(), offsetMinutes)) {
            recorded.put(row.day(), row.amount());
        }
        Map<String, Long> ledger = new LinkedHashMap<>();
        for (TelemetrySummaryMapper.DayAmount row : summary.ledgerSpendByDay(window.from(), window.to(), offsetMinutes)) {
            ledger.put(row.day(), row.amount());
        }
        java.util.TreeSet<String> days = new java.util.TreeSet<>(recorded.keySet());
        days.addAll(ledger.keySet());
        int threshold = properties.getReconcileThreshold();
        List<ReconcileDay> rows = new ArrayList<>(days.size());
        for (String day : days) {
            long r = recorded.getOrDefault(day, 0L);
            long l = ledger.getOrDefault(day, 0L);
            long diff = r - l;
            rows.add(new ReconcileDay(day, r, l, diff, Math.abs(diff) >= threshold));
        }
        return new Reconcile(window.from(), window.to(), threshold, rows);
    }

    /** 耗时构成（WORK-036 REQ-008）。{@code otherMs} 夹到非负：并发工具会重叠，实测能算出负值。 */
    static Breakdown breakdown(Integer queueMs, Integer durationMs, List<SpanView> spans) {
        int llm = 0;
        int tool = 0;
        for (SpanView span : spans) {
            if (span.durationMs() == null) {
                continue;
            }
            if (io.github.xiaou61.ai.SpanSpec.KIND_LLM.equals(span.kind())) {
                llm += span.durationMs();
            } else if (io.github.xiaou61.ai.SpanSpec.KIND_TOOL.equals(span.kind())) {
                tool += span.durationMs();
            }
        }
        int queue = queueMs == null ? 0 : queueMs;
        int total = durationMs == null ? 0 : durationMs;
        return new Breakdown(queue, llm, tool, Math.max(total - queue - llm - tool, 0), total);
    }

    public PageResult<RunSummary> page(Instant from, Instant to, String kind, String status,
                                Long userId, String model, PageQuery query) {
        return page(from, to, kind, status, userId, model, null, query);
    }

    /**
     * 明细列表，多一个 traceId 前缀搜索（WORK-036 REQ-013）。
     *
     * <p>**前缀而不是全等**：从日志里复制的 id 常带空格或只有前几位。
     * `LIKE 'x%'` 还能走 `uk_ai_runs_trace`（范围扫描），`%x%` 会退化成全表扫。
     */
    public PageResult<RunSummary> page(Instant from, Instant to, String kind, String status,
                                Long userId, String model, String tracePrefix, PageQuery query) {
        Window window = window(from, to);
        String prefix = blankToNull(tracePrefix);
        prefix = prefix == null ? null : prefix.trim();
        if (prefix != null && prefix.isEmpty()) {
            prefix = null;
        }
        List<RunSummary> items = runs.page(window.from(), window.to(), blankToNull(kind),
                        blankToNull(status), userId, blankToNull(model), prefix,
                        query.limit(), query.offset()).stream()
                .map(AiRunAdminService::toSummary)
                .toList();
        long total = runs.countPage(window.from(), window.to(), blankToNull(kind),
                blankToNull(status), userId, blankToNull(model), prefix);
        return PageResult.of(total, query, items);
    }

    /** 详情：轮次 + 步骤（按 seq）。**不含正文**，attrs 里也只有长度与计数。 */
    public RunDetail detail(String traceId) {
        AiRunMapper.AiRunRow row = runs.findByTraceId(traceId);
        if (row == null) {
            return null;
        }
        List<SpanView> views = new ArrayList<>();
        for (AiRunSpanMapper.AiRunSpanRow span : spans.findByTraceId(traceId)) {
            views.add(new SpanView(span.getSeq() == null ? 0 : span.getSeq(), span.getParentSeq(),
                    span.getKind(), span.getName(), span.getStatus(), span.getErrorType(),
                    span.getStartedAt(), span.getDurationMs(), span.getTtftMs(),
                    orZero(span.getInputTokens()), orZero(span.getOutputTokens()),
                    parseAttrs(span.getAttrsJson())));
        }
        List<PayloadView> payloadViews = payloads.findByTraceId(traceId).stream()
                .map(p -> new PayloadView(
                        p.getSeq() == null ? 0 : p.getSeq(),
                        p.getRequestBody(), p.getResponseBody(),
                        p.getRequestBytes() == null ? 0 : p.getRequestBytes(),
                        p.getResponseBytes() == null ? 0 : p.getResponseBytes(),
                        Boolean.TRUE.equals(p.getTruncated())))
                .toList();
        return new RunDetail(toSummary(row), parseAttrs(row.getAttrsJson()), views,
                payloadViews, properties.isPayloadEnabled(),
                breakdown(row.getQueueMs(), row.getDurationMs(), views));
    }

    /** 时间窗内出现过的 kind / status / model，供筛选下拉。 */
    public Map<String, Object> meta(Instant from, Instant to) {
        Window window = window(from, to);
        return Map.of(
                "kinds", summary.distinctKinds(window.from(), window.to()),
                "models", summary.distinctModels(window.from(), window.to()),
                "statuses", summary.countByStatus(window.from(), window.to()),
                "from", window.from(),
                "to", window.to());
    }

    private static RunSummary toSummary(AiRunMapper.AiRunRow row) {
        return new RunSummary(row.getTraceId(), row.getUserId() == null ? 0 : row.getUserId(),
                row.getConversationId(), row.getKind(), row.getHarness(), row.getModel(),
                row.getStatus(), row.getErrorType(), row.getStartedAt(), row.getEndedAt(),
                row.getDurationMs(), row.getTtftMs(), orZero(row.getLlmRounds()),
                orZero(row.getToolCalls()), orZero(row.getInputTokens()),
                orZero(row.getOutputTokens()), orZero(row.getCredits()));
    }

    /** attrs 坏了当空——一个字段解析失败不该让整条记录打不开。 */
    private static Map<String, Object> parseAttrs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception failure) {
            return Map.of();
        }
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
