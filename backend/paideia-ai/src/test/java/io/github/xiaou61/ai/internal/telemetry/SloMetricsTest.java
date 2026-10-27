package io.github.xiaou61.ai.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SLO 指标的基数约束（WORK-043 TASK-017）。
 *
 * <p>为什么单开一条：这两个指标进的是 Prometheus 的 label，而标签值的每一种组合都是一条时间序列。
 * 把 userId、conversationId 或错误消息塞进标签，长跑之后就是基数爆炸——Prometheus 先变慢、
 * 再 OOM，且图上完全看不出来是谁干的。所以这里把"只允许哪些标签键"钉成断言。
 */
class SloMetricsTest {

    @Test
    @DisplayName("AI 运行指标的标签只有 kind 与 outcome，缺失 kind 归到 unknown")
    void labelsAreLowCardinality() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RunWriteQueue queue = new RunWriteQueue(payload -> { }, new TelemetryProperties());
        RunRecorder recorder = new DefaultRunRecorder(queue, new TelemetryProperties(), metrics);

        // kind 是自由字符串（RunKind 只是常量表），空值必须收敛到一个固定桶
        try (RunRecorder.RunScope run = recorder.begin(RunSpec.of(null, 1L))) {
            run.finish(RunOutcome.OK, null);
        }

        Counter counter = metrics.get("paideia.ai.runs")
                .tag("kind", "unknown").tag("outcome", "ok").counter();
        assertThat(counter.count()).isEqualTo(1);
        assertThat(counter.getId().getTags()).hasSize(2);

        Timer timer = metrics.get("paideia.ai.run.duration").tag("kind", "unknown").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTags()).hasSize(1);
    }
}
