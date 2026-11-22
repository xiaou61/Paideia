package io.github.xiaou61.ai.internal.provider;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.github.xiaou61.ai.internal.billing.BillingProperties;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 每次都校验出站地址的传输层。
 *
 * <p>为什么要在这一层做而不是只在校验用户填的 baseUrl 时做：模型客户端可能请求它自己拼出来
 * 的地址（拼路径、跟随重定向），只信任"用户填的那个串"是不够的。这里在**真正发出请求之前**
 * 拿到最终 URL 再校验一次——它是 SSRF 防线的第二道，也是唯一能看见最终地址的一道。
 *
 * <p>用装饰而不是自写传输实现：出站细节（连接池、超时、代理）交给 AgentScope 的默认实现，
 * 这里只加一道闸。
 */
@Component
public class GuardedHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(GuardedHttpTransport.class);

    private final UpstreamGuard guard;
    /** 平台上游要求的固定请求头；仅在请求目标 host 与平台 baseUrl 一致时才附带。 */
    private final Map<String, String> platformHeaders;
    private final String platformHost;
    /**
     * 禁止跟随重定向。
     *
     * <p>设计文档与 {@link UpstreamGuard} 都把"禁重定向"列为 SSRF 的缓解手段之一：允许跟随的话，
     * 一个公网地址可以 302 到内网，而第二道闸只看得到**用户填的那个地址**。
     * OkHttp 默认 `followRedirects=true`，所以必须显式关掉。
     *
     * <p><b>读取超时必须显式放大</b>：OkHttp 的 `readTimeout` 默认只有 10 秒，而 AgentScope 自己的
     * `HttpTransportConfig` 默认是 5 分钟。2026-10-21 实测：速递一次压缩 12 条候选跑了约 20 秒，
     * 在 10 秒处抛 `Failed to read response body: timeout`，整批静默降级——
     * **这个缺陷只在"非流式 + 大输出"的调用上出现**，单篇抽题（几秒）永远撞不到。
     * 这里取与 AgentScope 默认一致的 5 分钟，真正的时间上限由调用方的 `blockLast(…)` 决定。
     */
    private final OkHttpTransport delegate;

    /** 测试与不需要额外请求头的场景。 */
    public GuardedHttpTransport(UpstreamGuard guard) {
        this(guard, null);
    }

    @Autowired
    public GuardedHttpTransport(UpstreamGuard guard, BillingProperties billing) {
        this.guard = guard;
        Map<String, String> headers = new LinkedHashMap<>();
        String host = null;
        if (billing != null && billing.getPlatformCredential() != null) {
            BillingProperties.PlatformCredential credential = billing.getPlatformCredential();
            if (credential.getHeaders() != null && !credential.getHeaders().isEmpty()
                    && credential.getBaseUrl() != null) {
                headers.putAll(credential.getHeaders());
                host = hostOf(credential.getBaseUrl());
            }
        }
        this.platformHeaders = Map.copyOf(headers);
        this.platformHost = host;

        okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .readTimeout(java.time.Duration.ofMinutes(5))
                .writeTimeout(java.time.Duration.ofMinutes(1))
                .callTimeout(java.time.Duration.ofMinutes(6));
        if (!platformHeaders.isEmpty() && platformHost == null) {
            // fail-closed：解析不出平台 host 时宁可不装配请求头，也不要把会话/配额标识发给任意上游
            log.warn("ai.billing.platform-credential.headers dropped: base-url 解析不出 host（baseUrl={}）",
                    billing.getPlatformCredential().getBaseUrl());
        } else if (!platformHeaders.isEmpty()) {
            final String targetHost = platformHost;
            final Map<String, String> extra = platformHeaders;
            builder.addInterceptor(chain -> {
                okhttp3.Request request = chain.request();
                if (targetHost.equalsIgnoreCase(request.url().host())) {
                    okhttp3.Request.Builder mutated = request.newBuilder();
                    for (Map.Entry<String, String> entry : extra.entrySet()) {
                        if (request.header(entry.getKey()) == null) {
                            mutated.addHeader(entry.getKey(), entry.getValue());
                        }
                    }
                    request = mutated.build();
                }
                return chain.proceed(request);
            });
        }
        this.delegate = new OkHttpTransport(builder.build(), HttpTransportConfig.defaults());
    }

    private static String hostOf(String baseUrl) {
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        guard.assertAllowed(request.getUrl());
        return delegate.execute(request);
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        guard.assertAllowed(request.getUrl());
        return delegate.stream(request);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
