package io.github.xiaou61.mcp.internal;

/**
 * 一次外部调用被拒的原因（WORK-044 TASK-003）。
 *
 * <p><b>为什么要分类</b>：改之前所有失败都是一句"没有这个工具"或"调用过于频繁"，
 * 指标里只能算成一种错误。分成原因之后，"今天被拒的都是配额"与"都是权限"是两件完全不同的事。
 *
 * <p>`key()` 会进指标标签，所以只能是有限的固定取值——不许拼用户输入。
 */
enum Denial {

    /** 没有被拒。 */
    NONE(""),
    UNKNOWN_TOOL("unknown_tool"),
    /** 工具在目录里，但不在这个凭据的视图里，或 scope / 角色不够。 */
    PERMISSION("permission"),
    /** 每分钟固定窗口超限。 */
    RATE_LIMIT("rate_limit"),
    /** 每日调用次数达到凭据上限。 */
    QUOTA("quota"),
    /** 路径不是一个已知视图（在 `McpViewRegistry` 抛出，不进 invoker）。 */
    VIEW_NOT_FOUND("view_not_found");

    private final String key;

    Denial(String key) {
        this.key = key;
    }

    /** 指标标签值。`NONE` 返回空串，调用方据此跳过。 */
    String key() {
        return key;
    }

    boolean denied() {
        return this != NONE;
    }
}
