package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.mcp.internal.token.PatService;
import io.github.xiaou61.platform.ToolScope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * MCP 端点的启动冒烟（WORK-019 TASK-006 / AC-008 的前置）。
 *
 * <p><b>为什么这一条必须存在</b>：整条外部链路只依赖一个外部事实——`agentscope-core` 传递带入的
 * MCP SDK 0.17.0 能在 Boot 4 + JDK 25 上真的把 Streamable HTTP 服务端跑起来。这个事实没法靠读代码
 * 确认，只能真的发一次 `initialize` + `tools/list` 看它回不回。它失败的话，后面所有视图与鉴权
 * 都是空谈。
 *
 * <p>走真实 HTTP（{@code RANDOM_PORT}）而不是 MockMvc：MCP 传输层用的是 servlet 异步分派与
 * SSE，MockMvc 那条路会绕过容器行为，测不到真问题。
 *
 * <p>需要真实 MySQL（PAT 表）与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class McpStartupSmokeTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    PatService tokens;

    @Test
    @DisplayName("initialize + tools/list：默认视图能回包，且清单里只有只读默认组工具")
    void streamableHttpRoundTrip() throws Exception {
        PatService.CreatedToken created = tokens.create(7L, "冒烟", Set.of(ToolScope.READ), Set.of(), 1, null, "learner");

        String sessionId = initialize("/mcp", created.plaintext());
        List<String> names = tools(("/mcp"), created.plaintext(), sessionId);

        assertThat(names).isNotEmpty().contains("knowledge_search");
        // 默认视图是只读的：写工具一个都不该出现
        assertThat(names).doesNotContain("note_create", "note_append", "assessment_mark_entry");
    }

    @Test
    @DisplayName("子视图路由：/mcp/x/knowledge 只给知识组")
    void subViewRoutesToSingleToolset() throws Exception {
        PatService.CreatedToken created = tokens.create(7L, "冒烟-知识", Set.of(ToolScope.READ), Set.of(), 1, null, "learner");

        String sessionId = initialize("/mcp/x/knowledge", created.plaintext());
        List<String> names = tools("/mcp/x/knowledge", created.plaintext(), sessionId);

        assertThat(names).isNotEmpty().allMatch(name -> name.startsWith("knowledge_"));
    }

    /**
     * 凭据级工具组白名单（2026-10-22）。
     *
     * <p>线上实测踩到的真缺陷：工具清单在构建 server 时固化，而视图缓存键**只用了视图名**，
     * 于是第一个请求的清单服务了所有凭据——勾了"只授权知识库"，清单里仍是全部 11 个工具。
     * 现在缓存键带上工具组，这条钉死它。
     */
    @Test
    @DisplayName("只授权 knowledge 的凭据看不到别的组的工具（清单按凭据分）")
    void toolsetsLimitVisibleTools() throws Exception {
        PatService.CreatedToken scoped = tokens.create(7L, "冒烟-限组",
                Set.of(ToolScope.READ), Set.of("knowledge"), 1, null, "learner");
        PatService.CreatedToken open = tokens.create(7L, "冒烟-不限组",
                Set.of(ToolScope.READ), Set.of(), 1, null, "learner");

        List<String> scopedNames = tools("/mcp", scoped.plaintext(),
                initialize("/mcp", scoped.plaintext()));
        List<String> openNames = tools("/mcp", open.plaintext(),
                initialize("/mcp", open.plaintext()));

        assertThat(scopedNames).isNotEmpty().allMatch(name -> name.startsWith("knowledge_"));
        // 不限制的老凭据语义不变：仍然能看见别的组
        assertThat(openNames).contains("note_list", "learning_progress");
        assertThat(openNames.size()).isGreaterThan(scopedNames.size());
    }

    @Test
    @DisplayName("没有 PAT 一律 401——包括 initialize")
    void missingTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initializeBody()))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("pd_mcp", "token");
    }

    @Test
    @DisplayName("未知视图 404，而不是 400——不告诉探测者哪些组存在")
    void unknownViewIsNotFound() throws Exception {
        PatService.CreatedToken created = tokens.create(7L, "冒烟-路由", Set.of(ToolScope.READ), Set.of(), 1, null, "learner");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp/x/nope"))
                .header("Authorization", "Bearer " + created.plaintext())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initializeBody()))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------- helpers

    private String initialize(String path, String token) throws IOException, InterruptedException {
        HttpResponse<String> response = post(path, token, initializeBody(), null);
        assertThat(response.statusCode())
                .as("initialize 应成功，实际响应：%s", response.body())
                .isEqualTo(200);
        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse("");
        assertThat(sessionId).as("Streamable HTTP 必须回 Mcp-Session-Id").isNotBlank();
        // 握手第二步：告诉服务端"我准备好了"。失败也无所谓，但规范要求发
        post(path, token, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId);
        return sessionId;
    }

    private List<String> tools(String path, String token, String sessionId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = post(path, token,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", sessionId);
        assertThat(response.statusCode()).as("tools/list：%s", response.body()).isEqualTo(200);
        // Streamable HTTP 的回复可能是 `text/event-stream`：正文是 `event: message` + `data: {...}`。
        // 直接 JsonPath 整段会失败——先取出 data 行再解析。
        List<String> names = JsonPath.read(jsonPayload(response.body()), "$.result.tools[*].name");
        assertThat(names).as("tools/list 必须有工具，响应：%s", response.body()).isNotEmpty();
        return names;
    }

    private HttpResponse<String> post(String path, String token, String body, String sessionId)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                // Streamable HTTP 要求客户端两种都接受
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 从 SSE 正文里取出 JSON；不是 SSE 时原样返回。 */
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
                  "clientInfo":{"name":"paideia-smoke","version":"1.0"}}}
                """;
    }
}
