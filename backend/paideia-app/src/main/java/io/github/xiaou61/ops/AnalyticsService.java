package io.github.xiaou61.ops;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 数据大屏的编排（WORK-045）。
 *
 * <p>四件事：把只读聚合的结果拼成五区块、判定断点、按北京自然日切桶、把监控层降级当正常情况处理。
 *
 * <p><b>为什么断点规则在这里而不在前端</b>：判据只该有一处。前端拿到 {@code broken} 就直接上色，
 * 不重新算一遍"上游是不是 0"。
 *
 * <p><b>为什么监控层用 try/catch 包住每个查询</b>：Prometheus 挂掉不该让整页空白。
 * 业务数字来自 MySQL，与监控层是两个独立的数据源，一层的故障不能牵连另一层。
 */
@Service
class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    /** 时序窗口：14 天足够看出趋势，又不会把首屏查询拖长。 */
    private static final int DAILY_DAYS = 14;

    /** 增量窗口：7 天。 */
    private static final int DELTA_DAYS = 7;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 时序里固定的五条系列。顺序即图例顺序。 */
    private static final List<String> DAILY_SERIES =
            List.of("ai_runs", "digest_items", "entry_views", "digest_clicks", "resumes");

    private final AnalyticsQueries queries;
    private final OpsProperties properties;
    private final PrometheusClient prometheus;

    /** 业务时区。与速递的 `edition_date` 同源，否则"今天"会错位一天。 */
    private final ZoneId zone;

    AnalyticsService(AnalyticsQueries queries, OpsProperties properties,
                     @Value("${paideia.digest.zone:Asia/Shanghai}") String zoneName) {
        this.queries = queries;
        this.properties = properties;
        this.prometheus = new PrometheusClient(properties.getPrometheusUrl(), properties.getQueryTimeoutMs());
        this.zone = parseZone(zoneName);
    }

    /**
     * 解析业务时区。
     *
     * <p>解析失败退回系统默认而不是抛异常——一个配置笔误不该让整页 500。与
     * `DigestProperties.zoneId()` 同一取舍（那里也是这么做的）。
     */
    private static ZoneId parseZone(String name) {
        try {
            return ZoneId.of(name);
        } catch (RuntimeException invalid) {
            log.warn("analytics.zone.invalid name={} fallback={}", name, ZoneId.systemDefault());
            return ZoneId.systemDefault();
        }
    }

    AnalyticsView overview() {
        Instant now = Instant.now();
        Instant deltaFrom = now.minus(Duration.ofDays(DELTA_DAYS));
        Instant dailyFrom = now.minus(Duration.ofDays(DAILY_DAYS));

        Counts counts = new Counts();

        List<AnalyticsView.Flow> flows = flows(counts);
        AnalyticsView.Volume volume = volume(now, deltaFrom, dailyFrom, counts);
        AnalyticsView.Usage usage = usage(counts);
        AnalyticsView.Ai ai = ai(deltaFrom);
        AnalyticsView.Search search = search(deltaFrom);
        AnalyticsView.Monitoring monitoring = monitoring();

        return new AnalyticsView(now.toString(), flows, volume, usage, ai, search, monitoring);
    }

    // ── 链 ────────────────────────────────────────────────────────────────────

    private List<AnalyticsView.Flow> flows(Counts c) {
        List<AnalyticsView.Flow> out = new ArrayList<>();
        out.add(flow("knowledge", "知识内容",
                new long[] {c.publishedEntries, c.categories, c.entryViewTotal, c.searches},
                new String[] {"条", "个", "次", "次"},
                new String[] {"管理端录入", "分类与概念", "条目浏览", "搜索"},
                "「条目浏览」是每人每篇一行的累加值，不是逐次明细。"));
        out.add(flow("digest", "每日速递",
                new long[] {c.digestItems, c.aiRuns, c.digestItems, c.digestClicks},
                new String[] {"条", "次", "条", "次"},
                new String[] {"抓取入库", "AI 压缩", "入库条目", "站内点击"},
                "点击只覆盖站内；用 RSS 客户端直接读的统计不到。"));
        out.add(flow("ai", "AI 对话",
                new long[] {c.aiConversations, c.aiRuns, c.aiMessages, c.creditLedgerRows},
                new String[] {"个", "次", "条", "笔"},
                new String[] {"会话", "模型调用", "消息", "积分流水"},
                null));
        out.add(flow("learning", "学习闭环",
                new long[] {c.entryViewTotal, c.learningProgress, c.questionAttempts, c.masteryStates},
                new String[] {"次", "条", "次", "个"},
                new String[] {"条目浏览", "开始学习", "答题", "掌握度"},
                "「答题」是掌握度与复习的数据来源，它为空时后两步必然是空的。"));
        out.add(flow("resume", "简历",
                new long[] {c.resumeDocuments, c.resumeDocuments, c.resumeEdits, c.resumeDocuments},
                new String[] {"份", "份", "次", "份"},
                new String[] {"上传", "AI 解析", "编辑", "投递使用"},
                null));
        out.add(flow("sql", "SQL 练习",
                new long[] {c.codeRuns, c.codeRuns, c.codeRuns, 0},
                new String[] {"次", "次", "次", "次"},
                new String[] {"提交运行", "沙箱执行", "结果生成", "结果查看"},
                "「结果查看」没有采集：运行结果不落库，看不到用户是否真的看了。"));
        // SQL 链的最后一步是显式标记的"没采集"：数值是 0，但原因不是"没人看"，
        // 而是"根本没记录"。generic 规则只能读出 0，所以这里覆盖一次。
        return overrideLastNode(out, "sql", AnalyticsView.State.NOT_COLLECTED);
    }

    /** 把某条链最后一个节点改成指定状态（用于"没采集"这种数值表达不出来的情况）。 */
    private static List<AnalyticsView.Flow> overrideLastNode(
            List<AnalyticsView.Flow> flows, String key, String state) {
        return flows.stream().map(flow -> {
            if (!flow.key().equals(key)) {
                return flow;
            }
            List<AnalyticsView.Node> nodes = new ArrayList<>(flow.nodes());
            int last = nodes.size() - 1;
            AnalyticsView.Node node = nodes.get(last);
            nodes.set(last, new AnalyticsView.Node(node.label(), node.count(), state, node.unit(), false));
            return new AnalyticsView.Flow(flow.key(), flow.title(), nodes, flow.edges(), flow.note());
        }).toList();
    }

    /**
     * 造一条链，并按「本节点为 0 且上游 > 0」标断点。
     *
     * <p>上游也 0 时不标——那说明整条链没跑，不是这一步断了。这个区别是整页最有信息量的地方：
     * 学习闭环的「答题=1」是断点（浏览 55 次却几乎没人答题），而一条全 0 的链只是没用过。
     */
    private AnalyticsView.Flow flow(String key, String title, long[] counts, String[] units,
                                    String[] labels, String note) {
        List<AnalyticsView.Node> nodes = new ArrayList<>(counts.length);
        List<AnalyticsView.Edge> edges = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            nodes.add(new AnalyticsView.Node(labels[i], counts[i], stateOf(counts, i), units[i],
                    isBroken(counts, i)));
            if (i > 0) {
                Double ratio = counts[i - 1] == 0 ? null : (double) counts[i] / counts[i - 1];
                edges.add(new AnalyticsView.Edge(i - 1, i, ratio));
            }
        }
        return new AnalyticsView.Flow(key, title, nodes, edges, note);
    }

    /**
     * 断点判定：本节点为 0 且上游有数据。
     *
     * <p>抽成静态纯函数是为了能单测——它是整页最容易被改错的一处（"上游也是 0"很容易漏）。
     */
    static boolean isBroken(long[] counts, int index) {
        return counts[index] == 0 && index > 0 && counts[index - 1] > 0;
    }

    /** 节点三态。 */
    static String stateOf(long[] counts, int index) {
        return counts[index] > 0 ? AnalyticsView.State.OK : AnalyticsView.State.ZERO;
    }

    // ── 业务量 ────────────────────────────────────────────────────────────────

    private AnalyticsView.Volume volume(Instant now, Instant deltaFrom, Instant dailyFrom, Counts c) {
        List<AnalyticsView.TableRow> tables = List.of(
                table("ai_runs", "AI 运行", c.aiRuns, queries.aiRunsSince(deltaFrom)),
                table("digest_items", "速递条目", c.digestItems, queries.digestItemsSince(deltaFrom)),
                table("knowledge_entries", "知识条目", c.publishedEntries, null),
                table("knowledge_entry_views", "条目浏览", c.entryViewRows, queries.entryViewsSince(deltaFrom)),
                table("learning_progress", "学习进度", c.learningProgress, null),
                table("question_attempts", "答题记录", c.questionAttempts, queries.attemptsSince(deltaFrom)),
                table("concept_mastery_state", "掌握度", c.masteryStates, null),
                table("review_sessions", "复习会话", c.reviewSessions, null),
                table("resume_documents", "简历", c.resumeDocuments, queries.resumesSince(deltaFrom)),
                table("resume_edit_history", "简历编辑", c.resumeEdits, null),
                table("code_runs", "SQL 运行", c.codeRuns, queries.codeRunsSince(deltaFrom)),
                table("digest_item_clicks", "速递点击", c.digestClicks, queries.digestClicksSince(deltaFrom)),
                table("knowledge_search_log", "搜索记录", c.searches, queries.searchesSince(deltaFrom)),
                table("notes", "笔记", c.notes, null),
                table("users", "账号", c.accounts, null));

        List<AnalyticsView.DailyPoint> daily = new ArrayList<>();
        // 先铺满 14 天的空桶：缺天会让折线看起来像"中间断过"
        Map<String, Map<String, Long>> byDate = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(zone);
        for (int back = DAILY_DAYS - 1; back >= 0; back--) {
            byDate.put(DATE.format(today.minusDays(back)), new LinkedHashMap<>());
        }
        String offset = offsetOf(zone, now);
        for (AnalyticsQueries.DailyRow row : queries.daily(dailyFrom, offset)) {
            Map<String, Long> day = byDate.get(row.d());
            if (day != null) {
                day.put(row.series(), row.total());
            }
        }
        // **每一天 × 每个 series 都要有点**，哪怕值是 0：只画有数据的那几天，
        // 折线会在空白处"跳跃"，看起来像数据断了而不是那天真的没有活动。
        for (Map.Entry<String, Map<String, Long>> entry : byDate.entrySet()) {
            for (String series : DAILY_SERIES) {
                daily.add(new AnalyticsView.DailyPoint(entry.getKey(), series,
                        entry.getValue().getOrDefault(series, 0L)));
            }
        }

        return new AnalyticsView.Volume(tables, daily, dailyNote(daily));
    }

    /**
     * 时序的一句话说明。
     *
     * <p>数据点少于 3 天时明说"数据不足"——画一条看着正常的线会让读者以为趋势稳定，
     * 而真相是还没有趋势可言。
     */
    private String dailyNote(List<AnalyticsView.DailyPoint> daily) {
        long days = daily.stream().map(AnalyticsView.DailyPoint::date).distinct().count();
        long nonZeroDays = daily.stream().filter(point -> point.count() > 0)
                .map(AnalyticsView.DailyPoint::date).distinct().count();
        if (nonZeroDays < 3) {
            return "数据不足（" + nonZeroDays + " 天有数据，共 " + days + " 天窗口）";
        }
        return null;
    }

    /** 时区在当天的偏移，格式 `+08:00`（`CONVERT_TZ` 要这个形式，不要时区名）。 */
    private static String offsetOf(ZoneId zone, Instant at) {
        ZoneOffset offset = zone.getRules().getOffset(at);
        return offset.getId().equals("Z") ? "+00:00" : offset.getId();
    }

    private AnalyticsView.TableRow table(String name, String label, long rows, Long delta7d) {
        // "只增不减"的表：没有删除路径，行数只涨。给归档判断用。
        boolean appendOnly = switch (name) {
            case "ai_runs", "ai_credit_ledger", "digest_items", "digest_item_clicks",
                 "knowledge_search_log", "knowledge_entry_views" -> true;
            default -> false;
        };
        return new AnalyticsView.TableRow(name, label, rows, delta7d,
                appendOnly ? "append-only" : AnalyticsView.State.OK);
    }

    // ── 使用度 ────────────────────────────────────────────────────────────────

    private AnalyticsView.Usage usage(Counts c) {
        List<AnalyticsView.Feature> features = List.of(
                feature("AI 对话", c.aiConversationCount, "个会话"),
                feature("AI 调用", c.aiRuns, "次运行"),
                feature("条目浏览", c.entryViewTotal, "次"),
                feature("知识库搜索", c.searches, "次（新增采集）"),
                feature("速递点击", c.digestClicks, "次（新增采集）"),
                feature("学习进度", c.learningProgress, "条"),
                feature("答题", c.questionAttempts, "次"),
                feature("掌握度计算", c.masteryStates, "个概念"),
                feature("复习会话", c.reviewSessions, "次"),
                feature("简历", c.resumeDocuments, "份"),
                feature("SQL 运行", c.codeRuns, "次"),
                feature("笔记", c.notes, "篇"),
                // 这三项是"已采集但为 0"的典型：表建好了，功能上线了，没人用
                new AnalyticsView.Feature("训练营", AnalyticsView.State.ZERO, "0 条（表为空）"),
                new AnalyticsView.Feature("模拟面试评估", AnalyticsView.State.ZERO, "0 条（表为空）"));
        return new AnalyticsView.Usage(c.accounts, features);
    }

    private AnalyticsView.Feature feature(String name, long count, String unit) {
        String state = count > 0 ? AnalyticsView.State.OK : AnalyticsView.State.ZERO;
        return new AnalyticsView.Feature(name, state, count + " " + unit);
    }

    // ── AI ────────────────────────────────────────────────────────────────────

    private AnalyticsView.Ai ai(Instant deltaFrom) {
        List<AnalyticsView.KindRow> byKind = queries.aiByKind().stream()
                .map(row -> new AnalyticsView.KindRow(row.name(), row.total(), row.ok()))
                .toList();
        long runs = queries.aiRuns();
        long ok = queries.aiRunsOk();
        Double successRate = runs == 0 ? null : (double) ok / runs;
        AnalyticsView.Credits credits = new AnalyticsView.Credits(
                queries.creditsGranted(), queries.creditsSpent(), queries.creditsBalance());

        long clickedItems = queries.clickedItemCount(deltaFrom);
        long clicks = queries.clickCount(deltaFrom);
        long items = queries.digestItems();
        // 点击率用"有点击的条目数 / 条目总数"，不是"点击数 / 条目数"——后者会超过 100%
        Long clickRatePercent = items == 0 ? null : Math.round(clickedItems * 100.0 / items);

        List<AnalyticsView.ClickRow> topItems = queries.topClickedItems(deltaFrom, 5).stream()
                .map(row -> new AnalyticsView.ClickRow(row.id(), row.title(), row.total()))
                .toList();
        return new AnalyticsView.Ai(byKind, successRate, runs, credits, topItems, clicks, clickRatePercent);
    }

    // ── 搜索 ──────────────────────────────────────────────────────────────────

    private AnalyticsView.Search search(Instant deltaFrom) {
        List<AnalyticsView.NameCount> top = queries.topKeywords(deltaFrom, 10).stream()
                .map(row -> new AnalyticsView.NameCount(row.name(), row.total()))
                .toList();
        List<AnalyticsView.NameCount> zero = queries.zeroResultKeywords(deltaFrom, 10).stream()
                .map(row -> new AnalyticsView.NameCount(row.name(), row.total()))
                .toList();
        return new AnalyticsView.Search(top, zero);
    }

    // ── 监控 ──────────────────────────────────────────────────────────────────

    private AnalyticsView.Monitoring monitoring() {
        // 每个查询独立 try/catch：Prometheus 挂掉时这一块显示不可达，其它区块照常
        Double reachable = safely(() -> prometheus.scalar("vector(1)"));
        if (reachable == null) {
            return new AnalyticsView.Monitoring(false, null, null, null, null);
        }
        return new AnalyticsView.Monitoring(true,
                safely(() -> prometheus.scalar("sum(rate(http_server_requests_seconds_count[5m]))")),
                safely(() -> prometheus.scalar(
                        "(sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[5m])) or vector(0))"
                                + " / clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 0.000001)")),
                safely(() -> prometheus.scalar(
                        "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))")),
                safely(() -> prometheus.scalar("paideia:sli_ai_success:ratio1h")));
    }

    private static Double safely(Supplier<Double> query) {
        try {
            return query.get();
        } catch (RuntimeException failure) {
            log.debug("analytics.prometheus.failed cause={}", failure.toString());
            return null;
        }
    }

    // ── 计数快照 ──────────────────────────────────────────────────────────────

    /** 一次把所有 COUNT 取齐，避免在多个构造方法里重复打库。 */
    private final class Counts {
        final long publishedEntries = queries.publishedEntries();
        final long categories = queries.categories();
        final long entryViewRows = queries.entryViewRows();
        final long entryViewTotal = queries.entryViewTotal();
        final long digestItems = queries.digestItems();
        final long digestClicks = queries.digestClicks();
        final long aiRuns = queries.aiRuns();
        final long aiConversations = queries.aiConversations();
        final long aiConversationCount = aiConversations;
        final long aiMessages = queries.aiMessages();
        final long creditLedgerRows = queries.creditLedgerRows();
        final long learningProgress = queries.learningProgress();
        final long questionAttempts = queries.questionAttempts();
        final long masteryStates = queries.masteryStates();
        final long reviewSessions = queries.reviewSessions();
        final long resumeDocuments = queries.resumeDocuments();
        final long resumeEdits = queries.resumeEdits();
        final long codeRuns = queries.codeRuns();
        final long searches = queries.searchLogs();
        final long notes = queries.notes();
        final long accounts = queries.accounts();
    }
}
