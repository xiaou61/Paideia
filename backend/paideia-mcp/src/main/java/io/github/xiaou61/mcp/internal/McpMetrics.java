package io.github.xiaou61.mcp.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * MCP 外部调用的指标（WORK-044 TASK-005）。
 *
 * <p><b>为什么不能只靠审计日志</b>：日志给人读，没有结构化查询面；Prometheus 要的是稳定标签集。
 * 两者并存——日志留细节（tokenId、userId），指标留趋势。
 *
 * <p><b>基数控制</b>：所有标签值都是有限枚举（工具名 65 个、工具组 14 个、结果 3 种、原因 5 种），
 * **不许把 userId / tokenId / 工具参数拼进标签**——那会让时间序列无限增长。
 */
@Component
class McpMetrics {

    /** 一次调用的结果分类。 */
    static final String OK = "ok";
    static final String TOOL_ERROR = "tool_error";
    static final String DENIED = "denied";

    private final MeterRegistry registry;

    McpMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 一次工具调用。`result` 取 {@link #OK} / {@link #TOOL_ERROR} / {@link #DENIED}。 */
    void calls(String toolset, String result) {
        registry.counter("paideia.mcp.tool.calls", "toolset", toolset, "result", result).increment();
    }

    /** 单个工具的耗时（秒）。 */
    void duration(String tool, double seconds) {
        registry.timer("paideia.mcp.tool.duration", "tool", tool).record(
                java.time.Duration.ofNanos((long) (seconds * 1_000_000_000L)));
    }

    /** 被拒的原因。 */
    void denied(String reason) {
        registry.counter("paideia.mcp.denied", "reason", reason).increment();
    }
}
