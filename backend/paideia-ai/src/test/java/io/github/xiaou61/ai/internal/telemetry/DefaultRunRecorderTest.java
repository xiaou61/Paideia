package io.github.xiaou61.ai.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.RunKind;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.github.xiaou61.ai.SpanSpec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采集器与队列的单元测试（WORK-022 T02/T03）。
 *
 * <p>这一层的三条关键行为无法从"跑一次对话"里看出来：
 * ① finish 与 close 都被调用时只写一条终态（真实路径上它们是重叠的）；
 * ② 没 finish 就关会补 interrupted（异常路径）；
 * ③ 队列满与 sink 抛异常都不外溢（统计系统不许影响回答）。
 */
class DefaultRunRecorderTest {

    /**
     * 同步队列替身：不启线程、不碰数据库，把"投递了什么"变成可断言的列表。
     *
     * <p>用同步而不是真实线程，是为了让"只写一条""满时不阻塞"这类断言**确定**而不是靠等待。
     * 真正的线程行为由 {@code sinkFailureDoesNotBreakLoop} 那条覆盖。
     */
    private static final class TestQueue extends RunWriteQueue {
        private final List<RunPayload> accepted = new ArrayList<>();
        private boolean full;

        TestQueue(TelemetryProperties properties) {
            super(payload -> { }, properties);
        }

        void setFull(boolean full) {
            this.full = full;
        }

        @Override
        boolean offer(RunPayload payload) {
            if (full) {
                return false;
            }
            accepted.add(payload);
            return true;
        }

        List<RunPayload> accepted() {
            return accepted;
        }
    }

    private static TelemetryProperties properties() {
        return new TelemetryProperties();
    }

    @Test
    @DisplayName("finish 与 close 都调用时只写一条终态")
    void finishAndCloseWriteOnce() {
        TestQueue queue = new TestQueue(properties());
        RunRecorder recorder = new DefaultRunRecorder(queue, new TelemetryProperties(), new SimpleMeterRegistry());
        RunRecorder.RunScope run = recorder.begin(RunSpec.of(RunKind.CHAT, 7L));
        run.finish(RunOutcome.OK, null);
        run.close();
        run.finish(RunOutcome.FAILED, "x");

        long finishes = queue.accepted().stream().filter(RunPayload.RunFinish.class::isInstance).count();
        assertThat(finishes).isEqualTo(1);
        RunPayload.RunFinish finish = (RunPayload.RunFinish) queue.accepted().stream()
                .filter(RunPayload.RunFinish.class::isInstance).findFirst().orElseThrow();
        assertThat(finish.run().status()).isEqualTo("ok");
    }

    @Test
    @DisplayName("只 close 不 finish 记为 interrupted")
    void closeWithoutFinishRecordsInterrupted() {
        TestQueue queue = new TestQueue(properties());
        RunRecorder recorder = new DefaultRunRecorder(queue, new TelemetryProperties(), new SimpleMeterRegistry());
        recorder.begin(RunSpec.of(RunKind.CHAT, 7L)).close();

        RunPayload.RunFinish finish = (RunPayload.RunFinish) queue.accepted().stream()
                .filter(RunPayload.RunFinish.class::isInstance).findFirst().orElseThrow();
        assertThat(finish.run().status()).isEqualTo(RunOutcome.INTERRUPTED.key());
        assertThat(finish.run().errorType()).isEqualTo("unclosed");
    }

    @Test
    @DisplayName("trace id 由服务端生成，与客户端传入的 httpTraceId 无关")
    void traceIdIsServerGenerated() {
        TestQueue queue = new TestQueue(properties());
        RunRecorder recorder = new DefaultRunRecorder(queue, new TelemetryProperties(), new SimpleMeterRegistry());
        RunRecorder.RunScope run = recorder.begin(RunSpec.of(RunKind.CHAT, 7L));
        run.attribute("httpTraceId", "client-supplied");
        run.finish(RunOutcome.OK, null);

        String traceId = ((RunPayload.RunBegin) queue.accepted().get(0)).run().traceId();
        assertThat(traceId).hasSize(32).doesNotContain("-");
        assertThat(traceId).isNotEqualTo("client-supplied");
        RunPayload.RunFinish finish = (RunPayload.RunFinish) queue.accepted().get(1);
        assertThat(finish.run().attrs()).containsEntry("httpTraceId", "client-supplied");
        assertThat(finish.run().traceId()).isEqualTo(traceId);
    }

    @Test
    @DisplayName("未知 attrs 键被忽略（不许把提示词塞进来）")
    void unknownAttributeKeyIgnored() {
        TestQueue queue = new TestQueue(properties());
        RunRecorder recorder = new DefaultRunRecorder(queue, new TelemetryProperties(), new SimpleMeterRegistry());
        RunRecorder.RunScope run = recorder.begin(RunSpec.of(RunKind.CHAT, 7L));
        run.attribute("prompt", "用户的完整提示词");
        run.attribute("historyMsgs", 3);
        run.finish(RunOutcome.OK, null);

        RunPayload.RunFinish finish = (RunPayload.RunFinish) queue.accepted().get(1);
        assertThat(finish.run().attrs()).containsEntry("historyMsgs", 3);
        assertThat(finish.run().attrs()).doesNotContainKey("prompt");
    }

    @Test
    @DisplayName("逐轮 token 累加到轮次总量，步骤各自保留自己的用量")
    void perRoundTokensAccumulate() {
        TestQueue queue = new TestQueue(properties());
        RunRecorder recorder = new DefaultRunRecorder(queue, new TelemetryProperties(), new SimpleMeterRegistry());
        RunRecorder.RunScope run = recorder.begin(RunSpec.of(RunKind.CHAT, 7L));
        try (RunRecorder.StepScope step = run.step(SpanSpec.llm(1))) {
            step.tokens(10, 5, 0);
            step.succeeded();
        }
        run.tokens(10, 5, 0);
        try (RunRecorder.StepScope step = run.step(SpanSpec.llm(2))) {
            step.tokens(20, 8, 0);
            step.succeeded();
        }
        run.tokens(20, 8, 0);
        run.finish(RunOutcome.OK, null);

        RunPayload.RunFinish finish = (RunPayload.RunFinish) queue.accepted().get(1);
        assertThat(finish.run().inputTokens()).isEqualTo(30);
        assertThat(finish.run().outputTokens()).isEqualTo(13);
        assertThat(finish.run().llmRounds()).isEqualTo(2);
        assertThat(finish.run().spans()).hasSize(2);
        assertThat(finish.run().spans().get(0).inputTokens()).isEqualTo(10);
        assertThat(finish.run().spans().get(1).inputTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("失败的工具步骤记为 failed，不冒充成功")
    void failedToolStepIsMarkedFailed() {
        TestQueue queue = new TestQueue(properties());
        RunRecorder recorder = new DefaultRunRecorder(queue, new TelemetryProperties(), new SimpleMeterRegistry());
        RunRecorder.RunScope run = recorder.begin(RunSpec.of(RunKind.CHAT, 7L));
        try (RunRecorder.StepScope step = run.step(SpanSpec.tool("knowledge_search"))) {
            step.failed("tool_error");
        }
        run.finish(RunOutcome.OK, null);

        RunPayload.RunFinish finish = (RunPayload.RunFinish) queue.accepted().get(1);
        SpanRecord span = finish.run().spans().get(0);
        assertThat(span.status()).isEqualTo("failed");
        assertThat(span.errorType()).isEqualTo("tool_error");
        assertThat(finish.run().toolCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("队列满时不阻塞、不抛，只计数")
    void fullQueueDropsWithoutBlocking() {
        TestQueue queue = new TestQueue(properties());
        queue.setFull(true);
        RunRecorder recorder = new DefaultRunRecorder(queue, new TelemetryProperties(), new SimpleMeterRegistry());

        long started = System.nanoTime();
        RunRecorder.RunScope run = recorder.begin(RunSpec.of(RunKind.CHAT, 7L));
        run.finish(RunOutcome.OK, null);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(queue.accepted()).isEmpty();
        // 满队列不该让调用方等：100ms 是"明显卡住"的下限
        assertThat(elapsedMs).isLessThan(100);
    }

    @Test
    @DisplayName("sink 抛异常不影响后续任务，只累加失败计数")
    void sinkFailureDoesNotBreakLoop() {
        TelemetryProperties properties = properties();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        RunSink exploding = payload -> {
            calls.incrementAndGet();
            throw new IllegalStateException("db down");
        };
        RunWriteQueue queue = new RunWriteQueue(exploding, properties);
        // 不启线程，直接走写路径：这里验的是"一次失败不该中断"
        assertThat(queue.writeFailures()).isZero();
        queue.start();
        try {
            queue.offer(new RunPayload.RunBegin(RunRecord.running("t1", RunSpec.of(RunKind.CHAT, 1L),
                    java.time.Instant.now())));
            queue.offer(new RunPayload.RunBegin(RunRecord.running("t2", RunSpec.of(RunKind.CHAT, 1L),
                    java.time.Instant.now())));
            long deadline = System.currentTimeMillis() + 2000;
            while (calls.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            queue.stop();
        }
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        assertThat(queue.writeFailures()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("轮次结束发 AI 运行计数与耗时指标")
    void emitsRunMetrics() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        TestQueue queue = new TestQueue(properties());
        RunRecorder recorder = new DefaultRunRecorder(queue, properties(), metrics);

        try (RunRecorder.RunScope run = recorder.begin(RunSpec.of(RunKind.CHAT, 7L))) {
            run.finish(RunOutcome.OK, null);
        }

        assertThat(metrics.get("paideia.ai.runs")
                .tag("kind", RunKind.CHAT).tag("outcome", "ok").counter().count()).isEqualTo(1);
        assertThat(metrics.get("paideia.ai.run.duration")
                .tag("kind", RunKind.CHAT).timer().count()).isEqualTo(1);
    }
}
