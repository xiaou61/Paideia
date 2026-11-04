package io.github.xiaou61.ai.internal.telemetry;

import io.github.xiaou61.ai.RunOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 本项唯一落地的 sink：写 MySQL（WORK-022 T03）。
 *
 * <p>三次写：`RunBegin` → INSERT running；`RunFinish` → 采样判定 → UPDATE 终态 + 批量插步骤。
 *
 * <p><b>采样在 finish 时才判定</b>：begin 时还不知道结果，而"失败/超时永远保留"这条要求
 * 必须先知道结果。所以 running 行一定先写了，采样生效时删掉它。
 *
 * <p>**异常照抛**：由 {@link RunWriteQueue} 捕获并打 WARN。这里不吞，是因为吞掉之后
 * 队列那边就看不到"写入失败"这件事了。
 */
@Component
class MysqlRunSink implements RunSink {

    private static final Logger log = LoggerFactory.getLogger(MysqlRunSink.class);

    private final AiRunMapper runs;
    private final AiRunSpanMapper spans;
    private final AiRunPayloadMapper payloads;
    private final TelemetryProperties properties;
    private final java.util.Random random = new java.util.Random();

    MysqlRunSink(AiRunMapper runs, AiRunSpanMapper spans, AiRunPayloadMapper payloads,
                 TelemetryProperties properties) {
        this.runs = runs;
        this.spans = spans;
        this.payloads = payloads;
        this.properties = properties;
    }

    @Override
    public void accept(RunPayload payload) {
        if (payload instanceof RunPayload.RunBegin begin) {
            runs.insertRunning(toRow(begin.run()));
            return;
        }
        RunPayload.RunFinish finish = (RunPayload.RunFinish) payload;
        RunRecord run = finish.run();

        if (sampledOut(run)) {
            runs.deleteRunning(run.traceId());
            return;
        }

        runs.finish(toRow(run));
        if (!run.spans().isEmpty()) {
            spans.insertBatch(toSpanRows(run));
        }
        // 载荷在**步骤之后**写：它的 seq 要能对上刚落库的步骤。
        // 开关关掉时整段跳过（REQ-064），页面会显示「已停用」。
        if (properties.isPayloadEnabled() && !finish.payloads().isEmpty()) {
            payloads.insertBatch(toPayloadRows(finish.payloads()));
        }
    }

    /**
     * 是否丢弃这一轮。
     *
     * <p>失败、超时、中断**永远保留**——采样是为了省存量，不是为了把问题藏起来。
     */
    private boolean sampledOut(RunRecord run) {
        double sampling = properties.getSampling();
        if (sampling >= 1.0) {
            return false;
        }
        if (!RunOutcome.OK.key().equals(run.status())) {
            return false;
        }
        return random.nextDouble() >= sampling;
    }

    private AiRunMapper.AiRunRow toRow(RunRecord run) {
        AiRunMapper.AiRunRow row = new AiRunMapper.AiRunRow();
        row.setTraceId(run.traceId());
        row.setUserId(run.userId());
        row.setConversationId(run.conversationId());
        row.setKind(run.kind());
        row.setHarness(run.harness());
        row.setModel(run.model());
        row.setPromptVersion(run.promptVersion());
        row.setStatus(run.status());
        row.setErrorType(run.errorType());
        row.setFailureKind(run.failureKind());
        row.setStartedAt(run.startedAt());
        row.setEndedAt(run.endedAt());
        row.setQueueMs(run.queueMs());
        row.setDurationMs(run.durationMs());
        row.setTtftMs(run.ttftMs());
        row.setLlmRounds(run.llmRounds());
        row.setToolCalls(run.toolCalls());
        row.setInputTokens(run.inputTokens());
        row.setOutputTokens(run.outputTokens());
        row.setCacheReadTokens(run.cacheReadTokens());
        row.setCredits(run.credits());
        row.setAttrsJson(AiRunMapper.AiRunRow.toJson(run.attrs()));
        return row;
    }

    private List<AiRunPayloadMapper.Row> toPayloadRows(List<PayloadRecord> records) {
        List<AiRunPayloadMapper.Row> rows = new ArrayList<>(records.size());
        for (PayloadRecord record : records) {
            AiRunPayloadMapper.Row row = new AiRunPayloadMapper.Row();
            row.setRunTraceId(record.traceId());
            row.setSeq(record.seq());
            row.setRequestBody(record.requestBody());
            row.setResponseBody(record.responseBody());
            row.setRequestBytes(record.requestBytes());
            row.setResponseBytes(record.responseBytes());
            row.setTruncated(record.truncated());
            row.setCreatedAt(record.createdAt());
            rows.add(row);
        }
        return rows;
    }

    private List<AiRunSpanMapper.AiRunSpanRow> toSpanRows(RunRecord run) {
        List<AiRunSpanMapper.AiRunSpanRow> rows = new ArrayList<>(run.spans().size());
        for (SpanRecord span : run.spans()) {
            AiRunSpanMapper.AiRunSpanRow row = new AiRunSpanMapper.AiRunSpanRow();
            row.setRunTraceId(span.traceId());
            row.setParentSeq(span.parentSeq());
            row.setSeq(span.seq());
            row.setKind(span.kind());
            row.setName(span.name());
            row.setStatus(span.status());
            row.setErrorType(span.errorType());
            row.setStartedAt(span.startedAt());
            row.setDurationMs(span.durationMs());
            row.setTtftMs(span.ttftMs());
            row.setInputTokens(span.inputTokens());
            row.setOutputTokens(span.outputTokens());
            row.setCacheReadTokens(span.cacheReadTokens());
            row.setAttrsJson(AiRunMapper.AiRunRow.toJson(span.attrs()));
            rows.add(row);
        }
        return rows;
    }
}
