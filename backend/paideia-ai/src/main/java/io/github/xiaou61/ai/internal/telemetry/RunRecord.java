package io.github.xiaou61.ai.internal.telemetry;

import io.github.xiaou61.ai.RunSpec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮的**不可变快照**（WORK-022）。
 *
 * <p>约束：**必须自包含**。它跨线程交给写入队列，那时原始对象可能已经被改过了；
 * 任何"回调时才去取"的懒加载都会读到错的值。
 *
 * <p>`attrs` 用不可变副本：写入线程与业务线程不再共享可变状态。
 */
public record RunRecord(
        String traceId,
        long userId,
        Long conversationId,
        String kind,
        String harness,
        String model,
        String promptVersion,
        Instant startedAt,
        Instant endedAt,
        String status,
        String errorType,
        String failureKind,
        Integer queueMs,
        Integer durationMs,
        Integer ttftMs,
        int llmRounds,
        int toolCalls,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int credits,
        Map<String, Object> attrs,
        List<SpanRecord> spans) {

    public RunRecord {
        attrs = attrs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attrs));
        spans = spans == null ? List.of() : List.copyOf(spans);
    }

    /** 从 spec 起一个只填了"开始时就知道"的那些字段的快照。 */
    static RunRecord running(String traceId, RunSpec spec, Instant startedAt) {
        return new RunRecord(traceId, spec.userId(), spec.conversationId(), spec.kind(),
                spec.harness(), spec.model(), spec.promptVersion(),
                startedAt, null, "running", null, null,
                null, null, null, 0, 0, 0, 0, 0, 0,
                Map.of(), List.of());
    }

    boolean isRunning() {
        return "running".equals(status);
    }
}
