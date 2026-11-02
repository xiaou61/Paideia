package io.github.xiaou61;


import io.github.xiaou61.security.AuthPort;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 到期复习列表（WORK-034 S1）：只返回到期项、按到期日升序、带概念名。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class LearningReviewIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuthPort authPort;

    /** 出题端口打桩：集成测试不碰真实上游（否则每次跑都要等一次连接超时）。 */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    io.github.xiaou61.knowledge.ReviewQuestionPort reviewQuestions;

    private final long subject = 334_455L;
    private long firstEntry;
    private long secondEntry;
    private long thirdEntry;
    private long conceptId;
    private String conceptName;

    @BeforeEach
    void setUp() {
        List<Long> entries = jdbcTemplate.queryForList(
                "SELECT id FROM knowledge_entries WHERE status = 'published' ORDER BY id LIMIT 3", Long.class);
        firstEntry = entries.get(0);
        secondEntry = entries.get(1);
        thirdEntry = entries.get(2);

        insertProgress(firstEntry, "-2 HOUR");
        insertProgress(secondEntry, "-1 HOUR");
        insertProgress(thirdEntry, "+1 DAY");

        conceptName = "复习概念-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO knowledge_concepts (name, summary, created_at, updated_at)
                VALUES (?, '复习列表测试', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, conceptName);
        conceptId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_concepts WHERE name = ?", Long.class, conceptName);
        jdbcTemplate.update("""
                INSERT INTO knowledge_entry_concepts (entry_id, concept_id, created_at)
                VALUES (?, ?, UTC_TIMESTAMP(3))
                """, secondEntry, conceptId);
    }

    private void insertProgress(long entryId, String offset) {
        jdbcTemplate.update("""
                INSERT INTO learning_progress
                    (user_id, entry_id, challenges_json, quizzes_json, status, review_stage, review_due_at)
                VALUES (?, ?, '{}', '{}', 'passed', 2, DATE_ADD(UTC_TIMESTAMP(3), INTERVAL %s))
                """.formatted(offset), subject, entryId);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM review_sessions WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM learning_progress WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM knowledge_entry_concepts WHERE concept_id = ?", conceptId);
        jdbcTemplate.update("DELETE FROM knowledge_concepts WHERE id = ?", conceptId);
    }

    private String token() {
        return TestTokens.bearer(authPort, String.valueOf(subject));
    }

    @Test
    @DisplayName("只返回到期项，并按到期日升序")
    void dueReviewsAreOrderedByDueDate() throws Exception {
        mockMvc.perform(get("/api/v1/learning/reviews").header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].entryId").value(firstEntry))
                .andExpect(jsonPath("$.data.items[1].entryId").value(secondEntry));
    }

    @Test
    @DisplayName("条目挂了概念时带出概念名")
    void dueReviewsIncludeConceptName() throws Exception {
        mockMvc.perform(get("/api/v1/learning/reviews").header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[1].conceptId").value(conceptId))
                .andExpect(jsonPath("$.data.items[1].conceptName").value(conceptName));
    }

    @Test
    @DisplayName("没有 AI 凭据时开始复习返回承载条目降级，不报错")
    void startFallsBackWhenGenerationFails() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/learning/reviews/sessions").header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(0))
                .andExpect(jsonPath("$.data.fallback[0].entryId").exists());
    }

    @Test
    @DisplayName("交卷推进档位，重复交卷 409")
    void submitAdvancesStageAndIsIdempotent() throws Exception {
        long sessionId = insertSession("+20 MINUTE");
        String body = """
                {"answers":[{"itemId":0,"choice":1}]}
                """;
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/learning/reviews/sessions/" + sessionId + "/answers")
                        .header("Authorization", token())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].correct").value(true))
                .andExpect(jsonPath("$.data.schedule[0].entryId").value(secondEntry))
                .andExpect(jsonPath("$.data.schedule[0].stage").value(3));

        Integer stage = jdbcTemplate.queryForObject("""
                SELECT review_stage FROM learning_progress WHERE user_id = ? AND entry_id = ?
                """, Integer.class, subject, secondEntry);
        assertThat(stage).isEqualTo(3);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/learning/reviews/sessions/" + sessionId + "/answers")
                        .header("Authorization", token())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("过期会话拒绝交卷；别人的会话 404")
    void expiredAndForeignSessionRejected() throws Exception {
        long expired = insertSession("-1 MINUTE");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/learning/reviews/sessions/" + expired + "/answers")
                        .header("Authorization", token())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"itemId\":0,\"choice\":1}]}"))
                .andExpect(status().isConflict());

        String other = TestTokens.bearer(authPort, "334456");
        long future = insertSession("+20 MINUTE");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/learning/reviews/sessions/" + future + "/answers")
                        .header("Authorization", other)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"itemId\":0,\"choice\":1}]}"))
                .andExpect(status().isNotFound());
    }

    private long insertSession(String expiresOffset) {
        String items = """
                [{"itemId":0,"entryId":%d,"conceptId":%d,"stem":"这是一道复习题","options":["A","B","C","D"],
                  "answerIndex":1,"explanation":"选 B","fingerprint":"%s"}]
                """.formatted(secondEntry, conceptId, "a".repeat(64));
        long id = 9_900_000L + Math.abs(System.nanoTime() % 100_000);
        jdbcTemplate.update("""
                INSERT INTO review_sessions
                    (id, user_id, concept_ids_json, items_json, created_at, expires_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(3), DATE_ADD(UTC_TIMESTAMP(3), INTERVAL %s))
                """.formatted(expiresOffset), id, subject, "[" + conceptId + "]", items);
        return id;
    }
}