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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 极薄的 Tempo 查询客户端（WORK-043 P2）。
 *
 * <p>只做两件事：按 TraceQL 搜错误/慢 trace，按 id 取一条 trace 的最慢 span。
 * 失败一律返回空/空列表——证据里写“无匹配 trace（或 Tempo 不可达）”，不把缺失当成正常。
 */
class TempoClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ERROR_QUERY =
            "{ resource.service.name = \"paideia\" && status = error }";
    private static final String SLOW_QUERY =
            "{ resource.service.name = \"paideia\" && duration > 1s }";

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    TempoClient(String baseUrl, long timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    List<TraceSummary> search(Instant from, Instant to, int limit) {
        Map<String, TraceSummary> found = new LinkedHashMap<>();
        for (String query : List.of(ERROR_QUERY, SLOW_QUERY)) {
            for (TraceSummary summary : searchQuery(query, from, to, limit)) {
                found.putIfAbsent(summary.traceId(), summary);
            }
        }
        return found.values().stream().limit(Math.max(1, limit)).toList();
    }

    private List<TraceSummary> searchQuery(String query, Instant from, Instant to, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/api/search?q=" + encoded
                + "&start=" + from.getEpochSecond()
                + "&end=" + to.getEpochSecond()
                + "&limit=" + Math.max(1, limit);
        Optional<JsonNode> root = get(url);
        if (root.isEmpty()) {
            return List.of();
        }
        List<TraceSummary> traces = new ArrayList<>();
        for (JsonNode trace : root.get().path("traces")) {
            String id = trace.path("traceID").asText();
            if (id.isBlank()) {
                continue;
            }
            String name = trace.path("rootTraceName").asText("unknown");
            long durationMs = trace.path("durationMs").asLong(0);
            String rawStart = trace.path("startTimeUnixNano").asText("");
            Instant start;
            try {
                start = rawStart.isBlank() ? Instant.EPOCH : Instant.ofEpochSecond(0, Long.parseLong(rawStart));
            } catch (NumberFormatException e) {
                start = Instant.EPOCH;
            }
            traces.add(new TraceSummary(id, name, durationMs, ERROR_QUERY.equals(query), start));
        }
        return traces;
    }

    /** 取一条 trace 的最慢 span；失败返回空。 */
    Optional<TraceDetail> trace(String traceId) {
        Optional<JsonNode> root = get(baseUrl + "/api/traces/" + URLEncoder.encode(traceId, StandardCharsets.UTF_8));
        if (root.isEmpty()) {
            return Optional.empty();
        }
        List<Span> spans = new ArrayList<>();
        for (JsonNode batch : root.get().path("batches")) {
            for (JsonNode scope : batch.path("scopeSpans")) {
                for (JsonNode span : scope.path("spans")) {
                    String startRaw = span.path("startTimeUnixNano").asText("");
                    String endRaw = span.path("endTimeUnixNano").asText("");
                    if (startRaw.isBlank() || endRaw.isBlank()) {
                        continue;
                    }
                    long start;
                    long end;
                    try {
                        start = Long.parseLong(startRaw);
                        end = Long.parseLong(endRaw);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    spans.add(new Span(
                            span.path("name").asText("unknown"),
                            Math.max(0, (end - start) / 1_000_000),
                            // OTLP 的 proto3 JSON **省略零值**：status.code=UNSET(0) 时整个字段不出现，
                            // 读成空串会让证据里出现 "status=" 这种没法解读的片段（生产演练时见过）。
                            span.path("status").path("code").asText("unset"),
                            attributes(span.path("attributes"))));
                }
            }
        }
        return spans.isEmpty() ? Optional.empty() : Optional.of(new TraceDetail(traceId, spans));
    }

    private static Map<String, String> attributes(JsonNode attributes) {
        Map<String, String> all = new LinkedHashMap<>();
        for (JsonNode attribute : attributes) {
            String key = attribute.path("key").asText();
            if (!TempoTraceRedactor.allowed(key)) {
                continue;
            }
            JsonNode value = attribute.path("value");
            String text = value.path("stringValue").asText("");
            if (text.isBlank()) {
                text = value.path("intValue").asText("");
            }
            if (text.isBlank()) {
                text = value.path("boolValue").asText("");
            }
            if (!text.isBlank()) {
                all.put(key, text);
            }
        }
        return TempoTraceRedactor.filter(all);
    }

    private Optional<JsonNode> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(JSON.readTree(response.body()));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    record TraceSummary(String traceId, String rootName, long durationMs, boolean error, Instant start) {
    }

    record TraceDetail(String traceId, List<Span> spans) {

        Span slowest() {
            return spans.stream().max(java.util.Comparator.comparingLong(Span::durationMs)).orElse(null);
        }
    }

    record Span(String name, long durationMs, String status, Map<String, String> attributes) {
    }
}
