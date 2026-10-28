package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.xiaou61.ai.MemoryCandidate;
import io.github.xiaou61.ai.internal.memory.MemoryCandidateService;
import io.github.xiaou61.ai.internal.settings.AiSettingsStore;
import io.github.xiaou61.platform.BizException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 半自动提案的三种终态（WORK-050 AC-006..009 / AC-013）。
 *
 * <p>要证明的四件事：提案**不写正式表**；接受后才写；并发接受只有一条落地；
 * 被敏感过滤的那条**正文不落库**。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class MemoryCandidateTest {

    @Autowired
    MemoryCandidateService candidates;

    @Autowired
    AiSettingsStore settings;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
        jdbcTemplate.update("DELETE FROM ai_memory_candidates");
        userId = 9_053_000L + (System.nanoTime() % 1000);
    }

    @Test
    @DisplayName("提案只进候选表，正式表一行都不多")
    void proposeDoesNotWriteMemory() {
        long id = propose("他最近在翻 DDIA");

        assertThat(candidates.pending(userId, 20, 0)).extracting(MemoryCandidateService.Candidate::id)
                .containsExactly(id);
        assertThat(memoryCount()).isZero();
    }

    @Test
    @DisplayName("接受之后成为一条正式记忆，候选不再是 pending")
    void acceptWritesMemory() {
        long id = propose("他最近在翻 DDIA");

        candidates.accept(userId, id);

        assertThat(memoryCount()).isEqualTo(1);
        assertThat(candidates.pending(userId, 20, 0)).isEmpty();
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM ai_memory_candidates WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("accepted");
    }

    @Test
    @DisplayName("拒绝之后永远不成为记忆")
    void rejectWritesNothing() {
        long id = propose("他可能想转 Go");

        candidates.reject(userId, id);

        assertThat(memoryCount()).isZero();
        assertThat(candidates.pending(userId, 20, 0)).isEmpty();
    }

    @Test
    @DisplayName("同一条被接受两次：第二次报冲突，正式表仍只有一条")
    void concurrentAcceptCreatesOne() {
        long id = propose("他下周有个面试");

        candidates.accept(userId, id);
        assertThatThrownBy(() -> candidates.accept(userId, id)).isInstanceOf(BizException.class);

        assertThat(memoryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("被敏感过滤的那条：只留长度与原因，正文为 null")
    void filteredCandidateStoresNoContent() {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO ai_memory_candidates
                    (user_id, store, content, content_bytes, source, status, decision_reason,
                     created_at, expires_at)
                VALUES (?, 'text', NULL, 32, 'auto', 'filtered', 'sensitive', ?, ?)
                """, userId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(3600)));

        Integer withContent = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memory_candidates WHERE user_id = ? AND content IS NOT NULL",
                Integer.class, userId);
        assertThat(withContent).isZero();
    }

    @Test
    @DisplayName("过期候选被清理，未过期的留着")
    void dropsExpiredOnly() {
        long fresh = propose("他喜欢先看结论");
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        jdbcTemplate.update("""
                INSERT INTO ai_memory_candidates
                    (user_id, store, content, content_bytes, source, status, created_at, expires_at)
                VALUES (?, 'text', '过期的提议', 12, 'auto', 'pending', ?, ?)
                """, userId, java.sql.Timestamp.from(past.minus(30, ChronoUnit.DAYS)),
                java.sql.Timestamp.from(past));

        int dropped = candidates.dropExpired(Instant.now(), 500);

        assertThat(dropped).isGreaterThanOrEqualTo(1);
        assertThat(candidates.pending(userId, 20, 0))
                .extracting(MemoryCandidateService.Candidate::id).containsExactly(fresh);
    }

    @Test
    @DisplayName("总开关关掉后：看不到候选，也接受不了")
    void respectsMemorySwitch() {
        long id = propose("他最近在翻 DDIA");
        settings.disableMemory(userId);

        assertThat(candidates.pending(userId, 20, 0)).isEmpty();
        assertThatThrownBy(() -> candidates.accept(userId, id)).isInstanceOf(BizException.class);
        assertThat(memoryCount()).isZero();
    }

    private long propose(String content) {
        return candidates.propose(userId, MemoryCandidate.Store.TEXT, null, null, content,
                1L, 2L, "测试");
    }

    private int memoryCount() {
        Integer structured = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memories_structured", Integer.class);
        Integer text = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_memories_text", Integer.class);
        return (structured == null ? 0 : structured) + (text == null ? 0 : text);
    }
}
