package io.github.xiaou61.ops;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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


    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    PrometheusClient(String baseUrl, long timeoutMs) {
        this.baseUrl = OpsHttp.trimTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = OpsHttp.client(timeout);
    }

    /** 单个数值：取第一条序列。没有结果返回 {@code null}（调用方据此判 UNKNOWN）。 */
    Double scalar(String query) {
        List<Sample> samples = query(query);
        return samples.isEmpty() ? null : samples.getFirst().value();
    }

    /** 多条序列，每条带自己的标签。 */
    List<Sample> query(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        Optional<JsonNode> root = OpsHttp.getJson(http, baseUrl + "/api/v1/query?query=" + encoded, timeout);
        if (root.isEmpty() || !"success".equals(root.get().path("status").asText())) {
            return List.of();
        }
        List<Sample> samples = new ArrayList<>();
        for (JsonNode item : root.get().path("data").path("result")) {
            Map<String, String> labels = new LinkedHashMap<>();
            item.path("metric").properties().forEach(entry ->
                    labels.put(entry.getKey(), entry.getValue().asText()));
            // value 是 [时间戳, "字符串数值"]，字符串形式是 Prometheus 的规范
            String raw = item.path("value").path(1).asText();
            Double value = OpsHttp.parseDouble(raw);
            samples.add(new Sample(labels, value));
        }
        return samples;
    }

    record Sample(Map<String, String> labels, Double value) {
    }
}
