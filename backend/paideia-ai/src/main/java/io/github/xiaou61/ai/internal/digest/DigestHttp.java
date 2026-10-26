package io.github.xiaou61.ai.internal.digest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 抓取用的 HTTP 薄封装（WORK-027）。
 *
 * <p>存在的理由只有一个：**把三条 SSRF 边界集中在一处**，不让每个源各自实现一遍
 * （各自实现必然有一处漏掉）。三条见 {@link DigestSource} 的类注释。
 *
 * <p><b>为什么不用 AgentScope 的 {@code GuardedHttpTransport}</b>：那是模型客户端的出站闸，
 * 拦的是"上游 baseUrl 被改"这类风险；抓 RSS 是 JDK HttpClient，与它不在一层。
 * WORK-025 的 `NowcoderEssaySource` 就是直接用 JDK 客户端，本项照那条路。
 *
 * <p>**默认重定向一律不跟随**：源地址来自配置（不是用户输入），风险低于模型 baseUrl，
 * 但仍不该让一次 302 把请求带去别处。宁可这条源失败也不悄悄换主机。
 * GitHub 的 star 历史端点会把 `/repos/{owner}/{repo}` 301 到 `/repositories/{id}`，
 * 这类同 host 重定向由 {@link #getTextFollowingSameHostRedirects} 显式处理，并校验 host。
 *
 * <p>不是 final：单测要继承它来替换 `getTextWithHeaders`，验证"主通路失败是否真的走兜底"
 * （2026-10-21：桩掉得靠子类，否则只能起真 HTTP）。
 */
class DigestHttp {

    private final HttpClient client;
    private final Duration timeout;

    DigestHttp(int timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * GET 一段文本。
     *
     * @throws DigestSource.DigestSourceException 地址非法、非 2xx、超时或网络失败
     */
    String getText(String url, String userAgent) {
        return getTextWithHeaders(url, userAgent, Map.of());
    }

    /**
     * GET 一段文本，带额外请求头（GitHub Search API 要 accept 与 authorization）。
     *
     * @throws DigestSource.DigestSourceException 地址非法、非 2xx、超时或网络失败
     */
    String getTextWithHeaders(String url, String userAgent, Map<String, String> headers) {
        HttpResponse<String> response = send(uri(url), userAgent, headers);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // 3xx 也会落到这里：不跟随重定向，直接按失败处理
            throw new DigestSource.DigestSourceException("HTTP " + status + "：" + url);
        }
        return response.body();
    }

    /**
     * GET 一段文本，只跟随**同一 host** 的 3xx 重定向。
     *
     * <p>**为什么需要它**：GitHub 部分仓库的 star 历史端点会 301 到
     * `/repositories/{id}/...`；不跟随就把这类仓库当失败。跨 host 一律拒绝——
     * 请求可能带 Authorization，不能被引到别处。
     *
     * @throws DigestSource.DigestSourceException 地址非法、跨 host、重定向过多、非 2xx、超时或网络失败
     */
    String getTextFollowingSameHostRedirects(String url, String userAgent,
                                             Map<String, String> headers, String allowedHost,
                                             int maxHops) {
        URI current = uri(url);
        for (int hop = 0; hop <= maxHops; hop++) {
            HttpResponse<String> response = send(current, userAgent, headers);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response.body();
            }
            if (status == 301 || status == 302 || status == 307 || status == 308) {
                String location = response.headers().firstValue("location").orElse("");
                if (location.isBlank()) {
                    throw new DigestSource.DigestSourceException("重定向缺少 Location：" + current);
                }
                URI next = current.resolve(location);
                String nextHost = next.getHost();
                if (nextHost == null || !allowedHost.equalsIgnoreCase(nextHost)) {
                    throw new DigestSource.DigestSourceException("拒绝跨 host 重定向：" + nextHost);
                }
                String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    throw new DigestSource.DigestSourceException("只允许 http/https：" + next);
                }
                current = next;
                continue;
            }
            throw new DigestSource.DigestSourceException("HTTP " + status + "：" + current);
        }
        throw new DigestSource.DigestSourceException("重定向次数过多：" + url);
    }

    private URI uri(String url) {
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new DigestSource.DigestSourceException("地址非法：" + url, exception);
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new DigestSource.DigestSourceException("只允许 http/https：" + url);
        }
        return parsed;
    }

    private HttpResponse<String> send(URI uri, String userAgent, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("user-agent", userAgent)
                .header("accept", "*/*")
                .GET();
        headers.forEach(builder::header);
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DigestSource.DigestSourceException("抓取被中断：" + uri, exception);
        } catch (java.io.IOException exception) {
            throw new DigestSource.DigestSourceException(
                    "抓取失败：" + uri + "（" + exception.getClass().getSimpleName() + "）", exception);
        }
    }
}
