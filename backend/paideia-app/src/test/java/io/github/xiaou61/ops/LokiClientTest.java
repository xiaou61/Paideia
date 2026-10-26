package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Loki 查询客户端的解析与失败降级（WORK-042）。
 *
 * <p>用 JDK 自带的 HttpServer 造响应，不引 MockWebServer——这里只需要验证
 * "values 数组怎么解、失败时不抛异常"这两件事。
 */
class LokiClientTest {

    private HttpServer server;
    private String baseUrl;
    private int status = 200;
    private String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loki/api/v1/query_range", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("解析 values 并按时间倒序")
    void parsesAndSortsDescending() {
        body = """
                {"status":"success","data":{"result":[
                  {"stream":{"unit":"paideia.service"},"values":[
                    ["1761432000000000000","old line"],
                    ["1761432060000000000","new line"]
                  ]},
                  {"stream":{"unit":"laya.service"},"values":[
                    ["1761432030000000000","middle line"]
                  ]}
                ]}}""";

        List<LokiClient.LogLine> lines = client().queryRange(
                "{unit=\"paideia.service\"}", Instant.ofEpochSecond(1_761_432_000L),
                Instant.ofEpochSecond(1_761_432_100L), 10);

        assertThat(lines).extracting(LokiClient.LogLine::line)
                .containsExactly("new line", "middle line", "old line");
        assertThat(lines.getFirst().at()).isEqualTo(Instant.ofEpochSecond(1_761_432_060L));
    }

    @Test
    @DisplayName("HTTP 非 200 返回空列表而不是抛异常")
    void non200ReturnsEmpty() {
        status = 500;
        body = "boom";

        assertThat(client().queryRange("{}", Instant.now().minusSeconds(60), Instant.now(), 10))
                .isEmpty();
    }

    @Test
    @DisplayName("坏 JSON 返回空列表")
    void badJsonReturnsEmpty() {
        body = "not json";

        assertThat(client().queryRange("{}", Instant.now().minusSeconds(60), Instant.now(), 10))
                .isEmpty();
    }

    private LokiClient client() {
        return new LokiClient(baseUrl, 2_000);
    }
}
