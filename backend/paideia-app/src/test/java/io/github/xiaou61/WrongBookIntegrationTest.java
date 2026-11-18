package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.knowledge.internal.question.QuestionIndexService;
import io.github.xiaou61.security.AuthPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 错题本集成测试（WORK-083 TASK-005）：只返回最新一次答错、最新答对即移出、
 * 可按概念过滤、按用户隔离、响应不含答案。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class WrongBookIntegrationTest {

    private static final String QUIZ_BODY = """
            ```quiz
            {"id":"it-wrong","questions":[
              {"question":"W one?","options":["a","b"],"answer":0},
              {"question":"W two?","options":["a","b"],"answer":1}
            ]}
            ```
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuthPort authPort;

    @Autowired
    QuestionIndexService indexService;

    private long entryId;
    private long conceptId = -1L;
    private final long subject = 777_777L;

    @BeforeEach
    void setUp() {
        String title = "错题IT-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO knowledge_entries (title, body, status, published_at)
                VALUES (?, ?, 'published', UTC_TIMESTAMP(3))
                """, title, QUIZ_BODY);
        entryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE title = ?", Long.class, title);
        indexService.reconcile(entryId);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM question_concepts WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM question_attempts WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM concept_mastery_state WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM learning_progress WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM question_index WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM question_difficulty WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
        if (conceptId > 0) {
            jdbcTemplate.update("DELETE FROM knowledge_concepts WHERE id = ?", conceptId);
        }
    }

    private String token() {
        return TestTokens.bearer(authPort, String.valueOf(subject));
    }

    private List<Map<String, Object>> identities() {
        return jdbcTemplate.queryForList(
                "SELECT block_key, question_key FROM question_index WHERE entry_id = ? ORDER BY question_key",
                entryId);
    }

    private void answer(String blockKey, String questionKey, int choice) throws Exception {
        String body = "{\"entryId\":" + entryId + ",\"blockKey\":\"" + blockKey
                + "\",\"questionKey\":\"" + questionKey + "\",\"choice\":" + choice + "}";
        mockMvc.perform(post("/api/v1/learning/attempts")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String wrong(Long conceptIdParam, String bearer) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/learning/wrong-questions");
        if (conceptIdParam != null) {
            request = request.param("conceptId", String.valueOf(conceptIdParam));
        }
        return mockMvc.perform(request.header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static int countItems(String json) {
        int count = 0;
        int index = 0;
        while ((index = json.indexOf("\"questionKey\"", index)) >= 0) {
            count++;
            index += "\"questionKey\"".length();
        }
        return count;
    }

    @Test
    @DisplayName("只返回最新一次答错；响应不含答案")
    void returnsLatestWrongOnly() throws Exception {
        List<Map<String, Object>> rows = identities();
        String block0 = (String) rows.get(0).get("block_key");
        String key0 = (String) rows.get(0).get("question_key");
        String block1 = (String) rows.get(1).get("block_key");
        String key1 = (String) rows.get(1).get("question_key");

        answer(block0, key0, 0); // 对
        answer(block1, key1, 0); // 错（答案 1）

        String json = wrong(null, token());
        assertThat(json).doesNotContain("answerIndex");
        assertThat(countItems(json)).isEqualTo(1);
        assertThat(json).contains(key1);
        assertThat(json).doesNotContain(key0);
    }

    @Test
    @DisplayName("最新一次答对即移出错题本")
    void latestCorrectRemovesFromWrongBook() throws Exception {
        List<Map<String, Object>> rows = identities();
        String block1 = (String) rows.get(1).get("block_key");
        String key1 = (String) rows.get(1).get("question_key");
        answer(block1, key1, 0); // 错
        assertThat(countItems(wrong(null, token()))).isEqualTo(1);

        answer(block1, key1, 1); // 对
        assertThat(countItems(wrong(null, token()))).isZero();
    }

    @Test
    @DisplayName("按概念过滤；按用户隔离")
    void conceptFilterAndUserIsolation() throws Exception {
        List<Map<String, Object>> rows = identities();
        String block1 = (String) rows.get(1).get("block_key");
        String key1 = (String) rows.get(1).get("question_key");
        answer(block1, key1, 0); // 错

        String conceptName = "错题概念-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO knowledge_concepts (name, summary, category_id, created_at, updated_at)
                VALUES (?, NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, conceptName);
        conceptId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_concepts WHERE name = ?", Long.class, conceptName);
        jdbcTemplate.update("""
                INSERT INTO question_concepts
                    (entry_id, block_key, question_key, concept_id, weight, source, confidence, created_at)
                VALUES (?, ?, ?, ?, 1.0, 'author', NULL, UTC_TIMESTAMP(3))
                """, entryId, block1, key1, conceptId);

        assertThat(countItems(wrong(conceptId, token()))).isEqualTo(1);
        assertThat(countItems(wrong(conceptId + 999, token()))).isZero();

        // 他人只看到自己的错题（空）
        String other = TestTokens.bearer(authPort, "888888");
        assertThat(countItems(wrong(null, other))).isZero();
    }
}
