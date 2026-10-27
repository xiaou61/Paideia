package io.github.xiaou61.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * 三个指标的名字与标签（WORK-044 TASK-005）。
 *
 * <p>钉住名字是因为**名字一改，Grafana 面板就静默变空**——不报错，只是没数据。
 */
class McpMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final McpMetrics metrics = new McpMetrics(registry);

    @Test
    void callCounterUsesToolsetAndResult() {
        metrics.calls("note", McpMetrics.OK);

        assertThat(registry.get("paideia.mcp.tool.calls")
                .tag("toolset", "note").tag("result", "ok").counter().count()).isEqualTo(1.0);
    }

    @Test
    void deniedCounterUsesReason() {
        metrics.denied("quota");

        assertThat(registry.get("paideia.mcp.denied").tag("reason", "quota").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void durationIsATimerPerTool() {
        metrics.duration("note_list", 0.25);
        metrics.duration("note_list", 0.75);

        var timer = registry.get("paideia.mcp.tool.duration").tag("tool", "note_list").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1.0);
    }
}
