package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.internal.memory.MemoryStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 按时间淘汰与"最近使用"的刷新（WORK-050 TASK-006 / TASK-007，AC-003 / AC-004 / AC-005）。
 *
 * <p>核心要证明的一件事：**判据是"最近一次被召回"，不是"创建时间"**。只按创建时间淘汰会把
 * "他喜欢短回答"这种一直有效的事实删掉，而那正是用户最不能接受的失败——"它又忘了我"。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class MemoryPurgeTest {

    @Autowired
    MemoryStore memories;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
        userId = 9_051_000L + (System.nanoTime() % 1000);
    }

    @Test
    @DisplayName("长期未召回的弱项按 TTL 删掉，同期偏好保留，被召回过的旧偏好保留")
    void purgesByThirdsButKeepsRecentlyUsed() {
        Instant now = Instant.now();
        long staleWeak = insertStructured("weak_topic", "他不懂 Redis 持久化", now.minus(200, ChronoUnit.DAYS), null);
        long stalePreference = insertStructured("preference", "他喜欢先给结论", now.minus(200, ChronoUnit.DAYS), null);
        long oldButUsed = insertStructured("preference", "他希望例子用 Java",
                now.minus(400, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
        long freshWeak = insertStructured("weak_topic", "他不清楚索引失效", now.minus(10, ChronoUnit.DAYS), null);

        int deleted = memories.purgeExpired(now);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(exists(staleWeak)).as("200 天没被召回的弱项该被删（TTL 90）").isFalse();
        assertThat(exists(stalePreference)).as("200 天的偏好不该删（TTL 365）").isTrue();
        assertThat(exists(oldButUsed)).as("400 天但昨天还被召回过的偏好必须留下").isTrue();
        assertThat(exists(freshWeak)).as("10 天的弱项不该删").isTrue();
    }

    @Test
    @DisplayName("全文那一路按自己的 TTL 淘汰")
    void purgesTextByItsOwnTtl() {
        Instant now = Instant.now();
        long stale = insertText("他说自己在学 Python", now.minus(400, ChronoUnit.DAYS), null);
        long fresh = insertText("他说他今年大三", now.minus(10, ChronoUnit.DAYS), null);

        memories.purgeExpired(now);

        assertThat(exists(stale)).as("400 天的原话该被删（TTL 180）").isFalse();
        assertThat(exists(fresh)).isTrue();
    }

    @Test
    @DisplayName("连续执行两次，第二次删除 0 行")
    void isIdempotent() {
        Instant now = Instant.now();
        insertStructured("weak_topic", "他不懂 GC 日志", now.minus(500, ChronoUnit.DAYS), null);

        assertThat(memories.purgeExpired(now)).isGreaterThanOrEqualTo(1);
        assertThat(memories.purgeExpired(now)).as("删过一遍之后再跑不该再删").isZero();
    }

    @Test
    @DisplayName("touch 不会把 last_used_at 往回写")
    void touchNeverMovesBackwards() {
        Instant now = Instant.now();
        long recent = insertStructured("weak_topic", "他不清楚 rebalance", now.minus(30, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.HOURS));

        memories.touch(userId, List.of(recent), List.of());

        // DATETIME 列在 JDBC 上是 LocalDateTime（会话已被强制为 UTC），按 UTC 换算
        LocalDateTime after = jdbcTemplate.queryForObject(
                "SELECT last_used_at FROM ai_memories_structured WHERE id = ?", LocalDateTime.class, recent);
        assertThat(after.toInstant(ZoneOffset.UTC)).as("更早的 now 不该覆盖更晚的 last_used_at")
                .isAfter(now.minus(2, ChronoUnit.HOURS));
    }

    private long insertStructured(String kind, String content, Instant createdAt, Instant lastUsedAt) {
        jdbcTemplate.update("""
                INSERT INTO ai_memories_structured
                    (user_id, kind, content, source, created_at, last_used_at)
                VALUES (?, ?, ?, 'auto', ?, ?)
                """, userId, kind, content, Timestamp.from(createdAt),
                lastUsedAt == null ? null : Timestamp.from(lastUsedAt));
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertText(String content, Instant createdAt, Instant lastUsedAt) {
        jdbcTemplate.update("""
                INSERT INTO ai_memories_text (user_id, content, source, created_at, last_used_at)
                VALUES (?, ?, 'auto', ?, ?)
                """, userId, content, Timestamp.from(createdAt),
                lastUsedAt == null ? null : Timestamp.from(lastUsedAt));
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private boolean exists(long id) {
        Integer structured = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memories_structured WHERE id = ?", Integer.class, id);
        Integer text = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memories_text WHERE id = ?", Integer.class, id);
        return (structured != null && structured > 0) || (text != null && text > 0);
    }
}
