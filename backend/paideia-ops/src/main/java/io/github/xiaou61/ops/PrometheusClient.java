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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极薄的 Prometheus 即时查询客户端（WORK-028）。
 *
 * <p>只做一件事：`GET /api/v1/query` 并把 vector 结果解成「标签 → 数值」。
 * 不引 Prometheus 客户端库——它的价值在写指标与长连接，这里只要读一次。
 *
 * <p><b>超时是逐次请求的</b>：一次查询卡住不拖累其他查询，也不让整页超时。
 * 失败一律返回空列表而不是抛异常——调用方据此把该项判成"未知"，而不是让整页 500。
 */
class PrometheusClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    PrometheusClient(String baseUrl, long timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 单个数值：取第一条序列。没有结果返回 {@code null}（调用方据此判 UNKNOWN）。 */
    Double scalar(String query) {
        List<Sample> samples = query(query);
        return samples.isEmpty() ? null : samples.getFirst().value();
    }

    /** 多条序列，每条带自己的标签。 */
    List<Sample> query(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/query?query=" + encoded))
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
            List<Sample> samples = new ArrayList<>();
            for (JsonNode item : root.path("data").path("result")) {
                Map<String, String> labels = new LinkedHashMap<>();
                item.path("metric").properties().forEach(entry ->
                        labels.put(entry.getKey(), entry.getValue().asText()));
                // value 是 [时间戳, "字符串数值"]，字符串形式是 Prometheus 的规范
                String raw = item.path("value").path(1).asText();
                Double value = raw.isBlank() ? null : Double.valueOf(raw);
                samples.add(new Sample(labels, value));
            }
            return samples;
        } catch (IOException | InterruptedException | RuntimeException e) {
            // 中断也要还原标志位，否则线程池里的这条线程会带着中断状态继续跑
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    record Sample(Map<String, String> labels, Double value) {
    }
}
