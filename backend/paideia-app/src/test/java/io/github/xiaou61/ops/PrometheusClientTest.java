package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prometheus 数值解析（2026-11-03 区间审查）。
 *
 * <p>Prometheus 的 JSON 对特殊浮点值给的是字符串 `"+Inf"` / `"-Inf"` / `"NaN"`，而
 * `Double.valueOf` 只认 `"Infinity"`——不兜住就会抛 NumberFormatException，破坏本类
 * "失败一律返回空列表而不是抛异常"的契约。
 */
class PrometheusClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("+Inf 不抛异常（按无数据），NaN 交给下游按无数据判断")
    void specialFloatValuesBecomeNoData() throws Exception {
        PrometheusClient client = clientReturning("""
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"job":"x"},"value":[1700000000,"+Inf"]},
                  {"metric":{"job":"y"},"value":[1700000000,"NaN"]}]}}
                """);

        List<Double> values = client.query("up").stream()
                .map(PrometheusClient.Sample::value)
                .toList();

        // "+Inf" 是 Double.valueOf 认不出的写法（它只认 "Infinity"），解析失败即无数据；
        // "NaN" 本身能被解析，下游已有 NaN/Infinity → 无数据的处理。
        assertThat(values).hasSize(2);
        assertThat(values.get(0)).isNull();
        assertThat(values.get(1)).isNaN();
    }

    @Test
    @DisplayName("普通数值照常解析")
    void plainNumberIsParsed() throws Exception {
        PrometheusClient client = clientReturning("""
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"job":"x"},"value":[1700000000,"0.42"]}]}}
                """);

        assertThat(client.scalar("up")).isEqualTo(0.42);
    }

    private PrometheusClient clientReturning(String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return new PrometheusClient("http://127.0.0.1:" + server.getAddress().getPort(), 2000);
    }
}
