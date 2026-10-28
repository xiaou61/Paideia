package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.mcp.internal.token.PatService;
import io.github.xiaou61.mcp.internal.usage.McpUsageRecorder;
import io.github.xiaou61.platform.ToolScope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * 每日配额的**真实链路**验证（WORK-044 AC-008 / AC-011）。
 *
 * <p>`McpToolInvokerTest` 里那条配额用例用的是假 mapper，只能证明判定逻辑对。
 * 这里走真实 HTTP + 真实 MySQL，证明三件只有跑起来才知道的事：
 * <ol>
 *   <li>配额确实按"当天累计"拦，第 N+1 次被拒而前 N 次成功；</li>
 *   <li>计数真的落库了（重启不清零的前提）；</li>
 *   <li>被拒时工具没执行——返回的是"达到每日上限"而不是工具自己的错误。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class McpQuotaIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    PatService tokens;

    @Autowired
    McpUsageRecorder usage;

    @Test
    @DisplayName("每日上限 2：前两次成功，第三次被拒且工具未执行")
    void quotaStopsAfterLimit() throws Exception {
        // 用一个不存在的用户 id：本类只测配额与计数，不碰业务数据
        long userId = 987_654_321L;
        PatService.CreatedToken created = tokens.create(userId, "配额集成测试",
                Set.of(ToolScope.READ), Set.of(), 1, 2, "learner");
        String path = "/mcp/x/knowledge";

        String session = initialize(path, created.plaintext());
        String first = call(path, created.plaintext(), session, "knowledge_list_categories", 11);
        String second = call(path, created.plaintext(), session, "knowledge_list_categories", 12);
        String third = call(path, created.plaintext(), session, "knowledge_list_categories", 13);

        assertThat(first).as("第一次应当成功").doesNotContain("每日上限");
        assertThat(second).as("第二次应当成功").doesNotContain("每日上限");
        assertThat(third).as("第三次应当被配额拦下").contains("每日上限");

        // 计数落库：今天这个凭据记了 2 次——**被拒的那次不计**，否则重试会把配额越烧越高
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(usage.callsToday(created.id(), today))
                .as("被拒的调用不占配额，只留审计与指标")
                .isEqualTo(2);
        assertThat(usage.totals(created.id()).calls()).isEqualTo(2);

        tokens.deleteAllFor(userId);
    }

    @Test
    @DisplayName("不限上限的凭据不会被拦")
    void unlimitedTokenIsNotStopped() throws Exception {
        long userId = 987_654_322L;
        PatService.CreatedToken created = tokens.create(userId, "不限集成测试",
                Set.of(ToolScope.READ), Set.of(), 1, null, "learner");
        String path = "/mcp/x/knowledge";
        String session = initialize(path, created.plaintext());

        for (int i = 0; i < 3; i++) {
            assertThat(call(path, created.plaintext(), session, "knowledge_list_categories", 20 + i))
                    .doesNotContain("每日上限");
        }

        tokens.deleteAllFor(userId);
    }

    // ------------------------------------------------------------- helpers

    /**
     * 发一次 `tools/call`，返回正文。
     *
     * <p>**不断言 HTTP 状态**：MCP 的工具错误是 `200 + isError:true`，不是 4xx。
     * 断言状态码会让人误以为"被拦"应该是一个 HTTP 错误。
     */
    private String call(String path, String token, String sessionId, String tool, int id)
            throws IOException, InterruptedException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"" + tool + "\",\"arguments\":{}}}";
        HttpResponse<String> response = post(path, token, body, sessionId);
        assertThat(response.statusCode()).as("tools/call：%s", response.body()).isEqualTo(200);
        return jsonPayload(response.body());
    }

    private String initialize(String path, String token) throws IOException, InterruptedException {
        HttpResponse<String> response = post(path, token, initializeBody(), null);
        assertThat(response.statusCode()).as("initialize：%s", response.body()).isEqualTo(200);
        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse("");
        assertThat(sessionId).isNotBlank();
        post(path, token, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId);
        return sessionId;
    }

    private static String initializeBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"quota-test\",\"version\":\"1.0\"}}}";
    }

    private HttpResponse<String> post(String path, String token, String body, String sessionId)
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
        StringBuilder data = new StringBuilder();
        for (String line : trimmed.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("data:")) {
                data.append(stripped.substring("data:".length()).strip());
            }
        }
        return data.toString();
    }
}
