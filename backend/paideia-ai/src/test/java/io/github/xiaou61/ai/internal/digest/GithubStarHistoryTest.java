package io.github.xiaou61.ai.internal.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * star 历史的日期映射与重定向（WORK-041）。
 *
 * <p>**最贵的坑是日期**：端点的 `week` 标签是周日，但 `days` 从周一开始。
 * 2026-10-26 用三个创建日期已知的仓库核对出 `15 + 267 + 860 = 1142`，这里把它钉住。
 */
class GithubStarHistoryTest {

    private final GithubStarHistory history = new GithubStarHistory();

    /** week=2026-10-18（周日），days 从周一开始：周六 10-24=15、周日 10-25=267。 */
    private static final String FIXTURE = """
            [{"week":1792281600,"total":282,"days":[0,0,0,0,0,15,267]}]
            """;

    @Test
    @DisplayName("week 是周日标签，但 days 从周一开始：周日取 days[6]，不是 days[0]")
    void weekLabelIsSundayButDaysStartMonday() {
        GithubStarHistory.Stats stats = history.parse(FIXTURE, LocalDate.of(2026, 10, 25));

        assertThat(stats.dayStars()).as("10-25 是周日，在 days[6]").isEqualTo(267);
        assertThat(stats.weekStars()).as("近 7 日 = 15 + 267").isEqualTo(282);
    }

    @Test
    @DisplayName("周六取 days[5]")
    void saturdayIsFifthDay() {
        GithubStarHistory.Stats stats = history.parse(FIXTURE, LocalDate.of(2026, 10, 24));

        assertThat(stats.dayStars()).isEqualTo(15);
        assertThat(stats.weekStars()).isEqualTo(15);
    }

    @Test
    @DisplayName("空历史返回 0，不抛异常")
    void emptyHistoryIsZero() {
        GithubStarHistory.Stats stats = history.parse("[]", LocalDate.of(2026, 10, 25));

        assertThat(stats.dayStars()).isZero();
        assertThat(stats.weekStars()).isZero();
    }

    @Test
    @DisplayName("同 host 301 跟随成功")
    void sameHostRedirectIsFollowed() throws Exception {
        HttpServer server = server();
        server.createContext("/same", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String text = new DigestHttp(5).getTextFollowingSameHostRedirects(
                    base + "/same", "test", Map.of(), "127.0.0.1", 3);
            assertThat(text).isEqualTo("ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("跨 host 301 必须拒绝：请求可能带 token")
    void crossHostRedirectIsRejected() throws Exception {
        HttpServer server = server();
        server.createContext("/cross", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://example.com/x");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThatThrownBy(() -> new DigestHttp(5).getTextFollowingSameHostRedirects(
                    base + "/cross", "test", Map.of(), "127.0.0.1", 3))
                    .isInstanceOf(DigestSource.DigestSourceException.class)
                    .hasMessageContaining("跨 host");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }
}
