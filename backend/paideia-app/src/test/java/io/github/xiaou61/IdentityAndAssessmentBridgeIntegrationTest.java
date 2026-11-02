package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.xiaou61.account.AccountApi;
import io.github.xiaou61.account.Identity;
import io.github.xiaou61.ai.internal.memory.MemoryStore;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 身份化与自评桥（WORK-010 T-02/T-03/T-07/T-08）。
 *
 * <p>三条主线：
 * <ul>
 *   <li><b>身份与角色正交</b>（AC-001）：改身份不动 `role`、不动任何权限判定；</li>
 *   <li><b>自评桥是覆盖语义</b>（AC-008）：同一条目反复改，记忆始终只有一条且是最后一次；</li>
 *   <li><b>会话记 harness</b>（AC-005 的数据层）：建面试会话后，恢复时读到的就是面试行为。</li>
 * </ul>
 *
 * <p>用完整上下文 + 真实 MySQL + MockMvc。自评走真实端点（它会发事件），所以这条用例同时验了
 * "事件在事务内被监听器消费"这件事——那正是桥最容易被写错的地方。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class IdentityAndAssessmentBridgeIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    AccountApi accountApi;

    @Autowired
    MemoryStore memories;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    io.github.xiaou61.ai.internal.conversation.ConversationService conversations;

    private long user;
    private long entryId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
        jdbcTemplate.update("DELETE FROM learner_profiles");
        jdbcTemplate.update("DELETE FROM knowledge_self_assessments");
        // 必须是**真实的 users 行**：画像读取会先确认账号存在（不存在返回空），
        // 而编一个数字 id 会让"新用户读到默认身份"那条断言直接走到 orElseThrow。
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        jdbcTemplate.update("""
                INSERT INTO users (username, email, password_hash, role, email_verified_at, created_at, updated_at)
                VALUES (?, ?, '$2a$10$yeTcR4AZnZhA.xO4T.rK4.PDy8qpFeYgwxpsjMOAAKby3AQ6/AlNy', 'learner',
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, "w10-user-" + suffix, "w10-" + suffix + "@example.test");
        user = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, "w10-user-" + suffix);
        // 一条真实存在且已发布的种子条目（种子数据在 devdata，集成测试的 flyway 会加载它）
        entryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE status = 'published' ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_messages WHERE conversation_id IN "
                + "(SELECT id FROM ai_conversations WHERE user_id = ?)", user);
        jdbcTemplate.update("DELETE FROM ai_conversations WHERE user_id = ?", user);
        jdbcTemplate.update("DELETE FROM learner_profiles WHERE user_id = ?", user);
        jdbcTemplate.update("DELETE FROM knowledge_self_assessments WHERE user_id = ?", user);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user);
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
        jdbcTemplate.update("DELETE FROM learner_profiles");
        jdbcTemplate.update("DELETE FROM knowledge_self_assessments");
    }

    @Test
    @DisplayName("AC-001：改身份不改变 role，也不改变管理端访问判定")
    void identityIsOrthogonalToRole() throws Exception {
        // 先给这个人一个 learner 角色（默认就是），记下来
        String before = roleOf();
        assertThat(before).isEqualTo("learner");

        assertThat(putProfile("{\"identity\":\"job_seeker\",\"targetRole\":\"后端开发\"}").getResponse().getStatus())
                .isEqualTo(200);

        // 身份变了，角色一个字节没动
        assertThat(roleOf()).isEqualTo(before);
        // 管理端仍然拒绝（403）——身份不参与权限判定
        assertThat(mockMvc.perform(get("/api/v1/knowledge/admin/entries").header("Authorization", token(user)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("T-02/T-03：新用户读到默认身份；保存后读回；不可选的身份被拒")
    void profileDefaultsAndRoundTrips() throws Exception {
        // 没有画像行 → 默认 job_seeker，画像字段全空（不是"没有身份"）
        assertThat(accountApi.findProfile(Long.toString(user)).orElseThrow().identity())
                .isEqualTo(Identity.JOB_SEEKER);

        assertThat(putProfile("{\"identity\":\"job_seeker\",\"targetRole\":\"后端开发\","
                + "\"targetCity\":\"武汉\",\"timeline\":\"autumn\",\"stage\":\"applying\"}")
                .getResponse().getStatus()).isEqualTo(200);

        var profile = accountApi.findProfile(Long.toString(user)).orElseThrow();
        assertThat(profile.targetRole()).isEqualTo("后端开发");
        assertThat(profile.targetCity()).isEqualTo("武汉");
        assertThat(profile.timeline()).isEqualTo("autumn");

        // 一期只有 job_seeker 可选：考研虽然有枚举值，但通过接口写不进去（避免幽灵状态）
        assertThat(putProfile("{\"identity\":\"postgrad\"}").getResponse().getStatus()).isEqualTo(400);
        // 不认识的字符串直接拒，不静默退回默认（否则用户以为选上了）
        assertThat(putProfile("{\"identity\":\"nonsense\"}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("AC-008：自评桥写一条记忆；反复改仍只有一条且是最后一次；取消后消失")
    void assessmentBridgeIsOverwriteSemantics() throws Exception {
        assertThat(mark(entryId, "unsure").getResponse().getStatus()).isEqualTo(200);
        assertThat(memories.list(user, 0, 0)).hasSize(1);
        assertThat(memories.list(user, 0, 0).getFirst().text()).contains("还不懂");

        // 改成"已掌握"：仍然一条，内容是最后一次（覆盖，不堆积）
        assertThat(mark(entryId, "understood").getResponse().getStatus()).isEqualTo(200);
        assertThat(memories.list(user, 0, 0)).hasSize(1);
        assertThat(memories.list(user, 0, 0).getFirst().text()).contains("已掌握");

        // 再来回改几次，条数不变
        mark(entryId, "unsure");
        mark(entryId, "understood");
        assertThat(memories.list(user, 0, 0)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memories_structured WHERE user_id = ?", Long.class, user)).isEqualTo(1L);

        // 取消自评 → 那条记忆消失（否则标记没了助手还记着，是假记忆）
        assertThat(mockMvc.perform(delete("/api/v1/knowledge/entries/" + entryId + "/self-assessment")
                        .header("Authorization", token(user)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(memories.list(user, 0, 0)).isEmpty();
    }

    @Test
    @DisplayName("AC-005 数据层：会话记住 harness，恢复时按它选行为")
    void conversationRemembersHarness() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/ai/conversations")
                        .header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"harness\":\"interview\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        long id = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversations WHERE user_id = ? ORDER BY id DESC LIMIT 1", Long.class, user);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT harness FROM ai_conversations WHERE id = ?", String.class, id)).isEqualTo("interview");

        // 从**通用入口**（不传 harness）建的新会话是 assistant，不影响上面那段
        conversations.create(user, null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_conversations WHERE user_id = ? AND harness = 'assistant'",
                Long.class, user)).isEqualTo(1L);
    }

    @Test
    @DisplayName("身份端点：匿名 401；别人的画像读不到（按令牌取，请求体里没有 userId）")
    void profileEndpointsAreOwnerScoped() throws Exception {
        // 匿名：401（由 anyRequest().authenticated() 兜住，路径本身没有角色限制）
        assertThat(mockMvc.perform(get("/api/v1/account/profile")).andReturn().getResponse().getStatus())
                .isEqualTo(401);

        // 另一个**真实账号**：它读到的必须是它自己的默认画像，看不到我存的那份。
        // 引擎把 profile 端点声明为 AUTHENTICATED，所以这条用例改用"两个真实账号"来验归属，
        // 而不是拿一个编出来的 id 去测（那会得到 200 + 空画像，验不出"读不到别人的"）。
        String otherName = "w10-other-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO users (username, email, password_hash, role, email_verified_at, created_at, updated_at)
                VALUES (?, ?, '$2a$10$yeTcR4AZnZhA.xO4T.rK4.PDy8qpFeYgwxpsjMOAAKby3AQ6/AlNy', 'learner',
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, otherName, otherName + "@example.test");
        long other = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, otherName);
        try {
            assertThat(mockMvc.perform(get("/api/v1/account/profile").header("Authorization", token(other)))
                            .andReturn().getResponse().getStatus()).isEqualTo(200);
            putProfile("{\"identity\":\"job_seeker\",\"targetRole\":\"只应该是我的\"}");
            MvcResult others = mockMvc.perform(get("/api/v1/account/profile")
                            .header("Authorization", token(other)))
                    .andReturn();
            assertThat(body(others)).doesNotContain("只应该是我的");
        } finally {
            jdbcTemplate.update("DELETE FROM learner_profiles WHERE user_id = ?", other);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", other);
        }
    }

    // ---- 辅助 ----

    private MvcResult putProfile(String json) throws Exception {
        return mockMvc.perform(put("/api/v1/account/profile")
                        .header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private MvcResult mark(long entry, String level) throws Exception {
        return mockMvc.perform(put("/api/v1/knowledge/entries/" + entry + "/self-assessment")
                        .header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"" + level + "\"}"))
                .andReturn();
    }

    private String roleOf() {
        return accountApi.findRoleById(Long.toString(user)).orElseThrow();
    }

    private String token(long userId) {
        return "Bearer " + authPort.issue(new AuthPort.Subject(Long.toString(userId), AuthPort.ROLE_LEARNER)).value();
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
