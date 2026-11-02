package io.github.xiaou61.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * 运维 HTTP 客户端的三家共用件（WORK-062 收敛）。
 *
 * <p>Prometheus / Loki / Tempo 三个客户端原来是同一条样板各写一遍：尾斜杠归一、
 * 建带连接超时的 {@link HttpClient}、GET 一个 JSON、失败还原中断标志。这里只留机制；
 * **各家的失败返回类型与 `"status":"success"` 校验留在各自客户端**——Loki 的 query_range
 * 与 Tempo 都没有 success 字段，统一会收紧它们的失败语义。
 *
 * <p>包内 package-private：只服务 ops。
 */
final class OpsHttp {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpsHttp() {
    }

    /** 建客户端：连接超时跟着请求超时走（逐次请求还各自带 timeout，超时是逐次生效的）。 */
    static HttpClient client(Duration timeout) {
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 配置里基地址写没写尾斜杠都行——统一在这里归一，拼接处不必各自判。 */
    static String trimTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * GET 一个 JSON：**只有 200 才解析**；非 200、解析失败、IO/中断一律返回 empty，绝不抛。
     *
     * <p>中断也要还原标志位，否则线程池里的这条线程会带着中断状态继续跑。
     */
    static Optional<JsonNode> getJson(HttpClient http, String url, Duration timeout) {
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
}