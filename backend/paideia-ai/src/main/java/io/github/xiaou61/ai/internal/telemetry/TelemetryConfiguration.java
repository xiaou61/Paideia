package io.github.xiaou61.ai.internal.telemetry;

import io.github.xiaou61.ai.RunRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 采集的装配（WORK-022 T03）。
 *
 * <p><b>按开关选实现</b>：开着装配 {@link DefaultRunRecorder}，关着装配 {@link NoopRunRecorder}。
 * 业务代码只依赖 {@link RunRecorder} 接口，两种情形下埋点代码一字不改。
 *
 * <p>关掉时打一条 WARN："统计已关闭"这种事不该静默——否则排查时会以为数据在写、只是查不到。
 */
@Configuration
@EnableConfigurationProperties(TelemetryProperties.class)
class TelemetryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConfiguration.class);

    @Bean
    RunRecorder runRecorder(RunWriteQueue queue, TelemetryProperties properties,
                            MeterRegistry meterRegistry) {
        if (!properties.isEnabled()) {
            log.warn("ai.telemetry.disabled：AI 调用统计已关闭，不产生任何记录（历史数据不受影响）");
            return new NoopRunRecorder();
        }
        return new DefaultRunRecorder(queue, properties, meterRegistry);
    }
}
