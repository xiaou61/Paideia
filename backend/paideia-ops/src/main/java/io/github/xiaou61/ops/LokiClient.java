package io.github.xiaou61.ops;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 极薄的 Loki 查询客户端（WORK-042）。
 *
 * <p>只做一件事：`GET /loki/api/v1/query_range` 并把 `values` 解成「时间 + 日志行」。
 * 与 {@link PrometheusClient} 同一套失败语义：**任何失败都返回空列表**，调用方据此
 * 把"日志不可达"与"没有日志"分开呈现（前者写进证据，后者留空）。
 */
class LokiClient {


    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    LokiClient(String baseUrl, long timeoutMs) {
        this.baseUrl = OpsHttp.trimTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = OpsHttp.client(timeout);
    }

    List<LogLine> queryRange(String query, Instant from, Instant to, int limit) {        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/loki/api/v1/query_range?query=" + encoded
                + "&start=" + from.getEpochSecond() + "000000000"
                + "&end=" + to.getEpochSecond() + "000000000"
                + "&limit=" + limit
                + "&direction=backward";
        Optional<JsonNode> root = OpsHttp.getJson(http, url, timeout);
        if (root.isEmpty() || !"success".equals(root.get().path("status").asText())) {
            return List.of();
        }
        List<LogLine> lines = new ArrayList<>();
        for (JsonNode stream : root.get().path("data").path("result")) {
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
    }

    record LogLine(Instant at, String line) {
    }

    /**
     * 即时指标查询（WORK-043）：用于“当前 5 分钟 ERROR 数 vs 上周同时段”。
     * 失败返回 null，调用方写“基线不足/无数据”，不把缺失当成 0。
     */
    Double scalar(String query, Instant at) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/loki/api/v1/query?query=" + encoded
                + "&time=" + at.getEpochSecond() + "000000000";
        Optional<JsonNode> root = OpsHttp.getJson(http, url, timeout);
        if (root.isEmpty() || !"success".equals(root.get().path("status").asText())) {
            return null;
        }
        JsonNode result = root.get().path("data").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return null;
        }
        String raw = result.get(0).path("value").path(1).asText();
        return raw.isBlank() ? null : Double.valueOf(raw);
    }
}
