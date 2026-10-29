package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.security.AuthPort;
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
 * 逐题作答的集成测试（WORK-033）。
 *
 * <p>钉住三件事：服务端按正文判定（客户端不发 correct）、掌握度随作答上下移动、
 * 越界选择返回 400 且不落库。用自建条目而不是改种子，避免影响同库其它用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class QuestionAttemptIntegrationTest {

    private static final String BLOCK = "it-quiz";
    private static final String QUIZ_BODY = """
            ```quiz
            {"id":"it-quiz","questions":[
              {"question":"两数相加用哪个运算符？","options":["+","-"],"answer":0}
            ]}
            ```
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuthPort authPort;

    private long entryId;
    private long conceptId;
    private String entryTitle;
    private String conceptName;
    private final long subject = 333_333L;

    @BeforeEach
    void setUp() {
        entryTitle = "作答集成测试-" + System.nanoTime();
        conceptName = "作答概念-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO knowledge_entries (title, body, status, published_at)
                VALUES (?, ?, 'published', UTC_TIMESTAMP(3))
                """, entryTitle, QUIZ_BODY);
        entryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE title = ?", Long.class, entryTitle);
        jdbcTemplate.update("""
                INSERT INTO knowledge_concepts (name, summary, created_at, updated_at)
                VALUES (?, '集成测试概念', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, conceptName);
        conceptId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_concepts WHERE name = ?", Long.class, conceptName);
        jdbcTemplate.update("""
                INSERT INTO question_concepts
                    (entry_id, block_key, question_key, concept_id, weight, source, created_at)
                VALUES (?, ?, ?, ?, 1.00, 'author', UTC_TIMESTAMP(3))
                """, entryId, BLOCK, BLOCK + "#0", conceptId);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM question_attempts WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM concept_mastery_state WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM question_difficulty WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM question_concepts WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
        jdbcTemplate.update("DELETE FROM knowledge_concepts WHERE id = ?", conceptId);
    }

    private String token() {
        return "Bearer " + authPort.issue(new AuthPort.Subject(String.valueOf(subject), AuthPort.ROLE_LEARNER)).value();
    }

    private String body(int choice) {
        return """
                {"entryId":%d,"blockKey":"%s","questionKey":"%s#0","choice":%d}
                """.formatted(entryId, BLOCK, BLOCK, choice);
    }

    @Test
    @DisplayName("答对：写入作答、掌握度高于先验；再答错时掌握度下降")
    void correctThenWrongMovesMastery() throws Exception {
        mockMvc.perform(post("/api/v1/learning/attempts")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(true))
                .andExpect(jsonPath("$.data.concepts[0].conceptId").value(conceptId));

        Double afterCorrect = jdbcTemplate.queryForObject("""
                SELECT mastery FROM concept_mastery_state WHERE user_id = ? AND concept_id = ?
                """, Double.class, subject, conceptId);
        assertThat(afterCorrect).isNotNull().isGreaterThan(0.3);

        mockMvc.perform(post("/api/v1/learning/attempts")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(false));

        Double afterWrong = jdbcTemplate.queryForObject("""
                SELECT mastery FROM concept_mastery_state WHERE user_id = ? AND concept_id = ?
                """, Double.class, subject, conceptId);
        assertThat(afterWrong).isLessThan(afterCorrect);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM question_attempts WHERE user_id = ?", Integer.class, subject))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("已答的选择能读回来；题目改过之后旧作答不再算")
    void answeredChoicesAreReadBack() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/learning/entries/{id}/attempts", entryId)
                        .header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(post("/api/v1/learning/attempts")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1)))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/learning/entries/{id}/attempts", entryId)
                        .header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].blockKey").value(BLOCK))
                .andExpect(jsonPath("$.data[0].questionKey").value(BLOCK + "#0"))
                .andExpect(jsonPath("$.data[0].choice").value(1));

        // 改题干 → 指纹变了，旧选择不能再锁住一道新题
        jdbcTemplate.update("UPDATE knowledge_entries SET body = ? WHERE id = ?",
                QUIZ_BODY.replace("两数相加", "两数相减"), entryId);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/learning/entries/{id}/attempts", entryId)
                        .header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("选项越界返回 400，且不写作答")
    void outOfRangeChoiceIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/learning/attempts")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9)))
                .andExpect(status().isBadRequest());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM question_attempts WHERE user_id = ?", Integer.class, subject))
                .isZero();
    }

    @Test
    @DisplayName("找不到题目返回 400，不静默记一条假作答")
    void unknownQuestionIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/learning/attempts")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entryId":%d,"blockKey":"nope","questionKey":"nope#0","choice":0}
                                """.formatted(entryId)))
                .andExpect(status().isBadRequest());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM question_attempts WHERE user_id = ?", Integer.class, subject))
                .isZero();
    }

    @Test
    @DisplayName("答错把该条目的复习排期拉回明天（REQ-009）")
    void wrongAnswerSchedulesReview() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO learning_progress
                    (user_id, entry_id, challenges_json, quizzes_json, status)
                VALUES (?, ?, '{}', '{}', 'in_progress')
                """, subject, entryId);

        mockMvc.perform(post("/api/v1/learning/attempts")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1)))
                .andExpect(status().isOk());

        java.sql.Timestamp due = jdbcTemplate.queryForObject("""
                SELECT review_due_at FROM learning_progress WHERE user_id = ? AND entry_id = ?
                """, java.sql.Timestamp.class, subject, entryId);
        assertThat(due).isNotNull();
        assertThat(due.toInstant()).isAfter(java.time.Instant.now().plus(23, java.time.temporal.ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("管理端标注成功路径、练习选题与评估导出（REQ-008/012）")
    void adminTaggingPracticeAndExport() throws Exception {
        String admin = "Bearer " + authPort.issue(
                new AuthPort.Subject("900", AuthPort.ROLE_ADMIN)).value();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/learning/admin/questions").param("entryId", String.valueOf(entryId))
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].questionKey").value(BLOCK + "#0"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/learning/admin/question-concepts/suggestions")
                        .param("entryId", String.valueOf(entryId))
                        .param("blockKey", BLOCK).param("questionKey", BLOCK + "#0")
                        .header("Authorization", admin))
                .andExpect(status().isOk());

        String replace = """
                {"entryId":%d,"blockKey":"%s","questionKey":"%s#0",
                 "concepts":[{"conceptId":%d,"weight":1.0}],"source":"author"}
                """.formatted(entryId, BLOCK, BLOCK, conceptId);
        for (int round = 0; round < 2; round++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/learning/admin/question-concepts")
                            .header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replace))
                    .andExpect(status().isOk());
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM question_concepts
                WHERE entry_id = ? AND block_key = ? AND question_key = ?
                """, Integer.class, entryId, BLOCK, BLOCK + "#0")).isEqualTo(1);

        // 练习选题：关联后能返回这道题
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/learning/practice/next")
                        .param("conceptIds", String.valueOf(conceptId))
                        .header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questionKey").value(BLOCK + "#0"));

        // 先作答一条，导出才有内容
        mockMvc.perform(post("/api/v1/learning/attempts")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0)))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/learning/admin/evaluation/export")
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].entryId").value(entryId));
    }
}
