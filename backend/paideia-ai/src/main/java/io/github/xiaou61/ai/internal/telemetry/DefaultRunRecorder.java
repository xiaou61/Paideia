package io.github.xiaou61.ai.internal.telemetry;

import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.github.xiaou61.ai.SpanSpec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 采集器的默认实现（WORK-022 T02）。
 *
 * <p>它只做三件事：生成 trace id、在内存里攒 {@link RunRecord}、把快照投给写队列。
 * **不碰数据库、不碰网络**——那是 sink 的事。
 *
 * <p>幂等靠 {@link AtomicBoolean}：`finish` 与 `close` 都会走到同一处，只生效一次。
 * 这一点很关键——`onCompleted` 里先结算再往 SSE 写，写事件可能抛；重叠调用是常态。
 */
final class DefaultRunRecorder implements RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(DefaultRunRecorder.class);

    /**
     * 只接受这几个键，见 design「attrs 的键」。
     *
     * <p>白名单是**刻意的**：不拦的话总有一天有人把提示词或用户输入整段塞进来。
     * `toolArgs` / `toolResult` 是 WORK-030 阶段 B 加的，**调用方负责截断**——
     * 采集器不替它判长度，因为"截多长"是业务判断（1000 / 500 字符），不是采集器的职责。
     */
    private static final Set<String> ALLOWED_ATTRS = Set.of(
            "httpTraceId", "historyMsgs", "memoryHits", "entryChars",
            "learningSummaryChars", "queued", "reqChars", "respChars",
            "toolArgs", "toolResult");

    private final RunWriteQueue queue;
    private final TelemetryProperties properties;
    private final MeterRegistry meterRegistry;

    DefaultRunRecorder(RunWriteQueue queue, TelemetryProperties properties,
                       MeterRegistry meterRegistry) {
        this.queue = queue;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public RunScope begin(RunSpec spec) {
        String traceId = newTraceId();
        RunScopeImpl scope = new RunScopeImpl(traceId, spec, queue, properties, meterRegistry);
        // **立刻投一条 running**：进程崩溃 / 线程卡死的那一轮必须留得下来，
        // 而那种行恰好是最该查的。只在结束时写的话，这三类故障一行都不会有。
        queue.offer(new RunPayload.RunBegin(scope.runningSnapshot()));
        return scope;
    }

    /** 32 位十六进制，与 TraceIdFilter 的生成写法一致（那个是为了让日志与响应头同类）。 */
    static String newTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /** 一轮的可变状态机。所有方法都是内存操作。 */
    private static final class RunScopeImpl implements RunScope {

        private final String traceId;
        private final RunSpec spec;
        private final RunWriteQueue queue;
        private final TelemetryProperties properties;
        private final MeterRegistry meterRegistry;
        private final Instant startedAt = Instant.now();
        private final Map<String, Object> attrs = new LinkedHashMap<>();
        private final List<SpanRecord> spans = new ArrayList<>();
        private final List<PayloadRecord> payloads = new ArrayList<>();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicInteger tokens = new AtomicInteger();
        private final AtomicInteger llmRounds = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private int inputTokens;
        private int outputTokens;
        private int cacheReadTokens;
        private volatile Integer queueMs;
        private volatile Integer ttftMs;
        private volatile int credits;

        RunScopeImpl(String traceId, RunSpec spec, RunWriteQueue queue, TelemetryProperties properties,
                     MeterRegistry meterRegistry) {
            this.traceId = traceId;
            this.spec = spec;
            this.queue = queue;
            this.properties = properties;
            this.meterRegistry = meterRegistry;
        }

        RunRecord runningSnapshot() {
            return RunRecord.running(traceId, spec, startedAt);
        }

        @Override
        public StepScope step(SpanSpec spec) {
            return new StepScopeImpl(this, spec);
        }

        @Override
        public void attribute(String key, Object value) {
            if (key == null || !ALLOWED_ATTRS.contains(key)) {
                log.debug("ai.telemetry.attr-rejected key={}", key);
                return;
            }
            if (value != null) {
                attrs.put(key, value);
            }
        }

        @Override
        public synchronized void tokens(int input, int output, int cacheRead) {
            inputTokens += input;
            outputTokens += output;
            cacheReadTokens += cacheRead;
            tokens.incrementAndGet();
        }

        /** 真正开始处理的时刻。排队时间 = 它与 startedAt 的差。 */
        @Override
        public void markStarted() {
            if (queueMs == null) {
                queueMs = (int) Duration.between(startedAt, Instant.now()).toMillis();
            }
        }

        /** 第一个正文增量出现的时刻；只记第一次。 */
        @Override
        public void markTtft() {
            if (ttftMs == null) {
                ttftMs = (int) Duration.between(startedAt, Instant.now()).toMillis();
            }
        }

        @Override
        public void addCredits(int charged) {
            this.credits = charged;
        }

        /**
         * 收一份完整载荷。
         *
         * <p>**超上限在这里判，不在调用方**：调用方只负责把内容给出来，存不存得下是采集器的事。
         * 超限时**留占位而不是丢弃**——静默丢会让"这一轮为什么没有请求体"变成新的谜（REQ-066）。
         */
        void addPayload(int seq, String requestBody, String responseBody) {
            int reqBytes = byteLength(requestBody);
            int respBytes = byteLength(responseBody);
            boolean truncated = reqBytes > properties.getPayloadMaxBytes()
                    || respBytes > properties.getPayloadMaxBytes();
            synchronized (payloads) {
                payloads.add(new PayloadRecord(traceId, seq,
                        truncated ? null : requestBody,
                        truncated ? null : responseBody,
                        reqBytes, respBytes, truncated, Instant.now()));
            }
        }

        private static int byteLength(String value) {
            return value == null ? 0 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }

        void addSpan(SpanRecord span) {
            synchronized (spans) {
                spans.add(span);
            }
            if (SpanSpec.KIND_LLM.equals(span.kind())) {
                llmRounds.incrementAndGet();
            } else if (SpanSpec.KIND_TOOL.equals(span.kind())) {
                toolCalls.incrementAndGet();
            }
        }

        @Override
        public String traceId() {
            return traceId;
        }

        @Override
        public void finish(RunOutcome outcome, String errorType) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            List<PayloadRecord> payloadCopy;
            synchronized (payloads) {
                payloadCopy = List.copyOf(payloads);
            }
            RunRecord record = snapshot(outcome.key(), errorType);
            String kind = record.kind() == null || record.kind().isBlank() ? "unknown" : record.kind();
            meterRegistry.counter("paideia.ai.runs", "kind", kind, "outcome", outcome.key()).increment();
            if (record.durationMs() != null) {
                meterRegistry.timer("paideia.ai.run.duration", "kind", kind)
                        .record(Duration.ofMillis(record.durationMs()));
            }
            queue.offer(new RunPayload.RunFinish(record, payloadCopy));
        }

        /** 没 finish 就关：说明调用方走了异常路径。补一条 interrupted，绝不留 running 僵尸。 */
        @Override
        public void close() {
            finish(RunOutcome.INTERRUPTED, "unclosed");
        }

        private RunRecord snapshot(String status, String errorType) {
            Instant endedAt = Instant.now();
            int millis = (int) Duration.between(startedAt, endedAt).toMillis();
            List<SpanRecord> copy;
            synchronized (spans) {
                copy = List.copyOf(spans);
            }
            return new RunRecord(traceId, spec.userId(), spec.conversationId(), spec.kind(),
                    spec.harness(), spec.model(), spec.promptVersion(),
                    startedAt, endedAt, status, errorType,
                    queueMs, millis, ttftMs,
                    llmRounds.get(), toolCalls.get(),
                    inputTokens, outputTokens, cacheReadTokens, credits,
                    attrs, copy);
        }
    }

    /** 一步的可变状态。 */
    private static final class StepScopeImpl implements StepScope {

        private final RunScopeImpl run;
        private final SpanSpec spec;
        private final Instant startedAt = Instant.now();
        private final Map<String, Object> attrs = new LinkedHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final int seq;
        private String status = "ok";
        private String errorType;
        private int inputTokens;
        private int outputTokens;
        private int cacheReadTokens;
        private Integer ttftMs;
        private String requestBody;
        private String responseBody;

        StepScopeImpl(RunScopeImpl run, SpanSpec spec) {
            this.run = run;
            this.spec = spec;
            synchronized (run.spans) {
                this.seq = run.spans.size() + 1;
            }
        }

        @Override
        public void attribute(String key, Object value) {
            if (key == null || !ALLOWED_ATTRS.contains(key)) {
                return;
            }
            if (value != null) {
                attrs.put(key, value);
            }
        }

        @Override
        public void tokens(int input, int output, int cacheRead) {
            inputTokens += input;
            outputTokens += output;
            cacheReadTokens += cacheRead;
        }

        @Override
        public void ttftMs(int millis) {
            this.ttftMs = millis;
        }

        @Override
        public void payload(String requestBody, String responseBody) {
            this.requestBody = requestBody;
            this.responseBody = responseBody;
        }

        @Override
        public void succeeded() {
            this.status = "ok";
            this.errorType = null;
        }

        @Override
        public void failed(String errorType) {
            this.status = "failed";
            this.errorType = errorType;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            int millis = (int) Duration.between(startedAt, Instant.now()).toMillis();
            run.addSpan(new SpanRecord(run.traceId(), spec.parentSeq(), seq, spec.kind(), spec.name(),
                    status, errorType, startedAt, millis, ttftMs,
                    inputTokens, outputTokens, cacheReadTokens, attrs));
            // 完整载荷单独走一路：它可能几十 KB，不能跟着步骤一起进 attrs
            if (requestBody != null || responseBody != null) {
                run.addPayload(seq, requestBody, responseBody);
            }
        }
    }
}
