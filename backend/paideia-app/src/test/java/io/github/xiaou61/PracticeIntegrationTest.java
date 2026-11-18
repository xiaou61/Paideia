package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * 刷题会话集成测试（WORK-083 TASK-004）：只刷未做/只刷错题取交集、响应无答案、
 * 作答走既有 attempts 且不产生 paper 行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class PracticeIntegrationTest {

    private static final String QUIZ_BODY = """
            ```quiz
            {"id":"it-practice","questions":[
              {"question":"P one?","options":["a","b"],"answer":0},
              {"question":"P two?","options":["a","b"],"answer":1}
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
    private final long subject = 666_666L;

    @BeforeEach
    void setUp() {
        String title = "刷题IT-" + System.nanoTime();
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
        jdbcTemplate.update("DELETE FROM question_attempts WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM concept_mastery_state WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM learning_progress WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM question_index WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM question_difficulty WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
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

    private String session(String filterJson) throws Exception {
        return mockMvc.perform(post("/api/v1/learning/practice/sessions")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(filterJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("只刷未做 / 只刷错题取交集，响应无答案，且不产生 paper 行")
    void practiceFiltersWork() throws Exception {
        List<Map<String, Object>> rows = identities();
        assertThat(rows).hasSize(2);
        String block0 = (String) rows.get(0).get("block_key");
        String key0 = (String) rows.get(0).get("question_key");
        String block1 = (String) rows.get(1).get("block_key");
        String key1 = (String) rows.get(1).get("question_key");

        String undone = session("{\"onlyUndone\":true,\"limit\":20}");
        assertThat(undone).doesNotContain("answerIndex");
        assertThat(countItems(undone)).isEqualTo(2);

        // 第一题答对（答案 0）；第二题答错（答案 1，此处选 0）
        answer(block0, key0, 0);
        answer(block1, key1, 0);

        String undoneAfter = session("{\"onlyUndone\":true,\"limit\":20}");
        assertThat(countItems(undoneAfter)).isZero();

        String wrong = session("{\"onlyWrong\":true,\"limit\":20}");
        assertThat(countItems(wrong)).isEqualTo(1);
        assertThat(wrong).contains(key1);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM papers WHERE user_id = ?",
                Integer.class, subject)).isZero();
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
}
