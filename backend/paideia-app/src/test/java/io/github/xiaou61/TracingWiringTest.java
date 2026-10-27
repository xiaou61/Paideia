package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 链路装配（WORK-043 AC-007/AC-008）。
 *
 * <p>只证明 Spring 上下文里**同时**有 Tracer 与 SpanExporter：缺前者 span 不会产生，
 * 缺后者 span 会生成但不导出（2026-10-27 在 Tempo 上实测到的空结果）。
 * 真正的端到端导出由生产 `X-Trace-Id` ↔ Tempo 验证。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "management.tracing.enabled=true",
        "management.tracing.export.enabled=true",
        "management.tracing.export.otlp.enabled=true",
        "management.otlp.tracing.export.enabled=true",
        "management.otlp.tracing.endpoint=http://127.0.0.1:1/v1/traces"
})
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class TracingWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("Tracer 与 SpanExporter 都在上下文中")
    void tracerAndExporterAreWired() {
        assertThat(context.getBeansOfType(Tracer.class)).isNotEmpty();
        assertThat(context.getBeansOfType(SpanExporter.class)).isNotEmpty();
    }
}
