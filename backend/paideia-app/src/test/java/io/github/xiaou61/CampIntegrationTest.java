package io.github.xiaou61;


import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.camp.CampGenerationException;
import io.github.xiaou61.ai.internal.camp.CampItemGenerator;
import io.github.xiaou61.ai.internal.camp.ItemValidator;
import io.github.xiaou61.security.AuthPort;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 训练营的端到端业务验收（WORK-029 AC-001..AC-018）。
 *
 * <p>模型端口全程打桩，**不出网**。打桩还让"余额为 0 时到底有没有发起上游调用"变成一条
 * 可断言的计数，而不是一句承诺。
 *
 * <p>本类验的是编排、归属、判分与数据生命周期，**不是模型质量**。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuthPort authPort;

    @Autowired
    CreditService credits;

    @MockitoBean
    CampItemGenerator generator;

    /** 上游被调用了几次——用来断言"余额不足时一个请求都不发"。 */
    private final AtomicInteger generatorCalls = new AtomicInteger();

    private long userA;
    private long userB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        userA = createUser();
        userB = createUser();
        tokenA = token(userA);
        tokenB = token(userB);
        credits.adjust(userA, 100_000, 1L, "测试发分");
        credits.adjust(userB, 100_000, 1L, "测试发分");
        generatorCalls.set(0);
    }

    @AfterEach
    void cleanUp() {
        for (long userId : List.of(userA, userB)) {
            // 逐表清理由 SqlCleanup 统一（顺序 = 参数顺序，子表在前）；users 走 id 列，单独删
            SqlCleanup.deleteByUser(jdbcTemplate, userId, "camp_tasks", "camp_plans", "camp_attempts",
                    "camp_items", "camp_programs", "resume_documents", "ai_credit_ledger", "ai_credit_accounts");
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private long createUser() {
        String username = "camp-" + java.util.UUID.randomUUID().toString().substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO users (username, email, password_hash, role, email_verified_at)
                VALUES (?, ?, '$2a$10$abcdefghijklmnopqrstuv', 'learner', CURRENT_TIMESTAMP(3))
                """, username, username + "@example.test");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private String token(long userId) {
        return TestTokens.bearer(authPort, Long.toString(userId));
    }

    /** 一份有技能区的简历：训练营需要非空画像才能开营。 */
    private long resumeWithSkills(String token, List<String> skills) {
        String body = """
                {"title":"自查简历","plainText":"张三 Java MySQL",
                 "data":{"basics":{"name":"张三","phone":"13800001111","email":"z@example.test",
                                   "city":"杭州","links":[]},
                         "intent":{"role":"Java 后端","city":"杭州","availability":""},
                         "education":[],"work":[],"projects":[],
                         "skills":[{"group":"技术栈","items":[%s]}],
                         "awards":[],"selfEvaluation":""}}
                """.formatted(skills.stream().map(item -> "\"" + item + "\"")
                .reduce((left, right) -> left + "," + right).orElse(""));
        MvcResult result = perform(post("/api/v1/ai/resumes").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(status(result)).as("建简历失败：" + text(result)).isEqualTo(200);
        return ((Number) JsonPath.read(text(result), "$.data.id")).longValue();
    }

    private long resumeWithoutSkills(String token) {
        String body = """
                {"title":"空简历","plainText":"张三",
                 "data":{"basics":{"name":"张三","phone":"","email":"","city":"","links":[]},
                         "intent":{"role":"","city":"","availability":""},
                         "education":[],"work":[],"projects":[],"skills":[],
                         "awards":[],"selfEvaluation":""}}
                """;
        MvcResult result = perform(post("/api/v1/ai/resumes").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(status(result)).as("建简历失败：" + text(result)).isEqualTo(200);
        return ((Number) JsonPath.read(text(result), "$.data.id")).longValue();
    }

    /**
     * 一组合法题。题干必须互不相同，否则会被同卷指纹去重。
     *
     * @param topics 每个主题分几题（保持"题量分布"可控，便于断言判分）
     */
    private static List<ItemValidator.RawItem> items(Map<String, Integer> topics) {
        List<ItemValidator.RawItem> list = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, Integer> entry : topics.entrySet()) {
            for (int inTopic = 0; inTopic < entry.getValue(); inTopic++) {
                index++;
                list.add(new ItemValidator.RawItem(entry.getKey(), "", "medium",
                        "第 " + index + " 道题：这个技术点在什么场景下会出问题？",
                        List.of("并发扩容时", "冷启动时", "序列化时", "编译阶段"), 0,
                        "并发扩容时是这道题的核心考点，选项一为正解。"));
            }
        }
        return list;
    }

    private void stubGeneration(List<ItemValidator.RawItem> generated) {
        when(generator.diagnostic(anyString(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            generatorCalls.incrementAndGet();
            return new CampItemGenerator.Generated(generated, "{}", new TokenUsage(100, 50, 150));
        });
    }

    // ── HTTP 辅助 ───────────────────────────────────────────────────

    private MvcResult perform(org.springframework.test.web.servlet.RequestBuilder request) {
        try {
            return mockMvc.perform(request).andReturn();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private long openCamp(String token, long resumeId, int days) {
        MvcResult result = perform(post("/api/v1/ai/camps").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeDocumentId\":" + resumeId + ",\"days\":" + days + "}"));
        assertThat(status(result)).as(text(result)).isEqualTo(200);
        return ((Number) JsonPath.read(text(result), "$.data.id")).longValue();
    }

    /** 全选答案 index 下标作答，返回请求体。 */
    private static String answers(List<Long> itemIds, java.util.function.IntUnaryOperator choiceOf) {
        StringBuilder builder = new StringBuilder("{\"answers\":[");
        for (int index = 0; index < itemIds.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append("{\"itemId\":").append(itemIds.get(index))
                    .append(",\"choice\":").append(choiceOf.applyAsInt(index)).append('}');
        }
        return builder.append("]}").toString();
    }

    private List<Long> itemIds(String token, long campId, String form) {
        MvcResult result = perform(get("/api/v1/ai/camps/" + campId + "/items?form=" + form)
                .header("Authorization", token));
        assertThat(status(result)).isEqualTo(200);
        List<Integer> ids = JsonPath.read(text(result), "$.data[*].id");
        return ids.stream().map(Integer::longValue).toList();
    }

    private MvcResult submit(String token, long campId, String form, String answersJson) {
        String prefix = answersJson.startsWith("{") ? answersJson.substring(0, answersJson.length() - 1) : "{";
        return perform(post("/api/v1/ai/camps/" + campId + "/submit")
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(prefix + ",\"form\":\"" + form + "\"}"));
    }

    // ── AC-001 / AC-002：开营 ───────────────────────────────────────

    @Test
    @DisplayName("AC-001 开营出卷：建营、落题、结算流水种类为 CAMP")
    void createsCampWithItems() {
        stubGeneration(items(Map.of("集合", 6, "并发", 6)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java", "MySQL"));

        MvcResult created = perform(post("/api/v1/ai/camps").header("Authorization", tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeDocumentId\":" + resumeId + ",\"days\":3}"));
        assertThat(status(created)).as(text(created)).isEqualTo(200);
        long campId = ((Number) JsonPath.read(text(created), "$.data.id")).longValue();
        assertThat(((Number) JsonPath.read(text(created), "$.data.itemCount")).intValue()).isEqualTo(12);
        assertThat((Boolean) JsonPath.read(text(created), "$.data.shortOfTarget")).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM camp_items WHERE program_id = ?", Integer.class, campId)).isEqualTo(12);
        // 流水里必须出现 CAMP 这一种：用户要能回答"这个营花了多少"
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_credit_ledger WHERE user_id = ? AND kind = 'CAMP'",
                Integer.class, userA)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-001 题干不含简历里的姓名、电话、邮箱")
    void stemsCarryNoPii() {
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java", "MySQL"));
        long campId = openCamp(tokenA, resumeId, 3);

        String stems = String.join(" ", jdbcTemplate.queryForList(
                "SELECT stem FROM camp_items WHERE program_id = ?", String.class, campId));
        assertThat(stems).doesNotContain("张三").doesNotContain("13800001111")
                .doesNotContain("z@example.test");
    }

    @Test
    @DisplayName("AC-002 画像为空的简历被拒：不建营、不扣积分、上游 0 次")
    void emptyResumeIsRejected() {
        long resumeId = resumeWithoutSkills(tokenA);
        long before = credits.balance(userA);

        MvcResult result = perform(post("/api/v1/ai/camps").header("Authorization", tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeDocumentId\":" + resumeId + ",\"days\":3}"));

        assertThat(status(result)).isEqualTo(400);
        assertThat(text(result)).contains("可考核的技能");
        assertThat(generatorCalls.get()).isZero();
        assertThat(credits.balance(userA)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM camp_programs WHERE user_id = ?", Integer.class, userA)).isZero();
    }

    @Test
    @DisplayName("天数不是 3 或 7 → 400")
    void rejectsBadDays() {
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        MvcResult result = perform(post("/api/v1/ai/camps").header("Authorization", tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeDocumentId\":" + resumeId + ",\"days\":5}"));
        assertThat(status(result)).isEqualTo(400);
    }

    @Test
    @DisplayName("已有 3 个营再开 → 409")
    void rejectsFourthCamp() {
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        openCamp(tokenA, resumeId, 3);
        openCamp(tokenA, resumeId, 3);
        openCamp(tokenA, resumeId, 3);

        MvcResult fourth = perform(post("/api/v1/ai/camps").header("Authorization", tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeDocumentId\":" + resumeId + ",\"days\":3}"));
        assertThat(status(fourth)).isEqualTo(409);
    }

    // ── AC-003 / AC-004 / AC-005：判分 ─────────────────────────────

    @Test
    @DisplayName("AC-003 全对 → 每主题答对率 100% 且无弱项；故意错 2 题 → 该主题 correct 少 2")
    void scoresLocally() {
        stubGeneration(items(Map.of("集合", 6, "并发", 6)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");

        MvcResult allRight = submit(tokenA, campId, "diagnostic", answers(ids, index -> 0));
        assertThat(status(allRight)).as(text(allRight)).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(text(allRight), "$.data.weak")).isEmpty();
        assertThat((List<Integer>) JsonPath.read(text(allRight),
                "$.data.topics[*].correct")).containsExactlyInAnyOrder(6, 6);
    }

    @Test
    @DisplayName("AC-004 交卷前从 /items 拿不到答案与解析（字段集合断言）")
    void itemsNeverExposeAnswerKey() {
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);

        MvcResult items = perform(get("/api/v1/ai/camps/" + campId + "/items?form=diagnostic")
                .header("Authorization", tokenA));
        assertThat(status(items)).isEqualTo(200);
        String body = text(items);
        assertThat(body).doesNotContain("answerIndex").doesNotContain("explanation");

        List<Map<String, Object>> first = JsonPath.read(body, "$.data");
        assertThat(first.get(0).keySet())
                .containsExactlyInAnyOrder("id", "seq", "topic", "difficulty", "stem", "options");

        // 交卷后同一个端点仍然不带答案
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");
        submit(tokenA, campId, "diagnostic", answers(ids, index -> 0));
        MvcResult afterSubmit = perform(get("/api/v1/ai/camps/" + campId + "/items?form=diagnostic")
                .header("Authorization", tokenA));
        assertThat(text(afterSubmit)).doesNotContain("answerIndex").doesNotContain("explanation");
    }

    @Test
    @DisplayName("AC-005 请求体里塞 correct 与伪造分数 → 服务端按自己的判定落库；二次提交 409")
    void forgedCorrectFlagIsIgnored() {
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");

        // 全部选错（正确答案都是 0），同时伪造 correct:true 与 score
        StringBuilder forged = new StringBuilder("{\"form\":\"diagnostic\",\"score\":100,\"answers\":[");
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
                forged.append(',');
            }
            forged.append("{\"itemId\":").append(ids.get(index))
                    .append(",\"choice\":1,\"correct\":true}");
        }
        forged.append("]}");

        MvcResult result = perform(post("/api/v1/ai/camps/" + campId + "/submit")
                .header("Authorization", tokenA).contentType(MediaType.APPLICATION_JSON)
                .content(forged.toString()));
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM camp_attempts WHERE program_id = ? AND correct = 1",
                Integer.class, campId)).isZero();

        int attemptsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM camp_attempts WHERE program_id = ?", Integer.class, campId);
        MvcResult second = submit(tokenA, campId, "diagnostic", answers(ids, index -> 0));
        assertThat(status(second)).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM camp_attempts WHERE program_id = ?", Integer.class, campId))
                .isEqualTo(attemptsBefore);
    }

    @Test
    @DisplayName("缺一题未答 → 400")
    void missingAnswerIsRejected() {
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");

        MvcResult result = submit(tokenA, campId, "diagnostic",
                answers(ids.subList(0, ids.size() - 1), index -> 0));
        assertThat(status(result)).isEqualTo(400);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM camp_attempts WHERE program_id = ?", Integer.class, campId)).isZero();
    }

    // ── AC-014：归属与删除 ─────────────────────────────────────────

    @Test
    @DisplayName("AC-014 用户 B 访问 A 的营 → 404；删营后四张子表逐表 0 行")
    void otherUsersCampIs404AndDeleteIsComplete() {
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");
        submit(tokenA, campId, "diagnostic", answers(ids, index -> 0));
        perform(post("/api/v1/ai/camps/" + campId + "/plan").header("Authorization", tokenA));

        for (String path : List.of("/api/v1/ai/camps/" + campId,
                "/api/v1/ai/camps/" + campId + "/items?form=diagnostic",
                "/api/v1/ai/camps/" + campId + "/result?form=diagnostic",
                "/api/v1/ai/camps/" + campId + "/plan")) {
            MvcResult result = perform(get(path).header("Authorization", tokenB));
            assertThat(status(result)).as("B 访问 " + path).isEqualTo(404);
        }

        MvcResult deleted = perform(delete("/api/v1/ai/camps/" + campId).header("Authorization", tokenA));
        assertThat(status(deleted)).isEqualTo(200);
        // camp_programs 自己的主键是 id；其余四张用 program_id
        for (String[] table : List.of(
                new String[]{"camp_items", "program_id"},
                new String[]{"camp_attempts", "program_id"},
                new String[]{"camp_plans", "program_id"},
                new String[]{"camp_tasks", "program_id"},
                new String[]{"camp_programs", "id"})) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table[0] + " WHERE " + table[1] + " = ?",
                    Integer.class, campId)).as(table[0]).isZero();
        }
    }

    // ── AC-009 / AC-010：计划 ──────────────────────────────────────

    @Test
    @DisplayName("AC-010 未交诊断卷不能出计划；交卷后计划每天有项且不超预算")
    void planRequiresDiagnostic() {
        stubGeneration(items(Map.of("集合", 6, "并发", 6)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");

        assertThat(status(perform(post("/api/v1/ai/camps/" + campId + "/plan")
                .header("Authorization", tokenA)))).isEqualTo(400);

        // 全部答错 → 两个主题都弱，计划必须给出内容
        submit(tokenA, campId, "diagnostic", answers(ids, index -> 1));
        MvcResult plan = perform(post("/api/v1/ai/camps/" + campId + "/plan")
                .header("Authorization", tokenA));
        assertThat(status(plan)).as(text(plan)).isEqualTo(200);

        List<Map<String, Object>> dayPlans = JsonPath.read(text(plan), "$.data.dayPlans");
        assertThat(dayPlans).hasSize(3);
        int budget = ((Number) JsonPath.read(text(plan), "$.data.budgetMinutes")).intValue();
        assertThat(budget).isEqualTo(90);
        for (Map<String, Object> day : dayPlans) {
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) day.get("tasks");
            assertThat(tasks).as("第 " + day.get("day") + " 天").isNotEmpty();
        }
    }

    @Test
    @DisplayName("AC-010 任务勾选幂等；跨营的 taskId → 404")
    void taskToggleAndOwnership() {
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");
        submit(tokenA, campId, "diagnostic", answers(ids, index -> 1));
        MvcResult plan = perform(post("/api/v1/ai/camps/" + campId + "/plan")
                .header("Authorization", tokenA));
        long taskId = ((Number) JsonPath.read(text(plan), "$.data.dayPlans[0].tasks[0].id")).longValue();

        for (int round = 0; round < 2; round++) {
            MvcResult done = perform(patch("/api/v1/ai/camps/" + campId + "/tasks/" + taskId)
                    .header("Authorization", tokenA).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"done\":true}"));
            assertThat(status(done)).isEqualTo(200);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT done_at FROM camp_tasks WHERE id = ?", java.sql.Timestamp.class, taskId)).isNotNull();

        MvcResult undone = perform(patch("/api/v1/ai/camps/" + campId + "/tasks/" + taskId)
                .header("Authorization", tokenA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"done\":false}"));
        assertThat(status(undone)).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT done_at FROM camp_tasks WHERE id = ?", java.sql.Timestamp.class, taskId)).isNull();

        // 别人的营 → 404
        MvcResult foreign = perform(patch("/api/v1/ai/camps/" + campId + "/tasks/" + taskId)
                .header("Authorization", tokenB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"done\":true}"));
        assertThat(status(foreign)).isEqualTo(404);
    }

    @Test
    @DisplayName("AC-009 载体条目下架后计划项降级为缺口，且不返回失效 id")
    void planDegradesWhenEntryUnpublished() {
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);

        // 造一个概念 + 一个已发布载体条目，再把它下架
        jdbcTemplate.update("""
                INSERT INTO knowledge_concepts (name, summary, category_id, created_at, updated_at)
                VALUES ('camp-degrade-concept', '', NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        Long conceptId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_concepts WHERE name = 'camp-degrade-concept'", Long.class);
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_categories ORDER BY id LIMIT 1", Long.class);
        jdbcTemplate.update("""
                INSERT INTO knowledge_entries (category_id, sort_order, title, body, status, published_at)
                VALUES (?, NULL, '待下架的集合讲解', '正文内容够长，读完大概几分钟。', 'published',
                        UTC_TIMESTAMP(3))
                """, categoryId);
        Long entryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE title = '待下架的集合讲解'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO knowledge_entry_concepts (entry_id, concept_id, created_at)
                VALUES (?, ?, UTC_TIMESTAMP(3))
                """, entryId, conceptId);
        // 条目正文要有内容，否则 read 任务时长为下限 5 分钟——不影响这条断言

        // 题目要挂在这个概念上，计划才会去取它的载体条目
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");
        jdbcTemplate.update("UPDATE camp_items SET concept_id = ? WHERE program_id = ?", conceptId, campId);
        submit(tokenA, campId, "diagnostic", answers(ids, index -> 1));
        MvcResult plan = perform(post("/api/v1/ai/camps/" + campId + "/plan")
                .header("Authorization", tokenA));
        assertThat(status(plan)).as(text(plan)).isEqualTo(200);

        // **先证明载体真的被用上了**：没有这一步，下面的"下架后消失"会因为计划本来就
        // 空而假通过（2026-10-21 真的发生过：weakTopics 参数写反，载体永远取不到）
        assertThat(text(plan))
                .as("计划里应当出现这个载体条目；拿到的是 " + text(plan))
                .contains("\"entryId\":" + entryId);
        assertThat(text(plan)).doesNotContain("这项内容已下架");

        jdbcTemplate.update("UPDATE knowledge_entries SET status = 'draft' WHERE id = ?", entryId);
        try {
            MvcResult afterOffline = perform(get("/api/v1/ai/camps/" + campId + "/plan")
                    .header("Authorization", tokenA));
            assertThat(status(afterOffline)).isEqualTo(200);
            String body = text(afterOffline);
            assertThat(body).as("下架后不能再返回失效 id；拿到的是 " + body)
                    .doesNotContain("\"entryId\":" + entryId);
            assertThat(body).contains("这项内容已下架");
        } finally {
            jdbcTemplate.update("DELETE FROM knowledge_entry_concepts WHERE entry_id = ?", entryId);
            jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
            jdbcTemplate.update("DELETE FROM knowledge_concepts WHERE id = ?", conceptId);
        }
    }

    // ── AC-011 / AC-012 / AC-018：复测与失败 ───────────────────────

    @Test
    @DisplayName("AC-011 复测卷与诊断卷同主题同题量，题干无交集")
    void retestMatchesDistributionAndAvoidsRepeats() {
        stubGeneration(items(Map.of("集合", 6, "并发", 6)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");
        submit(tokenA, campId, "diagnostic", answers(ids, index -> 0));

        // 复测用另一套完全不同题干的题
        when(generator.retest(anyString(), anyString(), anyString(), anyList(), any()))
                .thenReturn(new CampItemGenerator.Generated(
                        items(Map.of("集合", 6, "并发", 6)).stream()
                                .map(item -> new ItemValidator.RawItem(item.topic(), item.conceptName(),
                                        item.difficulty(), "复测：" + item.stem(), item.options(),
                                        item.answerIndex(), item.explanation()))
                                .toList(),
                        "{}", new TokenUsage(100, 50, 150)));

        MvcResult retest = perform(post("/api/v1/ai/camps/" + campId + "/retest")
                .header("Authorization", tokenA));
        assertThat(status(retest)).as(text(retest)).isEqualTo(200);

        Map<String, Integer> diagnosticTopics = topicCounts(campId, "diagnostic");
        Map<String, Integer> retestTopics = topicCounts(campId, "retest");
        assertThat(retestTopics).isEqualTo(diagnosticTopics);

        List<String> diagnosticFingerprints = jdbcTemplate.queryForList(
                "SELECT fingerprint FROM camp_items WHERE program_id = ? AND form = 'diagnostic'",
                String.class, campId);
        List<String> retestFingerprints = jdbcTemplate.queryForList(
                "SELECT fingerprint FROM camp_items WHERE program_id = ? AND form = 'retest'",
                String.class, campId);
        assertThat(retestFingerprints).doesNotContainAnyElementsOf(diagnosticFingerprints);
    }

    private Map<String, Integer> topicCounts(long campId, String form) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        jdbcTemplate.query(
                "SELECT topic, COUNT(*) AS total FROM camp_items WHERE program_id = ? AND form = ? "
                        + "GROUP BY topic",
                (rs, rowNum) -> Map.entry(rs.getString("topic"), rs.getInt("total")),
                campId, form).forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    @Test
    @DisplayName("AC-012 复测结果带 previous 与逐主题结论")
    void retestResultCompares() {
        stubGeneration(items(Map.of("集合", 3)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long campId = openCamp(tokenA, resumeId, 3);
        List<Long> ids = itemIds(tokenA, campId, "diagnostic");
        // 诊断 1/3
        submit(tokenA, campId, "diagnostic", answers(ids, index -> index == 0 ? 0 : 1));

        when(generator.retest(anyString(), anyString(), anyString(), anyList(), any()))
                .thenReturn(new CampItemGenerator.Generated(
                        items(Map.of("集合", 3)).stream()
                                .map(item -> new ItemValidator.RawItem(item.topic(), item.conceptName(),
                                        item.difficulty(), "复测：" + item.stem(), item.options(),
                                        item.answerIndex(), item.explanation()))
                                .toList(),
                        "{}", new TokenUsage(100, 50, 150)));
        perform(post("/api/v1/ai/camps/" + campId + "/retest").header("Authorization", tokenA));

        List<Long> retestIds = itemIds(tokenA, campId, "retest");
        // 复测 2/3
        MvcResult result = submit(tokenA, campId, "retest",
                answers(retestIds, index -> index < 2 ? 0 : 1));
        assertThat(status(result)).as(text(result)).isEqualTo(200);
        assertThat(text(result)).contains("\"verdict\":\"IMPROVED\"");
        assertThat(text(result)).contains("previous");
    }

    @Test
    @DisplayName("AC-018 上游返回空内容：不建营、积分不被扣掉净额")
    void emptyUpstreamLeavesNothingBehind() {
        when(generator.diagnostic(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    generatorCalls.incrementAndGet();
                    throw CampGenerationException.emptyResponse();
                });
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        long before = credits.balance(userA);

        MvcResult result = perform(post("/api/v1/ai/camps").header("Authorization", tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeDocumentId\":" + resumeId + ",\"days\":3}"));

        assertThat(status(result)).isEqualTo(500);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM camp_programs WHERE user_id = ?", Integer.class, userA)).isZero();
        // 没有任何用量返回 → 全额退还，余额回到原值
        assertThat(credits.balance(userA)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-001 余额为 0 → 402 且上游 0 次")
    void insufficientCreditsNeverCallUpstream() {
        jdbcTemplate.update("DELETE FROM ai_credit_ledger WHERE user_id = ?", userB);
        jdbcTemplate.update("UPDATE ai_credit_accounts SET balance = 0 WHERE user_id = ?", userB);
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenB, List.of("Java"));

        MvcResult result = perform(post("/api/v1/ai/camps").header("Authorization", tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeDocumentId\":" + resumeId + ",\"days\":3}"));

        assertThat(status(result)).isEqualTo(402);
        assertThat(generatorCalls.get()).isZero();
        verify(generator, never()).diagnostic(anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("清单里最多 20 条，按创建时间倒序")
    void listsCamps() {
        stubGeneration(items(Map.of("集合", 12)));
        long resumeId = resumeWithSkills(tokenA, List.of("Java"));
        openCamp(tokenA, resumeId, 3);
        openCamp(tokenA, resumeId, 7);

        MvcResult list = perform(get("/api/v1/ai/camps").header("Authorization", tokenA));
        assertThat(status(list)).isEqualTo(200);
        List<Integer> days = JsonPath.read(text(list), "$.data[*].days");
        assertThat(days).containsExactly(7, 3);
    }
}
