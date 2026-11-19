package io.github.xiaou61.ops;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * /loki/api/v1/patterns 的响应解析（WORK-085）。
 *
 * <p>这里的响应体是 2026-11-19 在线上 Loki 3.7.9 上**实际抓到的形状**：{@code data} 是数组，
 * 每个元素有 {@code pattern}/{@code level}/{@code samples}，samples 是 {@code [epoch秒, 条数]}。
 * 早期实现把 samples 求和成一条，会把长窗口的数据全塞进一个检测桶，所以钉住这个解析。
 */
class LokiClientPatternsTest {

    private static HttpServer server(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loki/api/v1/patterns", exchange -> {
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static LokiClient client(HttpServer server) {
        return new LokiClient("http://127.0.0.1:" + server.getAddress().getPort(), 5000);
    }

    @Test
    @DisplayName("按采样点保留时间与条数，而不是求和成一条")
    void keepsPerSampleTimestamps() throws Exception {
        String body = "{\"status\":\"success\",\"data\":[{\"pattern\":\"handled GET /api/probe/x -\\u003e 200 in <_>\","
                + "\"level\":\"info\",\"samples\":[[1795058170,2],[1795058180,10],[1795058190,10]]}]}";
        HttpServer server = server(200, body);
        try {
            List<LokiClient.PatternSeries> out = client(server).patterns(
                    "{unit=\"laya.service\"}", Instant.ofEpochSecond(1795058160),
                    Instant.ofEpochSecond(1795058200), 10);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).total()).isEqualTo(22);
            assertThat(out.get(0).samples()).hasSize(3);
            assertThat(out.get(0).samples().get(0).at()).isEqualTo(Instant.ofEpochSecond(1795058170));
            assertThat(out.get(0).samples().get(0).count()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("按窗口内总条数倒序，并截到 maxSeries")
    void sortsByTotalAndCaps() throws Exception {
        String body = "{\"status\":\"success\",\"data\":["
                + "{\"pattern\":\"small\",\"samples\":[[1795058170,3]]},"
                + "{\"pattern\":\"big\",\"samples\":[[1795058170,40]]}]}";
        HttpServer server = server(200, body);
        try {
            List<LokiClient.PatternSeries> out = client(server).patterns(
                    "{unit=\"x\"}", Instant.ofEpochSecond(1795058160), Instant.ofEpochSecond(1795058200), 1);
            assertThat(out).hasSize(1);
            assertThat(out.get(0).pattern()).isEqualTo("big");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("接口 404（旧版 Loki/未开 pattern_ingester）降级为空，不抛")
    void missingEndpointDegrades() throws Exception {
        HttpServer server = server(404, "{\"message\":\"not found\"}");
        try {
            assertThat(client(server).patterns("{unit=\"x\"}",
                    Instant.ofEpochSecond(1795058160), Instant.ofEpochSecond(1795058200), 10)).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
