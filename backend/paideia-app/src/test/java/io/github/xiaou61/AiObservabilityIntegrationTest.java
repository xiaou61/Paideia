package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.github.xiaou61.ai.SpanSpec;
import io.github.xiaou61.ai.internal.telemetry.AiRunAdminService;
import io.github.xiaou61.platform.PageQuery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * WORK-036 的端到端验证：分桶序列、采集健康、对账、前缀搜索、子步骤。
 *
 * <p>这一层测**只有真起 Spring + 真写库才看得见**的东西：SQL 里的分桶表达式对不对、
 * 空桶有没有补上、对账有没有把 RESERVE 算进去、反馈的 upsert 是不是真的覆盖。
 *
 * <p>所有数据都用 9100 以上的 user_id，跑前跑后清掉，互不干扰。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiObservabilityIntegrationTest {

    private static final long USER = 9101L;

    @Autowired
    RunRecorder recorder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AiRunAdminService admin;

    private void wipe() {
        jdbc.update("DELETE FROM ai_feedback WHERE user_id >= 9100");
        jdbc.update("DELETE FROM ai_run_payloads WHERE run_trace_id IN "
                + "(SELECT trace_id FROM ai_runs WHERE user_id >= 9100)");
        jdbc.update("DELETE FROM ai_run_spans WHERE run_trace_id IN "
                + "(SELECT trace_id FROM ai_runs WHERE user_id >= 9100)");
        jdbc.update("DELETE FROM ai_runs WHERE user_id >= 9100");
    }

    /** 造一条已结束的轮次，时间可控（分桶断言必须能指定落在哪个桶）。 */
    private String insertRun(Instant startedAt, int minutes, String status, String kind) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO ai_runs
                  (trace_id, user_id, kind, status, started_at, ended_at, duration_ms, ttft_ms,
                   llm_rounds, input_tokens, output_tokens, cache_read_tokens, credits)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 1000, 200, 3000, 5)
                """, traceId, USER, kind, status,
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt.plusSeconds(1)),
                minutes * 60_000, 120);
        return traceId;
    }

    @Test
    @DisplayName("AC-002 中间没有数据的小时仍然返回一个全零桶，折线不跳段")
    void seriesFillsEmptyBuckets() throws InterruptedException {
        wipe();
        try {
            Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
            // 三个小时：有、空、有。窗口 3 小时 → 小时桶（≤48 小时）
            insertRun(now.minus(2, ChronoUnit.HOURS), 1, "ok", "chat");
            insertRun(now, 1, "ok", "chat");

            AiRunAdminService.Series series = admin.series(now.minus(2, ChronoUnit.HOURS),
                    now.plus(1, ChronoUnit.HOURS), null, null, null, USER);

            assertThat(series.bucket()).isEqualTo("hour");
            assertThat(series.points()).hasSize(3);
            // 中间那个桶必须存在且为 0——缺段会让折线断开，看起来像数据丢失
            assertThat(series.points().get(1).total()).isZero();
            // 没有数据的桶里，分位数必须是 null 而不是 0
            assertThat(series.points().get(1).durationP50()).isNull();
            assertThat(series.points().get(0).total() + series.points().get(2).total()).isEqualTo(2);
        } finally {
            wipe();
        }
    }

    @Test
    @DisplayName("AC-001 日桶：每桶数量之和等于窗口内的实际行数")
    void dailySeriesTotalsMatch() {
        wipe();
        try {
            Instant now = Instant.now();
            insertRun(now.minus(3, ChronoUnit.DAYS), 1, "ok", "chat");
            insertRun(now.minus(1, ChronoUnit.DAYS), 1, "failed", "chat");

            AiRunAdminService.Series series = admin.series(now.minus(5, ChronoUnit.DAYS), now,
                    null, null, null, USER);

            assertThat(series.bucket()).isEqualTo("day");
            long sum = series.points().stream().mapToLong(AiRunAdminService.SeriesPoint::total).sum();
            assertThat(sum).isEqualTo(2);
        } finally {
            wipe();
        }
    }

    @Test
    @DisplayName("AC-003 同一筛选下概览与明细的总数一致")
    void overviewMatchesListWithSameFilter() {
        wipe();
        try {
            Instant now = Instant.now();
            insertRun(now.minus(1, ChronoUnit.HOURS), 1, "failed", "chat");
            insertRun(now.minus(1, ChronoUnit.HOURS), 1, "ok", "chat");
            insertRun(now.minus(1, ChronoUnit.HOURS), 1, "failed", "entry_summary");

            AiRunAdminService.Overview overview = admin.overview(now.minus(1, ChronoUnit.DAYS), now,
                    "chat", "failed", null, USER);
            long listTotal = admin.page(now.minus(1, ChronoUnit.DAYS), now, "chat", "failed",
                    USER, null, PageQuery.of(1, 10, null)).total();

            assertThat(overview.total()).isEqualTo(1);
            assertThat(overview.total()).isEqualTo(listTotal);
        } finally {
            wipe();
        }
    }

    @Test
    @DisplayName("WORK-069 AC-005：failure_kind 筛选只返回对应行，概览按处置计数")
    void failureKindFilterReturnsOnlyMatchingRows() {
        wipe();
        try {
            Instant now = Instant.now();
            String badOutput = UUID.randomUUID().toString().replace("-", "");
            String needsHuman = UUID.randomUUID().toString().replace("-", "");
            jdbc.update("""
                    INSERT INTO ai_runs
                      (trace_id, user_id, kind, status, failure_kind, started_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, badOutput, USER, "chat", "failed", "bad_output",
                    java.sql.Timestamp.from(now.minus(1, ChronoUnit.HOURS)));
            jdbc.update("""
                    INSERT INTO ai_runs
                      (trace_id, user_id, kind, status, failure_kind, started_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, needsHuman, USER, "chat", "failed", "needs_human",
                    java.sql.Timestamp.from(now.minus(2, ChronoUnit.HOURS)));

            var page = admin.page(now.minus(1, ChronoUnit.DAYS), now, "chat", "failed",
                    "bad_output", USER, null, null, PageQuery.of(1, 10, null));
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.items()).extracting(AiRunAdminService.RunSummary::failureKind)
                    .containsExactly("bad_output");

            var overview = admin.overview(now.minus(1, ChronoUnit.DAYS), now, null, null, null, USER);
            assertThat(overview.byFailureKind())
                    .anySatisfy(row -> {
                        assertThat(row.name()).isEqualTo("bad_output");
                        assertThat(row.count()).isEqualTo(1);
                    });
        } finally {
            wipe();
        }
    }

    @Test
    @DisplayName("AC-004 缓存命中合计与节省比例来自真实列")
    void cacheTokensAreExposed() {
        wipe();
        try {
            Instant now = Instant.now();
            insertRun(now.minus(1, ChronoUnit.HOURS), 1, "ok", "chat");

            AiRunAdminService.Overview overview = admin.overview(now.minus(1, ChronoUnit.DAYS), now,
                    null, null, null, USER);

            assertThat(overview.cacheReadTokens()).isEqualTo(3000);
            assertThat(overview.inputTokens()).isEqualTo(1000);
            assertThat(overview.cacheSavedRatio()).isEqualTo(0.75);
        } finally {
            wipe();
        }
    }

    @Test
    @DisplayName("AC-008 对账：RESERVE/ADJUST 不算消耗，只比 SETTLE 一类的净额")
    void reconcileExcludesReserveAndAdjust() {
        wipe();
        try {
            Instant now = Instant.now();
            insertRun(now, 1, "ok", "chat");   // credits = 5
            // 流水：一笔结算 -5（消耗 5）、一笔预留不写行、一笔管理员发分 +100
            jdbc.update("INSERT INTO ai_credit_ledger (user_id, delta, kind, created_at) VALUES (?,?,?,?)",
                    USER, -5L, "SETTLE", java.sql.Timestamp.from(now));
            jdbc.update("INSERT INTO ai_credit_ledger (user_id, delta, kind, created_at) VALUES (?,?,?,?)",
                    USER, 100L, "ADJUST", java.sql.Timestamp.from(now));
            // 预留行正常不会写库（见 CreditService 类注释），但历史数据里可能有。
            // 把它造出来，才能证明排除列表真的在起作用而不是碰巧没数据。
            jdbc.update("INSERT INTO ai_credit_ledger (user_id, delta, kind, created_at) VALUES (?,?,?,?)",
                    USER, -50L, "RESERVE", java.sql.Timestamp.from(now));

            AiRunAdminService.Reconcile result = admin.reconcile(now.minus(1, ChronoUnit.DAYS),
                    now.plus(1, ChronoUnit.DAYS));

            AiRunAdminService.ReconcileDay today = result.days().stream()
                    .filter(day -> day.recorded() > 0)
                    .findFirst()
                    .orElseThrow();
            assertThat(today.recorded()).isEqualTo(5);
            // 若把 ADJUST(+100) 或 RESERVE(-50) 也算进来，这两个数会分别变成 -95 或 55
            assertThat(today.ledger()).isEqualTo(5);
            assertThat(today.diff()).isZero();
            assertThat(today.abnormal()).isFalse();
        } finally {
            wipe();
        }
    }

    @Test
    @DisplayName("AC-006/007 采集健康返回丢弃与采样率")
    void healthReportsQueueState() {
        AiRunAdminService.Health health = admin.health();

        assertThat(health.queueCapacity()).isPositive();
        assertThat(health.dropped()).isGreaterThanOrEqualTo(0);
        assertThat(health.sampling()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("AC-013 按 traceId 前缀能定位，不存在的前缀返回空而不是 500")
    void tracePrefixSearch() {
        wipe();
        try {
            Instant now = Instant.now();
            String traceId = insertRun(now.minus(1, ChronoUnit.HOURS), 1, "ok", "chat");

            long hit = admin.page(now.minus(1, ChronoUnit.DAYS), now, null, null, USER, null,
                    traceId.substring(0, 8), PageQuery.of(1, 10, null)).total();
            long miss = admin.page(now.minus(1, ChronoUnit.DAYS), now, null, null, USER, null,
                    "ffffffff", PageQuery.of(1, 10, null)).total();

            assertThat(hit).isEqualTo(1);
            assertThat(miss).isZero();
        } finally {
            wipe();
        }
    }

    @Test
    @DisplayName("AC-014 子步骤的 parentSeq 能落库并读回")
    void childSpanKeepsParent() throws InterruptedException {
        wipe();
        try (RunRecorder.RunScope run = recorder.begin(RunSpec.of("chat", USER))) {
            try (RunRecorder.StepScope parent = run.step(SpanSpec.tool("knowledge_search"))) {
                parent.succeeded();
            }
            try (RunRecorder.StepScope child =
                         run.step(SpanSpec.child(SpanSpec.KIND_RETRIEVAL, "sub", 1))) {
                child.succeeded();
            }
            run.finish(io.github.xiaou61.ai.RunOutcome.OK, null);
            String traceId = run.traceId();

            Integer parentSeq = null;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                List<Integer> values = jdbc.query(
                        "SELECT parent_seq FROM ai_run_spans WHERE run_trace_id = ? ORDER BY seq",
                        (rs, index) -> {
                            int value = rs.getInt(1);
                            return rs.wasNull() ? null : value;
                        }, traceId);
                if (values.size() >= 2) {
                    parentSeq = values.get(1);
                    break;
                }
                Thread.sleep(50);
            }

            // 第二条步骤挂在第一条下面。**这是本期唯一能证明层级没断的断言**：
            // 仓库里没有真实产生子步骤的调用点，所以只能这样直接验端口。
            assertThat(parentSeq).isEqualTo(1);
        } finally {
            wipe();
        }
    }
}
