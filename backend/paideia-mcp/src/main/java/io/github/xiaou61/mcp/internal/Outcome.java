package io.github.xiaou61.mcp.internal;

import io.github.xiaou61.platform.ToolResult;

/**
 * 一次外部调用的结果与被拒原因（WORK-044 TASK-003）。
 *
 * <p>把两者绑在一起，是因为它们**必须一起产生**：只返回 `ToolResult` 的话，
 * 调用方分不清"工具自己返回了失败"和"根本没让它执行"——前者要计入工具的失败率，
 * 后者要计入被拒原因，混在一起指标就没有意义了。
 */
record Outcome(ToolResult result, Denial denial) {

    static Outcome ok(ToolResult result) {
        return new Outcome(result, Denial.NONE);
    }

    static Outcome denied(String message, Denial denial) {
        return new Outcome(ToolResult.fail(message), denial);
    }
}
