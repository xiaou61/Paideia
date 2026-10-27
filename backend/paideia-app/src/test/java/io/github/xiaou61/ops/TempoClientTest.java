package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempo 查询与 trace 属性脱敏（WORK-043 AC-009/AC-016）。 */
class TempoClientTest {

    private HttpServer server;
    private String baseUrl;
    private int status = 200;
    private String searchBody = "{}";
    private String traceBody = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/search", exchange -> respond(exchange, searchBody));
        server.createContext("/api/traces/", exchange -> respond(exchange, traceBody));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    @DisplayName("搜索错误/慢 trace 并按 id 去重")
    void searchesAndDeduplicates() {
        searchBody = """
                {"traces":[{"traceID":"t1","rootTraceName":"POST /api/v1/ai/chat",
                 "durationMs":2400,"startTimeUnixNano":"1761432000000000000"}]}""";

        List<TempoClient.TraceSummary> traces = client().search(
                Instant.now().minusSeconds(3600), Instant.now(), 5);

        assertThat(traces).hasSize(1);
        assertThat(traces.getFirst().traceId()).isEqualTo("t1");
        assertThat(traces.getFirst().durationMs()).isEqualTo(2400);
        assertThat(traces.getFirst().start()).isEqualTo(Instant.ofEpochSecond(1_761_432_000L));
    }

    @Test
    @DisplayName("单条 trace 只保留白名单属性")
    void traceDropsForbiddenAttributes() {
        traceBody = """
                {"batches":[{"scopeSpans":[{"spans":[
                  {"name":"SELECT users","startTimeUnixNano":"1761432000000000000",
                   "endTimeUnixNano":"1761432001800000000","status":{"code":"STATUS_CODE_ERROR"},
                   "attributes":[
                     {"key":"db.system","value":{"stringValue":"mysql"}},
                     {"key":"db.statement","value":{"stringValue":"select * from users"}},
                     {"key":"authorization","value":{"stringValue":"Bearer secret"}}
                   ]}
                ]}]}]}""";

        Optional<TempoClient.TraceDetail> detail = client().trace("t1");

        assertThat(detail).isPresent();
        TempoClient.Span span = detail.get().slowest();
        assertThat(span.durationMs()).isEqualTo(1800);
        assertThat(span.attributes()).containsEntry("db.system", "mysql");
        assertThat(span.attributes()).doesNotContainKeys("db.statement", "authorization");
    }

    @Test
    @DisplayName("Tempo 非 200 时返回空")
    void non200ReturnsEmpty() {
        status = 503;
        assertThat(client().search(Instant.now().minusSeconds(60), Instant.now(), 5)).isEmpty();
        assertThat(client().trace("t1")).isEmpty();
    }

    private TempoClient client() {
        return new TempoClient(baseUrl, 2_000);
    }
}
