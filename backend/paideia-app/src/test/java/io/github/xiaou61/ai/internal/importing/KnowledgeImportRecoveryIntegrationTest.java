package io.github.xiaou61.ai.internal.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * WORK-092 TASK-006 / REQ-005：知识导入的领取令牌（fencing）与超时回收的真实 SQL 契约。
 *
 * <p>钉死两件旧实现没有保证的事：
 * <ul>
 *   <li>过期 worker 拿着旧令牌写结果会被丢弃，不会覆盖新 worker 的结果；</li>
 *   <li>领取后被杀、{@code processing_since} 超时的任务会被回收为 pending，旧令牌随之失效。</li>
 * </ul>
 * 需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class KnowledgeImportRecoveryIntegrationTest {

    private static final String FILE_PREFIX = "it-lease-";

    @Autowired
    KnowledgeImportMapper jobs;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_knowledge_imports WHERE file_id LIKE ?", FILE_PREFIX + "%");
    }

    private long newPending() {
        KnowledgeImport job = new KnowledgeImport();
        job.setFileId(FILE_PREFIX + System.nanoTime());
        job.setStatus(KnowledgeImportService.STATUS_PENDING);
        job.setOperatorId(9L);
        jobs.insert(job);
        return job.getId();
    }

    @Test
    @DisplayName("领取写入令牌；过期令牌写结果被 fencing 丢弃")
    void staleClaimCannotOverwriteResult() {
        long id = newPending();
        assertThat(jobs.markClaimed(id, "token-a")).isEqualTo(1);
        // 已被领取，同一任务第二次领取失败（只有 pending 能被领取）
        assertThat(jobs.markClaimed(id, "token-b")).isZero();
        // 过期 worker（token-b）写结果：fencing 拒绝，不覆盖
        assertThat(jobs.updateResult(id, KnowledgeImportService.STATUS_SUCCEEDED, 11L, null, "token-b")).isZero();
        // 正确令牌写结果成功
        assertThat(jobs.updateResult(id, KnowledgeImportService.STATUS_SUCCEEDED, 11L, null, "token-a")).isEqualTo(1);

        KnowledgeImport row = jobs.findById(id);
        assertThat(row.getStatus()).isEqualTo(KnowledgeImportService.STATUS_SUCCEEDED);
        assertThat(row.getEntryId()).isEqualTo(11L);
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("超时未完成的 processing 被回收为 pending 并清空令牌，旧令牌彻底失效")
    void staleProcessingIsRequeued() {
        long id = newPending();
        assertThat(jobs.markClaimed(id, "token-a")).isEqualTo(1);
        // 把领取时间拨回超过阈值，模拟"领取后进程被杀"
        jdbcTemplate.update("UPDATE ai_knowledge_imports SET processing_since = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), id);

        assertThat(jobs.requeueStale(Instant.now().minusSeconds(600))).isGreaterThanOrEqualTo(1);
        KnowledgeImport requeued = jobs.findById(id);
        assertThat(requeued.getStatus()).isEqualTo(KnowledgeImportService.STATUS_PENDING);
        assertThat(requeued.getClaimToken()).isNull();
        assertThat(requeued.getProcessingSince()).isNull();

        // 回收后可重新领取（新令牌），旧令牌彻底失效
        assertThat(jobs.markClaimed(id, "token-b")).isEqualTo(1);
        assertThat(jobs.updateResult(id, KnowledgeImportService.STATUS_FAILED, null, "x", "token-a")).isZero();
        assertThat(jobs.updateResult(id, KnowledgeImportService.STATUS_FAILED, null, "x", "token-b")).isEqualTo(1);
    }
}
