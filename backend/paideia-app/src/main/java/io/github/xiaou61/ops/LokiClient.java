package io.github.xiaou61.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 极薄的 Loki 查询客户端（WORK-042）。
 *
 * <p>只做一件事：`GET /loki/api/v1/query_range` 并把 `values` 解成「时间 + 日志行」。
 * 与 {@link PrometheusClient} 同一套失败语义：**任何失败都返回空列表**，调用方据此
 * 把"日志不可达"与"没有日志"分开呈现（前者写进证据，后者留空）。
 */
class LokiClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    LokiClient(String baseUrl, long timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    List<LogLine> queryRange(String query, Instant from, Instant to, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/loki/api/v1/query_range?query=" + encoded
                + "&start=" + from.getEpochSecond() + "000000000"
                + "&end=" + to.getEpochSecond() + "000000000"
                + "&limit=" + limit
                + "&direction=backward";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }
            JsonNode root = JSON.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                return List.of();
            }
            List<LogLine> lines = new ArrayList<>();
            for (JsonNode stream : root.path("data").path("result")) {
                for (JsonNode value : stream.path("values")) {
                    if (value.size() < 2) {
                        continue;
                    }
                    long nanos = Long.parseLong(value.get(0).asText());
                    lines.add(new LogLine(Instant.ofEpochSecond(0, nanos), value.get(1).asText()));
                }
            }
            // Loki 每个 stream 各自倒序；跨 stream 再排一次，调用方才能按"最近"截断。
            lines.sort(Comparator.comparing(LogLine::at).reversed());
            return lines.size() > limit ? lines.subList(0, limit) : lines;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    record LogLine(Instant at, String line) {
    }
}
