package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.xiaou61.ai.MemoryCandidate;
import io.github.xiaou61.ai.internal.memory.MemoryStore;
import io.github.xiaou61.platform.BizException;
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
 * 敏感信息过滤的**接线**验证（WORK-050 AC-001）。
 *
 * <p>纯函数本身由 {@code SensitiveContentTest} 覆盖；这里要证明的是"它真的接在写入路径上"——
 * 一条通过判定的内容，**两张正式表里都不该有它**。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class MemoryFilterTest {

    private static final String PHONE = "13812345678";
    private static final String CONNECTION = "jdbc:mysql://root:pw@10.0.0.1:3306/db";

    @Autowired
    MemoryStore memories;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
        userId = 9_052_000L + (System.nanoTime() % 1000);
    }

    @Test
    @DisplayName("手动写入含手机号/连接串：被拒，且库里没有该串")
    void manualWriteRejectsSensitive() {
        assertThatThrownBy(() -> memories.rememberText(userId, "他手机号是 " + PHONE))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> memories.rememberStructured(userId, "preference", null, "连接串 " + CONNECTION))
                .isInstanceOf(BizException.class);

        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("自动整理：命中敏感的那条被丢掉，同批其它候选照常写入")
    void autoExtractionDropsOnlyTheSensitiveCandidate() {
        List<MemoryCandidate> candidates = List.of(
                new MemoryCandidate(MemoryCandidate.Action.ADD, MemoryCandidate.Store.TEXT, null, null, null,
                        "他手机号 " + PHONE, "用户说了自己的号码"),
                new MemoryCandidate(MemoryCandidate.Action.ADD, MemoryCandidate.Store.STRUCTURED, null,
                        "weak_topic", null, "他不懂 Redis 持久化", "用户说没搞懂"));

        int affected = memories.applyAuto(userId, 1L, 2L, candidates);

        assertThat(affected).as("只有干干净的那条被写进去").isEqualTo(1);
        assertThat(count()).isEqualTo(1);
        Integer leaked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memories_text WHERE content LIKE ?", Integer.class, "%" + PHONE + "%");
        assertThat(leaked).isZero();
    }

    private int count() {
        Integer structured = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memories_structured", Integer.class);
        Integer text = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_memories_text", Integer.class);
        return (structured == null ? 0 : structured) + (text == null ? 0 : text);
    }
}
