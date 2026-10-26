package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.account.internal.user.PasswordHasher;
import io.github.xiaou61.mcp.internal.token.PatService;
import io.github.xiaou61.platform.ToolScope;
import io.github.xiaou61.security.AuthPort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 管理工具的两把锁（WORK-038 AC-001~005）。
 *
 * <p>钉住四条"错了不会报错、只会静默放权"的东西：非管理员拿不到 admin scope、
 * 非管理员看不到管理组（404 而不是空清单）、管理员先访问后学习者不串清单、
 * 学习者默认视图不出现任何管理工具。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class McpAdminIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuthPort authPort;

    @Autowired
    PasswordHasher passwordHasher;

    @Autowired
    PatService tokens;

    @Autowired
    io.github.xiaou61.platform.ToolCatalog catalog;

    private Long learnerId;
    private Long adminId;

    @AfterEach
    void cleanUp() {
        for (Long id : new Long[]{learnerId, adminId}) {
            if (id == null) {
                continue;
            }
            jdbcTemplate.update("DELETE FROM mcp_access_tokens WHERE user_id = ?", id);
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbcTemplate.update("DELETE FROM learner_profiles WHERE user_id = ?", id);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
        learnerId = null;
        adminId = null;
    }

    @Test
    @DisplayName("非管理员申请 admin scope 被 400 拒绝，管理员可以")
    void adminScopeRequiresAdminRole() throws Exception {
        learnerId = insertUser("learner");
        adminId = insertUser("admin");

        String learnerBody = "{\"name\":\"偷管理\",\"scopes\":[\"read\",\"admin\"]}";
        int learnerStatus = mockMvc.perform(post("/api/v1/ai/mcp-tokens")
                        .header("Authorization", jwt(learnerId, "learner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(learnerBody))
                .andReturn().getResponse().getStatus();
        assertThat(learnerStatus).isEqualTo(400);

        String adminBody = "{\"name\":\"管理\",\"scopes\":[\"read\",\"admin\"],\"toolsets\":[\"admin_users\"]}";
        String adminResponse = mockMvc.perform(post("/api/v1/ai/mcp-tokens")
                        .header("Authorization", jwt(adminId, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminBody))
                .andReturn().getResponse().getContentAsString();
        assertThat(adminResponse).contains("admin").contains("admin_users");
    }

    @Test
    @DisplayName("管理员能看到管理组工具；学习者同一路径 404；默认视图不出现管理工具")
    void adminViewIsNotSharedWithLearner() throws Exception {
        learnerId = insertUser("learner");
        adminId = insertUser("admin");
        String adminPat = adminPat();
        String learnerPat = learnerPat();

        // 管理员先访问，确保"管理员建好的清单"确实进了缓存
        String adminSession = initialize("/mcp/x/admin_users", adminPat);
        List<String> adminTools = tools("/mcp/x/admin_users", adminPat, adminSession);
        assertThat(adminTools)
                .contains("admin_users_list", "admin_users_get")
                .doesNotContain("admin_users_delete");
        assertThat(adminTools).allMatch(name -> name.startsWith("admin_users_"));

        // 学习者同一路径：404，而不是"200 + 空清单"
        HttpResponse<String> learnerView = postJson("/mcp/x/admin_users", learnerPat, initializeBody(), null);
        assertThat(learnerView.statusCode()).isEqualTo(404);

        // 学习者默认视图与改动前一致：12 个只读工具，且没有任何管理工具
        List<String> learnerTools = tools("/mcp", learnerPat, initialize("/mcp", learnerPat));
        assertThat(learnerTools).hasSize(12);
        assertThat(learnerTools).noneMatch(name -> name.startsWith("admin_"));
    }

    @Test
    @DisplayName("管理写工具只在 /write 视图出现")
    void writeToolNeedsWritePath() throws Exception {
        adminId = insertUser("admin");
        String adminPat = adminPat();

        assertThat(tools("/mcp/x/admin_users", adminPat, initialize("/mcp/x/admin_users", adminPat)))
                .doesNotContain("admin_users_set_role", "admin_users_delete");
        assertThat(tools("/mcp/x/admin_users/write", adminPat,
                initialize("/mcp/x/admin_users/write", adminPat)))
                .contains("admin_users_set_role", "admin_users_delete");
    }

    @Test
    @DisplayName("38 个管理工具全部注册、全部 ADMIN、只落在 7 个管理组")
    void allAdminToolsAreRegistered() {
        List<io.github.xiaou61.platform.ToolDescriptor> admin = catalog.snapshot().stream()
                .filter(tool -> tool.name().startsWith("admin_"))
                .toList();

        assertThat(admin).hasSize(38);
        assertThat(admin).allMatch(tool -> tool.scope() == ToolScope.ADMIN);
        assertThat(admin).allMatch(tool -> !tool.defaultGroup());
        assertThat(admin.stream().map(io.github.xiaou61.platform.ToolDescriptor::toolset)
                .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("admin_content", "admin_users", "admin_ai",
                        "admin_review", "admin_learning", "admin_ops", "admin_digest");
        assertThat(admin).filteredOn(io.github.xiaou61.platform.ToolDescriptor::readOnly).hasSize(19);
        assertThat(admin).filteredOn(io.github.xiaou61.platform.ToolDescriptor::destructive).hasSize(9);
    }

    // ------------------------------------------------------------- helpers

    private String adminPat() {
        return tokens.create(adminId, "管理集成测试", Set.of(ToolScope.ADMIN), Set.of(), 1, "admin")
                .plaintext();
    }

    private String learnerPat() {
        return tokens.create(learnerId, "学习者集成测试", Set.of(ToolScope.READ), Set.of(), 1, "learner")
                .plaintext();
    }

    private long insertUser(String role) {
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        jdbcTemplate.update("""
                INSERT INTO users (username, email, password_hash, role, email_verified_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                """, "ma-" + suffix, "ma-" + suffix + "@example.test",
                passwordHasher.hash("mcp-admin-test-password"), role);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM users", Long.class);
    }

    private String jwt(long id, String role) {
        return "Bearer " + authPort.issue(new AuthPort.Subject(Long.toString(id), role)).value();
    }

    private String initialize(String path, String token) throws IOException, InterruptedException {
        HttpResponse<String> response = postJson(path, token, initializeBody(), null);
        assertThat(response.statusCode())
                .as("initialize 应成功，实际响应：%s", response.body())
                .isEqualTo(200);
        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse("");
        assertThat(sessionId).as("Streamable HTTP 必须回 Mcp-Session-Id").isNotBlank();
        postJson(path, token, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId);
        return sessionId;
    }

    private List<String> tools(String path, String token, String sessionId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = postJson(path, token,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", sessionId);
        assertThat(response.statusCode()).as("tools/list：%s", response.body()).isEqualTo(200);
        List<String> names = JsonPath.read(jsonPayload(response.body()), "$.result.tools[*].name");
        return names;
    }

    private HttpResponse<String> postJson(String path, String token, String body, String sessionId)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonPayload(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (!trimmed.contains("data:")) {
            return trimmed;
        }
        StringBuilder json = new StringBuilder();
        for (String line : trimmed.lines().toList()) {
            if (line.startsWith("data:")) {
                json.append(line.substring("data:".length()).trim());
            }
        }
        return json.length() == 0 ? trimmed : json.toString();
    }

    private static String initializeBody() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18",
                  "capabilities":{},
                  "clientInfo":{"name":"paideia-admin-smoke","version":"1.0"}}}
                """;
    }
}
