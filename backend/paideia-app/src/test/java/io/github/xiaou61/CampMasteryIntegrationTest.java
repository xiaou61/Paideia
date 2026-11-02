package io.github.xiaou61;


import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.security.AuthPort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 训练营成绩进入掌握度（WORK-029 AC-013）。
 *
 * <p>三件事都要证到，缺一条这条验收就只是"代码看起来对"：
 * <ol>
 *   <li>同一概念，诊断全对的图谱分数 **高于** 全错；</li>
 *   <li>没有训练营记录时，图谱分数与**改动前逐位相同**——这是"第四路证据不改变既有行为"
 *       唯一可测的形式（拿同一个人在无营状态下的两次计算对比）；</li>
 *   <li>连续两次算出来逐位相同（纯函数纪律，沿用 WORK-021）。</li>
 * </ol>
 *
 * <p>直接把行插进库而不是走 HTTP 开营：本类验的是**图谱聚合**，开营那一段已经由
 * `CampIntegrationTest` 逐条覆盖；再走一遍 HTTP 只是把两件事绑在一起，失败时更难定位。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampMasteryIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuthPort authPort;

    private long userId;
    private String token;
    private Long conceptId;
    private Long entryId;
    private Long categoryId;
    private long programId;
    private long itemId;

    private static final String CONCEPT = "camp-mastery-concept";
    private static final String ENTRY_TITLE = "camp-mastery-entry";

    @BeforeEach
    void setUp() {
        String username = "mastery-" + java.util.UUID.randomUUID().toString().substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO users (username, email, password_hash, role, email_verified_at)
                VALUES (?, ?, '$2a$10$abcdefghijklmnopqrstuv', 'learner', CURRENT_TIMESTAMP(3))
                """, username, username + "@example.test");
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        token = TestTokens.bearer(authPort, Long.toString(userId));

        categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_categories ORDER BY id LIMIT 1", Long.class);
        // 概念与分类都可能被别的用例删过，缺了就补
        if (categoryId == null) {
            jdbcTemplate.update("""
                    INSERT INTO knowledge_categories (name, slug, sort_order)
                    VALUES ('camp-mastery', 'camp-mastery', 91)
                    """);
            categoryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM knowledge_categories WHERE slug = 'camp-mastery'", Long.class);
        }

        jdbcTemplate.update("""
                INSERT INTO knowledge_concepts (name, summary, category_id, created_at, updated_at)
                VALUES (?, '', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE summary = ''
                """, CONCEPT, categoryId);
        conceptId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_concepts WHERE name = ?", Long.class, CONCEPT);

        jdbcTemplate.update("""
                INSERT INTO knowledge_entries (category_id, sort_order, title, body, status, published_at)
                VALUES (?, NULL, ?, '掌握度联动用的载体条目正文。', 'published', UTC_TIMESTAMP(3))
                """, categoryId, ENTRY_TITLE);
        entryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE title = ?", Long.class, ENTRY_TITLE);
        jdbcTemplate.update("""
                INSERT INTO knowledge_entry_concepts (entry_id, concept_id, created_at)
                VALUES (?, ?, UTC_TIMESTAMP(3))
                """, entryId, conceptId);

        // 一个已经交过诊断卷的营；题目挂在这个概念上。
        // resume_document_id 必须指向**真实存在**的简历：出计划时要用它算覆盖度
        jdbcTemplate.update("""
                INSERT INTO resume_documents (user_id, title, is_default, revision, data_json, plain_text,
                                              created_at, updated_at)
                VALUES (?, '掌握度用', 1, 1,
                        '{"basics":{"name":"","phone":"","email":"","city":"","links":[]},
                          "intent":{"role":"","city":"","availability":""},
                          "education":[],"work":[],"projects":[],
                          "skills":[{"group":"技术栈","items":["并发"]}],
                          "awards":[],"selfEvaluation":""}',
                        '并发', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, userId);
        long resumeId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM resume_documents WHERE user_id = ?", Long.class, userId);
        jdbcTemplate.update("""
                INSERT INTO camp_programs (user_id, resume_document_id, resume_title, days, item_count,
                                           dropped_count, diagnostic_submitted_at, created_at, updated_at)
                VALUES (?, ?, '掌握度用', 3, 3, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, userId, resumeId);
        programId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM camp_programs WHERE user_id = ?", Long.class, userId);
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("camp_tasks", "camp_plans", "camp_attempts", "camp_items",
                "camp_programs", "resume_documents", "learning_progress", "knowledge_entry_views",
                "knowledge_self_assessments", "ai_credit_ledger", "ai_credit_accounts")) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
        }
        jdbcTemplate.update("DELETE FROM knowledge_entry_concepts WHERE entry_id = ?", entryId);
        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
        jdbcTemplate.update("DELETE FROM knowledge_concepts WHERE id = ?", conceptId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    /** 造一套诊断作答：`correct` 决定对几道（共 3 道）。 */
    private void seedCampAttempts(int correct) {
        jdbcTemplate.update("DELETE FROM camp_attempts WHERE program_id = ?", programId);
        jdbcTemplate.update("DELETE FROM camp_items WHERE program_id = ?", programId);
        for (int index = 1; index <= 3; index++) {
            jdbcTemplate.update("""
                    INSERT INTO camp_items (program_id, user_id, form, seq, topic, concept_id, difficulty,
                                            stem, options_json, answer_index, explanation, fingerprint,
                                            created_at)
                    VALUES (?, ?, 'diagnostic', ?, ?, ?, 'medium',
                            ?, '["A项","B项","C项","D项"]', 0, '解析内容足够长。', ?, UTC_TIMESTAMP(3))
                    """, programId, userId, index, CONCEPT, conceptId,
                    "掌握度联动第 " + index + " 题：这个技术点在什么场景下会出问题？",
                    String.format("%064d", index));
            itemId = jdbcTemplate.queryForObject(
                    "SELECT MAX(id) FROM camp_items WHERE program_id = ?", Long.class, programId);
            jdbcTemplate.update("""
                    INSERT INTO camp_attempts (program_id, user_id, item_id, choice, correct, created_at)
                    VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(3))
                    """, programId, userId, itemId, index <= correct ? 0 : 1, index <= correct ? 1 : 0);
        }
    }

    /** 取图谱里这个概念的分数。没有概念节点时返回 null。 */
    private Double conceptScore() throws Exception {
        MvcResult result = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/learning/graph").header("Authorization", token)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        List<Map<String, Object>> concepts = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.concepts");
        for (Map<String, Object> node : concepts) {
            if (CONCEPT.equals(node.get("name"))) {
                return ((Number) node.get("score")).doubleValue();
            }
        }
        return null;
    }

    @Test
    @DisplayName("没有训练营记录时分数与无营状态一致，且连续两次逐位相同")
    void graphUnchangedWithoutCampData() throws Exception {
        Double noCamp = conceptScore();
        assertThat(conceptScore()).isEqualTo(noCamp);

        seedCampAttempts(3);
        Double withCamp = conceptScore();
        assertThat(withCamp).as("有训练营证据后必须变高（全对）").isGreaterThan(noCamp);
    }

    @Test
    @DisplayName("同一概念：诊断全对 > 全错")
    void allCorrectBeatsAllWrong() throws Exception {
        seedCampAttempts(3);
        Double allRight = conceptScore();

        seedCampAttempts(0);
        Double allWrong = conceptScore();

        assertThat(allRight).as("全对 %.4f vs 全错 %.4f", allRight, allWrong).isGreaterThan(allWrong);
    }

    /**
     * TASK-018：计划里的「做」类任务与既有学习记录链路衔接。
     *
     * <p>本项**不新增上报路径**：`do` 任务的 `entryId` 指向一个带 quiz 围栏块的已发布条目，
     * 用户在条目页完成 quiz 后由既有 `ProgressPort` 写 `learning_progress`。这里把两段真串一遍
     * ——出计划拿到 `kind=do` 的 entryId，再调条目页用的那个端点写入，断言行真的出现。
     * 不这么做，"计划能跳过去"就只是一句设计承诺。
     */
    @Test
    @DisplayName("TASK-018 计划里的 do 任务指向带 quiz 的条目，走既有上报端点后 learning_progress 有记录")
    void planTaskLinksToExistingProgressPath() throws Exception {
        // 载体正文里带一个 quiz 围栏块：计划据此把 read 升级成 do
        jdbcTemplate.update("""
                UPDATE knowledge_entries SET body = ? WHERE id = ?
                """, """
                掌握度联动用的载体条目正文。

                ```quiz
                {"id":"q1","question":"这道题考的是什么？","options":["并发","顺序","网络","磁盘"],"answer":0}
                ```
                """, entryId);
        seedCampAttempts(0);

        // 出计划：全部答错 → 该主题弱 → 计划里必然有这一条
        MvcResult plan = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/ai/camps/" + programId + "/plan")
                                .header("Authorization", token)).andReturn();
        assertThat(plan.getResponse().getStatus())
                .as(plan.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);

        List<Map<String, Object>> tasks = JsonPath.read(
                plan.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.dayPlans[*].tasks[*]");
        Map<String, Object> doTask = tasks.stream()
                .filter(task -> "do".equals(task.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("计划里没有 do 类型任务：" + tasks));
        assertThat(((Number) doTask.get("entryId")).longValue()).isEqualTo(entryId);

        // 再走条目页用的那个上报端点（不是本项新增的路径）
        MvcResult reported = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/v1/learning/entries/" + entryId)
                                .header("Authorization", token)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"quizzes\":{\"q1\":{\"score\":1,\"total\":1}}}"))
                .andReturn();
        assertThat(reported.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM learning_progress WHERE user_id = ? AND entry_id = ?",
                Integer.class, userId, entryId)).isEqualTo(1);
    }

    @Test
    @DisplayName("连续两次 GET /graph 结果逐位相同（沿用 WORK-021 纯函数纪律）")
    void graphIsDeterministic() throws Exception {
        seedCampAttempts(2);
        assertThat(conceptScore()).isEqualTo(conceptScore());
    }
}
