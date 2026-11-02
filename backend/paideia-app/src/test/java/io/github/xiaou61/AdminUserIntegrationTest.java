package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.xiaou61.account.internal.user.PasswordHasher;
import io.github.xiaou61.security.AuthPort;
import org.junit.jupiter.api.AfterEach;
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
 * 管理端用户管理（WORK-030 阶段 A）。
 *
 * <p><b>这里钉住的四条都是"错了不会报错、只会静默放权"的东西</b>：
 * <ol>
 *   <li>学习者调管理端端点必须 403——权限规则写错位置时，Spring 不会报错，只会静默放行；</li>
 *   <li>管理员不能动自己——前端藏按钮挡不住直接调接口；</li>
 *   <li>响应体里没有 `password_hash`——这一条由类型保证，但仍然要真跑一次看一眼；</li>
 *   <li>管理员删号与自助注销走同一段级联——复制一份删除逻辑的话，以后加表就会漏一处。</li>
 * </ol>
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AdminUserIntegrationTest {

    private static final String PASSWORD = "admin-user-test-password";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuthPort authPort;

    @Autowired
    PasswordHasher passwordHasher;

    private Long learnerId;
    private Long adminId;

    @AfterEach
    void cleanUp() {
        for (Long id : new Long[]{learnerId, adminId}) {
            if (id == null) {
                continue;
            }
            // 逐表清理由 SqlCleanup 统一（顺序 = 参数顺序，子表在前）；users 走 id 列，单独删
            SqlCleanup.deleteByUser(jdbcTemplate, id, "refresh_tokens", "ai_credit_ledger", "ai_credit_accounts",
                    "ai_runs", "learner_profiles");
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
        learnerId = null;
        adminId = null;
    }

    private long insertUser(String role) {
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        jdbcTemplate.update("""
                INSERT INTO users (username, email, password_hash, role, email_verified_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                """, "au-" + suffix, "au-" + suffix + "@example.test",
                passwordHasher.hash(PASSWORD), role);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM users", Long.class);
    }

    private String token(long id, String role) {
        return TestTokens.bearer(authPort, Long.toString(id), role);
    }

    @Test
    @DisplayName("学习者调管理端端点一律 403（权限规则写错位置时不会报错，只会静默放行）")
    void learnerIsForbidden() throws Exception {
        learnerId = insertUser("learner");
        String token = token(learnerId, AuthPort.ROLE_LEARNER);

        assertThat(mockMvc.perform(get("/api/v1/account/admin/users").header("Authorization", token))
                .andReturn().getResponse().getStatus())
                .as("列表").isEqualTo(403);
        assertThat(mockMvc.perform(get("/api/v1/account/admin/users/" + learnerId)
                        .header("Authorization", token))
                .andReturn().getResponse().getStatus())
                .as("详情").isEqualTo(403);
        assertThat(mockMvc.perform(post("/api/v1/account/admin/users/" + learnerId + "/role")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"admin\"}"))
                .andReturn().getResponse().getStatus())
                .as("改角色——这一条若放开等于学习者能给自己提权").isEqualTo(403);
        assertThat(mockMvc.perform(delete("/api/v1/account/admin/users/" + learnerId)
                        .header("Authorization", token))
                .andReturn().getResponse().getStatus())
                .as("删号").isEqualTo(403);
    }

    @Test
    @DisplayName("管理员能列表；响应里没有 password_hash")
    void adminCanListWithoutPasswordHash() throws Exception {
        adminId = insertUser("admin");
        learnerId = insertUser("learner");
        String token = token(adminId, AuthPort.ROLE_ADMIN);

        String body = mockMvc.perform(get("/api/v1/account/admin/users")
                        .param("query", "au-")
                        .header("Authorization", token))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("列表里应有刚造的两个账号").contains(String.valueOf(learnerId));
        assertThat(body.toLowerCase())
                .as("响应体里出现了口令摘要")
                .doesNotContain("passwordhash")
                .doesNotContain("password_hash")
                .doesNotContain("$2a$");
    }

    @Test
    @DisplayName("管理员不能动自己：改角色、删号、调积分三条都拒绝")
    void cannotTouchSelf() throws Exception {
        adminId = insertUser("admin");
        String token = token(adminId, AuthPort.ROLE_ADMIN);

        assertThat(mockMvc.perform(post("/api/v1/account/admin/users/" + adminId + "/role")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"learner\"}"))
                .andReturn().getResponse().getStatus())
                .as("自降级会把自己锁在管理端外面").isEqualTo(400);

        assertThat(mockMvc.perform(delete("/api/v1/account/admin/users/" + adminId)
                        .header("Authorization", token))
                .andReturn().getResponse().getStatus())
                .as("自删").isEqualTo(400);

        assertThat(mockMvc.perform(post("/api/v1/account/admin/users/" + adminId + "/credits")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":100,\"note\":\"给自己发分\"}"))
                .andReturn().getResponse().getStatus())
                .as("给自己发积分").isEqualTo(400);

        // 三次拒绝之后账号还在、角色没变
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, adminId))
                .isEqualTo("admin");
    }

    @Test
    @DisplayName("改角色会吊销该用户全部 refresh token（U-18）")
    void changeRoleRevokesRefreshTokens() throws Exception {
        adminId = insertUser("admin");
        learnerId = insertUser("learner");
        jdbcTemplate.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, family_id, expires_at)
                VALUES (?, REPEAT('c', 64), UUID(), DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 1 DAY))
                """, learnerId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Long.class, learnerId)).isPositive();

        assertThat(mockMvc.perform(post("/api/v1/account/admin/users/" + learnerId + "/role")
                        .header("Authorization", token(adminId, AuthPort.ROLE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"admin\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, learnerId))
                .as("角色已改").isEqualTo("admin");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Long.class, learnerId))
                .as("refresh token 必须被吊销——否则他刷出新令牌还是旧角色").isZero();
    }

    @Test
    @DisplayName("调积分必须写原因")
    void adjustCreditsRequiresNote() throws Exception {
        adminId = insertUser("admin");
        learnerId = insertUser("learner");
        String token = token(adminId, AuthPort.ROLE_ADMIN);

        assertThat(mockMvc.perform(post("/api/v1/account/admin/users/" + learnerId + "/credits")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":100,\"note\":\"\"}"))
                .andReturn().getResponse().getStatus())
                .as("没写原因").isEqualTo(400);

        assertThat(mockMvc.perform(post("/api/v1/account/admin/users/" + learnerId + "/credits")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":100,\"note\":\"新用户补贴\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM ai_credit_accounts WHERE user_id = ?", Long.class, learnerId))
                .isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_credit_ledger WHERE user_id = ? AND kind = 'ADJUST'",
                Long.class, learnerId)).as("留了一条调整流水").isEqualTo(1L);
    }

    @Test
    @DisplayName("管理员删号走同一段级联：users 与 refresh_tokens 都清，且不需要对方密码")
    void adminDeletionCascades() throws Exception {
        adminId = insertUser("admin");
        learnerId = insertUser("learner");
        long target = learnerId;
        jdbcTemplate.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, family_id, expires_at)
                VALUES (?, REPEAT('d', 64), UUID(), DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 1 DAY))
                """, target);

        assertThat(mockMvc.perform(delete("/api/v1/account/admin/users/" + target)
                        .header("Authorization", token(adminId, AuthPort.ROLE_ADMIN)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Long.class, target))
                .as("users 那一行还在").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Long.class, target))
                .as("refresh token 没删").isZero();
        learnerId = null; // 已被删掉，收尾不必再删
    }

    @Test
    @DisplayName("列表带用量指标：余额、已用、调用次数")
    void listIncludesUsageMetrics() throws Exception {
        adminId = insertUser("admin");
        learnerId = insertUser("learner");
        jdbcTemplate.update("INSERT INTO ai_credit_accounts (user_id, balance) VALUES (?, 500)", learnerId);
        jdbcTemplate.update("""
                INSERT INTO ai_credit_ledger (user_id, delta, kind, note)
                VALUES (?, -30, 'SETTLE', '用例')
                """, learnerId);

        String body = mockMvc.perform(get("/api/v1/account/admin/users/" + learnerId)
                        .header("Authorization", token(adminId, AuthPort.ROLE_ADMIN)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("余额").contains("\"balance\":500");
        assertThat(body).as("已用只算流出").contains("\"usedCredits\":30");
        assertThat(body).as("未调用过时次数为 0").contains("\"runCount\":0");
    }
}
