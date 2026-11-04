package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.MemoryCandidate;
import io.github.xiaou61.ai.MemoryExtractor;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.ai.internal.conversation.ConversationService;
import io.github.xiaou61.ai.internal.memory.MemoryExtractionJob;
import io.github.xiaou61.ai.internal.memory.MemoryExtractionJobs;
import io.github.xiaou61.ai.internal.memory.MemoryExtractionRelay;
import io.github.xiaou61.ai.internal.memory.MemoryExtractionService;
import io.github.xiaou61.ai.internal.memory.MemoryProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 记忆整理任务行的语义（WORK-061 TASK-004）：入队与游标的原子性、抢占单飞、退避与上限、
 * 卡死回收、注销级联、保留回收、中继端到端重试。
 *
 * <p>模型端口全部替换成桩：这里验的是"我们这边"的任务语义，不是模型提炼得好不好
 * （那由 {@code MemoryAutoExtractionIntegrationTest} 与实测承担）。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class MemoryExtractionJobsIntegrationTest {

    private static final int ROUNDS = 6;

    @Autowired
    MemoryExtractionJobs jobs;

    @Autowired
    MemoryExtractionRelay relay;

    @Autowired
    MemoryExtractionService extraction;

    @Autowired
    ConversationService conversations;

    @Autowired
    CreditService credits;

    @Autowired
    MemoryProperties properties;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    MemoryExtractor extractor;

    private long user;

    @BeforeEach
    void setUp() {
        clear();
        user = Long.parseLong("84" + Long.toString(System.nanoTime() % 1_000_000L));
        // 整理要花钱：余额给足，否则会走"余额不足即收掉任务"那条路
        credits.adjust(user, 100_000, 0L, "test");
    }

    @AfterEach
    void cleanUp() {
        clear();
    }

    private void clear() {
        jdbcTemplate.update("DELETE FROM ai_memory_extraction_jobs");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_user_settings");
        jdbcTemplate.update("DELETE FROM ai_credit_ledger");
        jdbcTemplate.update("DELETE FROM ai_credit_accounts");
    }

    @Test
    @DisplayName("AC-004①：入队与游标推进同一事务——入队失败时游标必须一起回滚")
    void enqueueRollsBackWithCursor() {
        long conversationId = newConversation();
        Long to = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM ai_messages WHERE conversation_id = ?", Long.class, conversationId);
        // 占住"这一段切片"的任务键：真正的 INSERT 会撞唯一键，用来验证整段事务回滚
        jdbcTemplate.update("""
                INSERT INTO ai_memory_extraction_jobs
                    (user_id, conversation_id, from_message_id, to_message_id, status, attempts,
                     next_attempt_at, created_at)
                VALUES (?, ?, 0, ?, 'failed', 0, NOW(3), NOW(3))
                """, user, conversationId, to);

        assertThatThrownBy(() -> jobs.claimAndEnqueue(user, conversationId))
                .isInstanceOf(DataAccessException.class);

        assertThat(cursorOf(conversationId))
                .as("入队失败必须把游标一起回滚，否则这段切片永远不再重提")
                .isNull();
        assertThat(jobCount()).as("只剩占位那一行").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-004②：抢占单飞，attempts 只在抢占时 +1；游标推进到切片终点")
    void claimIsSingleWinner() {
        long conversationId = newConversation();
        MemoryExtractionJob enqueued = jobs.claimAndEnqueue(user, conversationId).orElseThrow();

        assertThat(jobCount()).isEqualTo(1);
        assertThat(statusOf(enqueued)).isEqualTo("pending");
        assertThat(attemptsOf(enqueued)).isZero();

        MemoryExtractionJob claimed = jobs.claim(enqueued.getId()).orElseThrow();
        assertThat(claimed.getAttempts()).isEqualTo(1);
        assertThat(jobs.claim(enqueued.getId())).as("第二次抢占必须抢不到").isEmpty();
        assertThat(attemptsOf(enqueued)).isEqualTo(1);
        assertThat(statusOf(enqueued)).isEqualTo("running");
        assertThat(cursorOf(conversationId)).isEqualTo(enqueued.getToMessageId());
    }

    @Test
    @DisplayName("AC-004③：失败按 2 倍退避重排；到上限置 failed、不再被拾取、last_error 留痕")
    void failBacksOffThenExhausts() {
        long conversationId = newConversation();
        MemoryExtractionJob job = jobs.claimAndEnqueue(user, conversationId).orElseThrow();
        int base = properties.getRetryBackoffSeconds();
        int max = properties.getMaxExtractionAttempts();

        for (int attempt = 1; attempt <= max; attempt++) {
            MemoryExtractionJob claimed = jobs.claim(job.getId()).orElseThrow();
            assertThat(claimed.getAttempts()).isEqualTo(attempt);
            jobs.fail(job.getId(), claimed.getAttempts(), "boom-" + attempt);

            if (attempt < max) {
                assertThat(statusOf(job)).isEqualTo("pending");
                long expected = Math.min((long) base << (attempt - 1), 600);
                assertThat(secondsUntilNextAttempt(job))
                        .as("第 %d 次失败的退避应约 %d 秒", attempt, expected)
                        .isBetween(expected - 10, expected + 10);
            }
        }
        assertThat(statusOf(job)).isEqualTo("failed");
        assertThat(jobs.due(10)).as("failed 行不再被中继拾取").isEmpty();
        assertThat(lastErrorOf(job)).isEqualTo("boom-" + max);
    }

    @Test
    @DisplayName("AC-004④：崩溃留下的 running 行按锁定超时回收成 pending")
    void stuckRunningIsReclaimed() {
        long conversationId = newConversation();
        MemoryExtractionJob job = jobs.claimAndEnqueue(user, conversationId).orElseThrow();
        jobs.claim(job.getId()).orElseThrow();
        // 模拟进程在运行中崩溃：把锁定时间拨到超时之前
        jdbcTemplate.update("UPDATE ai_memory_extraction_jobs SET locked_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(properties.getRunningTimeoutSeconds() + 60L)),
                job.getId());

        assertThat(jobs.reclaimStuck()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(job)).isEqualTo("pending");
    }

    @Test
    @DisplayName("AC-004⑤：成功后任务行消失")
    void completedJobDisappears() {
        long conversationId = newConversation();
        MemoryExtractionJob job = jobs.claimAndEnqueue(user, conversationId).orElseThrow();
        jobs.complete(job.getId());
        assertThat(jobCount()).isZero();
    }

    @Test
    @DisplayName("AC-004⑥：注销级联把任务行清零")
    void deleteByUserClearsJobs() {
        jobs.claimAndEnqueue(user, newConversation()).orElseThrow();
        assertThat(jobCount()).isEqualTo(1);
        assertThat(jobs.deleteByUser(user)).isEqualTo(1);
        assertThat(jobCount()).isZero();
    }

    @Test
    @DisplayName("AC-004⑦：failed 行按保留期回收——诊断留痕但不无限积")
    void failedRowsArePurged() {
        long conversationId = newConversation();
        jdbcTemplate.update("""
                INSERT INTO ai_memory_extraction_jobs
                    (user_id, conversation_id, from_message_id, to_message_id, status, attempts,
                     next_attempt_at, last_error, created_at)
                VALUES (?, ?, 0, 1, 'failed', 3, NOW(3), 'boom', ?)
                """, user, conversationId, Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        assertThat(jobs.dropFailed(Instant.now().minus(properties.getFailedJobRetentionDays(),
                ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(jobCount()).isZero();
    }

    @Test
    @DisplayName("AC-002：不可重试失败直接 failed，attempts 不再增长、next_attempt_at 不推后，ai_runs 标 needs_human")
    void permanentFailureStopsRetry() {
        long conversationId = newConversation();
        when(extractor.extract(any(), any()))
                .thenThrow(new BizException(ErrorCode.INVALID_ARGUMENT, "上游拒绝了这组凭据"));

        extraction.scheduleIfDue(user, conversationId);

        if (!Await.until(() -> "failed".equals(jobStatus()), 10_000, 50)) {
            throw new AssertionError("等永久失败落定超时");
        }
        assertThat(jobStatus()).isEqualTo("failed");
        assertThat(latestJobAttempts()).isEqualTo(1);
        assertThat(secondsBetweenCreatedAndNextAttempt()).isZero();
        if (!Await.until(() -> "needs_human".equals(latestRunFailureKind()), 5_000, 50)) {
            throw new AssertionError("等 ai_runs.failure_kind 落库超时");
        }
        assertThat(latestRunFailureKind()).isEqualTo("needs_human");
    }

    @Test
    @DisplayName("AC-002：retryable 失败仍按既有退避重排")
    void retryableFailureStillBacksOff() {
        long conversationId = newConversation();
        when(extractor.extract(any(), any())).thenThrow(new IllegalStateException("upstream timeout"));

        extraction.scheduleIfDue(user, conversationId);
        awaitFailedAttemptOne();

        assertThat(jobStatus()).isEqualTo("pending");
        assertThat(latestJobAttempts()).isEqualTo(1);
        assertThat(secondsUntilNextAttemptForUser()).isBetween(
                (long) properties.getRetryBackoffSeconds() - 10,
                (long) properties.getRetryBackoffSeconds() + 10);
    }

    @Test
    @DisplayName("端到端：失败留下的任务被中继拾起重试成功——记忆最终写上、任务行消失")
    void relayRetriesFailedJobToSuccess() {
        long conversationId = newConversation();
        AtomicInteger calls = new AtomicInteger();
        when(extractor.extract(any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("第一次炸");
            }
            return new MemoryExtractor.MemoryExtraction(List.of(new MemoryCandidate(
                    MemoryCandidate.Action.ADD, MemoryCandidate.Store.TEXT, null, null, null,
                    "重试之后记下的", "第二次成功")), null);
        });

        extraction.scheduleIfDue(user, conversationId);
        awaitFailedAttemptOne();

        // 把退避清零（等价于"过了一分钟"），让中继立刻能拾
        jdbcTemplate.update("UPDATE ai_memory_extraction_jobs SET next_attempt_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), user);
        relay.tick();

        awaitMemoryText("重试之后记下的");
        assertThat(jobCount()).as("两次尝试都已被消费，任务行应当消失").isZero();
        assertThat(calls.get()).isEqualTo(2);
    }

    // ---- 辅助 ----

    private long newConversation() {
        long conversationId = conversations.create(user, null);
        for (int i = 0; i < ROUNDS; i++) {
            conversations.complete(user, conversationId, "第 " + i + " 轮回答", null);
        }
        return conversationId;
    }

    private Long cursorOf(long conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_memory_extract_message_id FROM ai_conversations WHERE id = ?",
                Long.class, conversationId);
    }

    private int jobCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memory_extraction_jobs WHERE user_id = ?", Integer.class, user);
        return count == null ? 0 : count;
    }

    private String statusOf(MemoryExtractionJob job) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ai_memory_extraction_jobs WHERE id = ?", String.class, job.getId());
    }

    private int attemptsOf(MemoryExtractionJob job) {
        return jdbcTemplate.queryForObject(
                "SELECT attempts FROM ai_memory_extraction_jobs WHERE id = ?", Integer.class, job.getId());
    }

    private String lastErrorOf(MemoryExtractionJob job) {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM ai_memory_extraction_jobs WHERE id = ?", String.class, job.getId());
    }

    private String jobStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ai_memory_extraction_jobs WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, user);
    }

    private int latestJobAttempts() {
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempts FROM ai_memory_extraction_jobs WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class, user);
        return attempts == null ? 0 : attempts;
    }

    private long secondsBetweenCreatedAndNextAttempt() {
        Long seconds = jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, created_at, next_attempt_at) "
                        + "FROM ai_memory_extraction_jobs WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, user);
        return seconds == null ? 0 : seconds;
    }

    private String latestRunFailureKind() {
        var rows = jdbcTemplate.queryForList(
                "SELECT failure_kind FROM ai_runs WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, user);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 当前用户最新任务距离下一次可尝试还有几秒（按应用视角的 UTC 比较）。 */
    private long secondsUntilNextAttemptForUser() {
        Timestamp next = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM ai_memory_extraction_jobs WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                Timestamp.class, user);
        return next.toInstant().getEpochSecond() - Instant.now().getEpochSecond();
    }

    /** 距离下一次可尝试还有几秒（按应用视角的 UTC 比较）。 */
    private long secondsUntilNextAttempt(MemoryExtractionJob job) {
        Timestamp next = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM ai_memory_extraction_jobs WHERE id = ?",
                Timestamp.class, job.getId());
        return next.toInstant().getEpochSecond() - Instant.now().getEpochSecond();
    }

    /** 等到"第一次尝试失败、已在退避中"这个状态落定，避免和即时派发抢时序。 */
    private void awaitFailedAttemptOne() {
        if (!Await.until(() -> {
            Integer failedOnce = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_memory_extraction_jobs "
                            + "WHERE user_id = ? AND status = 'pending' AND attempts = 1 AND last_error IS NOT NULL",
                    Integer.class, user);
            return failedOnce != null && failedOnce == 1;
        }, 10_000, 50)) {
            throw new AssertionError("等第一次失败落定超时");
        }
    }

    /** 等记忆写入（异步那一步真正跑完）。 */
    private void awaitMemoryText(String expected) {
        if (!Await.until(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_memories_text WHERE user_id = ? AND content = ?",
                    Integer.class, user, expected);
            return count != null && count >= 1;
        }, 10_000, 50)) {
            throw new AssertionError("等重试写入记忆超时");
        }
    }
}