package io.github.xiaou61.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求确定追踪标识，写入 MDC、请求属性与响应头。
 *
 * <p><b>WORK-043 起优先用 OTel 的 trace id</b>：本过滤器排在 observation 过滤器之后
 * （{@code HIGHEST_PRECEDENCE + 2}），拿当前 span 的 trace id；这样响应头、日志与 Tempo
 * 里是同一条 trace，不再各生成一个互不相干的 id。没有 span 时（tracing 关闭或测试）
 * 沿用入站 {@code X-Trace-Id} 或生成一个，行为与 WORK-042 一致。
 *
 * <p>MDC 写入同一个 trace id；有 span 时由 Micrometer 的 scope 负责在请求结束时清理，
 * 本过滤器只在“没有 span”时 remove，免得把它的上下文提前删掉。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = "paideia.traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final String MDC_KEY = "traceId";
    private static final String SAFE_TRACE_ID = "[A-Za-z0-9_-]{1,64}";

    private final ObjectProvider<Tracer> tracerProvider;

    public TraceIdFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String spanTraceId = currentTraceId();
        boolean hasSpan = spanTraceId != null;
        String traceId = hasSpan ? spanTraceId : resolveTraceId(request);

        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (!hasSpan) {
                MDC.remove(MDC_KEY);
            }
        }
    }

    /** 当前 OTel span 的 trace id；没有 span、tracer 不可用或 id 非法时返回 null。 */
    private String currentTraceId() {
        try {
            Tracer tracer = tracerProvider.getIfAvailable();
            if (tracer == null) {
                return null;
            }
            Span span = tracer.currentSpan();
            if (span == null) {
                return null;
            }
            String traceId = span.context().traceId();
            return traceId != null && traceId.matches(SAFE_TRACE_ID) ? traceId : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming != null && incoming.matches(SAFE_TRACE_ID)) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
