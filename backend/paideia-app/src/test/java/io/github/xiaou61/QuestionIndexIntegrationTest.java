package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.knowledge.internal.question.QuestionIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 题库派生索引的集成测试（WORK-083 TASK-001）。
 *
 * <p>钉住四件事：只索引正文里合法的题（坏块跳过）、正文删题后对应索引消失、
 * 条目转草稿后索引清空、有 Elo 校准时难度标签随评分变化。用自建条目而不是改种子。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class QuestionIndexIntegrationTest {

    private static final String BLOCK = "it-index";

    private static final String QUIZ_BODY = """
            ```quiz
            {"id":"it-index","questions":[
              {"question":"两数相加用哪个运算符？","options":["+","-"],"answer":0},
              {"question":"下面哪个是关键字？","options":["class","kitty","colour"],"answer":0}
            ]}
            ```
            ```quiz
            { this block is not valid json }
            ```
            """;

    @Autowired
    QuestionIndexService indexService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long entryId;
    private final String entryTitle = "索引集成测试-" + System.nanoTime();

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                INSERT INTO knowledge_entries (title, body, status, published_at)
                VALUES (?, ?, 'published', UTC_TIMESTAMP(3))
                """, entryTitle, QUIZ_BODY);
        entryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE title = ?", Long.class, entryTitle);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM question_index WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM question_difficulty WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
    }

    private int indexCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM question_index WHERE entry_id = ?", Integer.class, entryId);
    }

    @Test
    @DisplayName("重建只索引正文里合法的题，坏块跳过，未校准按规则给初始难度")
    void reconcileIndexesValidQuestionsOnly() {
        indexService.reconcile(entryId);

        assertThat(indexCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT question_key FROM question_index WHERE entry_id = ? ORDER BY question_key",
                String.class, entryId)).containsExactly(BLOCK + "#0", BLOCK + "#1");
        // #0 只有 2 个选项 → 规则判易；#1 有 3 个选项 → 规则保持 medium
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM question_index
                WHERE entry_id = ? AND difficulty_label = 'easy'
                """, Integer.class, entryId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM question_index
                WHERE entry_id = ? AND difficulty_label = 'medium'
                """, Integer.class, entryId)).isEqualTo(1);
    }

    @Test
    @DisplayName("正文删掉一道题后重建，对应索引行消失")
    void reconcileDropsRemovedQuestion() {
        indexService.reconcile(entryId);
        assertThat(indexCount()).isEqualTo(2);

        jdbcTemplate.update("UPDATE knowledge_entries SET body = ? WHERE id = ?", """
                ```quiz
                {"id":"it-index","questions":[
                  {"question":"两数相加用哪个运算符？","options":["+","-"],"answer":0}
                ]}
                ```
                """, entryId);
        indexService.reconcile(entryId);

        assertThat(indexCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT question_key FROM question_index WHERE entry_id = ?", String.class, entryId))
                .containsExactly(BLOCK + "#0");
    }

    @Test
    @DisplayName("条目转草稿后重建，该条目索引清空")
    void reconcileRemovesIndexWhenUnpublished() {
        indexService.reconcile(entryId);
        assertThat(indexCount()).isEqualTo(2);

        jdbcTemplate.update("UPDATE knowledge_entries SET status = 'draft' WHERE id = ?", entryId);
        indexService.reconcile(entryId);

        assertThat(indexCount()).isZero();
    }

    @Test
    @DisplayName("有 Elo 校准（>=5 次）时难度标签随评分变化")
    void difficultyLabelUsesCalibratedElo() {
        indexService.reconcile(entryId);
        String fingerprint = jdbcTemplate.queryForObject("""
                SELECT fingerprint FROM question_index
                WHERE entry_id = ? AND question_key = ?
                """, String.class, entryId, BLOCK + "#0");

        jdbcTemplate.update("""
                INSERT INTO question_difficulty
                    (fingerprint, entry_id, block_key, question_key, rating, attempts, correct, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))
                """, fingerprint, entryId, BLOCK, BLOCK + "#0", 1400, 6, 0);
        indexService.reconcile(entryId);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT difficulty_label FROM question_index
                WHERE entry_id = ? AND question_key = ?
                """, String.class, entryId, BLOCK + "#0")).isEqualTo("hard");
    }
}
