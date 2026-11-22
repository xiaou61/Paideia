package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.knowledge.internal.question.QuestionIndexService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 规则组卷的集成测试（WORK-083 TASK-002）。
 *
 * <p>钉住：组卷响应不含答案、seq 连续、题数等于候选；同规则两次逐题相同；
 * 候选不足如实返回 shortfall；取卷/删卷按归属；他人 paper id 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class PaperIntegrationTest {

    private static final String BLOCK = "it-paper";

    private static final String QUIZ_BODY = """
            ```quiz
            {"id":"it-paper","questions":[
              {"question":"Q one?","options":["a","b"],"answer":0},
              {"question":"Q two?","options":["a","b"],"answer":1},
              {"question":"Q three?","options":["a","b"],"answer":0}
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
    private final long subject = 444_444L;

    @BeforeEach
    void setUp() {
        String title = "试卷IT-" + System.nanoTime();
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
        jdbcTemplate.update("DELETE FROM paper_answers WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM paper_items WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM papers WHERE user_id = ?", subject);
        jdbcTemplate.update("DELETE FROM question_index WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM question_difficulty WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
    }

    private String allCorrectAnswersJson(long paperId) {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, answer_index_snapshot FROM paper_items WHERE paper_id = ? ORDER BY seq", paperId);
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < rows.size(); i++) {
            long itemId = ((Number) rows.get(i).get("id")).longValue();
            int answer = ((Number) rows.get(i).get("answer_index_snapshot")).intValue();
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(itemId).append("\":").append(answer);
        }
        return json.append('}').toString();
    }

    private String token() {
        return TestTokens.bearer(authPort, String.valueOf(subject));
    }

    private String createBody(int count, int medium) {
        return "{\"title\":\"t\",\"count\":" + count
                + ",\"difficultyDistribution\":{\"medium\":" + medium + "}}";
    }

    private long latestPaperId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM papers WHERE user_id = ? ORDER BY id DESC LIMIT 1", Long.class, subject);
    }

    @Test
    @DisplayName("组卷：卷面不含答案，seq 连续，题数等于候选")
    void createDoesNotLeakAnswers() throws Exception {
        String content = mockMvc.perform(post("/api/v1/learning/papers")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2, 2)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).doesNotContain("answerIndex");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_items WHERE user_id = ?", Integer.class, subject)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM paper_items WHERE user_id = ?", Integer.class, subject)).isEqualTo(2);
    }

    @Test
    @DisplayName("候选不足如实返回 shortfall")
    void shortfallReported() throws Exception {
        String content = mockMvc.perform(post("/api/v1/learning/papers")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(5, 5)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains("\"shortfall\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_items WHERE user_id = ?", Integer.class, subject)).isEqualTo(3);
    }

    @Test
    @DisplayName("同规则两次组卷逐题一致")
    void sameRuleTwiceIdentical() throws Exception {
        for (int round = 0; round < 2; round++) {
            mockMvc.perform(post("/api/v1/learning/papers")
                            .header("Authorization", token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(2, 2)))
                    .andExpect(status().isOk());
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM papers WHERE user_id = ? ORDER BY id DESC LIMIT 2", Long.class, subject);
        List<String> newer = jdbcTemplate.queryForList(
                "SELECT fingerprint FROM paper_items WHERE paper_id = ? ORDER BY seq", String.class, ids.get(0));
        List<String> older = jdbcTemplate.queryForList(
                "SELECT fingerprint FROM paper_items WHERE paper_id = ? ORDER BY seq", String.class, ids.get(1));

        assertThat(newer).isEqualTo(older);
    }

    @Test
    @DisplayName("取卷无答案；删卷后取卷 404")
    void getAndDelete() throws Exception {
        mockMvc.perform(post("/api/v1/learning/papers")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2, 2)))
                .andExpect(status().isOk());
        long paperId = latestPaperId();

        String content = mockMvc.perform(get("/api/v1/learning/papers/{id}", paperId)
                        .header("Authorization", token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(content).doesNotContain("answerIndex");

        mockMvc.perform(delete("/api/v1/learning/papers/{id}", paperId)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/learning/papers/{id}", paperId)
                        .header("Authorization", token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("他人 paper id 返回 404")
    void foreignPaperIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/learning/papers")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2, 2)))
                .andExpect(status().isOk());
        long paperId = latestPaperId();
        String other = TestTokens.bearer(authPort, "555555");

        mockMvc.perform(get("/api/v1/learning/papers/{id}", paperId)
                        .header("Authorization", other))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("重复交卷返回 409，成绩不被覆盖")
    void doubleSubmitConflict() throws Exception {
        mockMvc.perform(post("/api/v1/learning/papers")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2, 2)))
                .andExpect(status().isOk());
        long paperId = latestPaperId();
        String answers = allCorrectAnswersJson(paperId);

        mockMvc.perform(post("/api/v1/learning/papers/{id}/submit", paperId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answers))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/learning/papers/{id}/submit", paperId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answers))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_answers WHERE paper_id = ?", Integer.class, paperId)).isEqualTo(2);
    }

    @Test
    @DisplayName("正文事后被改，仍按快照判分")
    void gradeUsesSnapshotAfterBodyEdit() throws Exception {
        mockMvc.perform(post("/api/v1/learning/papers")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2, 2)))
                .andExpect(status().isOk());
        long paperId = latestPaperId();
        String answers = allCorrectAnswersJson(paperId);

        jdbcTemplate.update("UPDATE knowledge_entries SET body = ? WHERE id = ?", "<p>gone</p>", entryId);

        String content = mockMvc.perform(post("/api/v1/learning/papers/{id}/submit", paperId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answers))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains("\"correct\":2").contains("\"total\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM question_attempts WHERE user_id = ? AND entry_id = ?",
                Integer.class, subject, entryId)).isEqualTo(2);

        String result = mockMvc.perform(get("/api/v1/learning/papers/{id}/result", paperId)
                        .header("Authorization", token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(result).contains("\"correct\":2");
    }

    @Test
    @DisplayName("漏答：只落已答题，漏答既不写空选项也不记作答")
    void partialAnswersAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/learning/papers")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(3, 3)))
                .andExpect(status().isOk());
        long paperId = latestPaperId();
        List<java.util.Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, answer_index_snapshot FROM paper_items WHERE paper_id = ? ORDER BY seq", paperId);
        long firstItem = ((Number) items.get(0).get("id")).longValue();
        int firstAnswer = ((Number) items.get(0).get("answer_index_snapshot")).intValue();

        String content = mockMvc.perform(post("/api/v1/learning/papers/{id}/submit", paperId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + firstItem + "\":" + firstAnswer + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains("\"correct\":1").contains("\"total\":3");
        // paper_answers.choice_index 是 NOT NULL：漏答题不能落一行 null，否则整卷交卷回滚成 500
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_answers WHERE paper_id = ?", Integer.class, paperId)).isEqualTo(1);
        // 漏答也不记作答，否则错题本与复习排期会把"没答"算成"答错"
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM question_attempts WHERE user_id = ? AND entry_id = ?",
                Integer.class, subject, entryId)).isEqualTo(1);
    }

    @Test
    @DisplayName("作答项不属于本卷、选项越界一律 400")
    void invalidAnswersRejected() throws Exception {
        mockMvc.perform(post("/api/v1/learning/papers")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2, 2)))
                .andExpect(status().isOk());
        long paperId = latestPaperId();
        long itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM paper_items WHERE paper_id = ? ORDER BY seq LIMIT 1", Long.class, paperId);

        mockMvc.perform(post("/api/v1/learning/papers/{id}/submit", paperId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"999999999\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/learning/papers/{id}/submit", paperId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + itemId + "\":5}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_answers WHERE paper_id = ?", Integer.class, paperId)).isZero();
    }
}
