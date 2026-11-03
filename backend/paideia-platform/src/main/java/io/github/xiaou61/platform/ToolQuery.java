package io.github.xiaou61.platform;

import java.util.Set;

/**
 * 一次"要哪些工具"的查询。
 *
 * <p>两个静态工厂对应两个消费者：{@link #internal} 是自家助手（按 harness 选组、scope 全给），
 * {@link #view} 是外部 MCP 视图（按路径选组、只读/可写）。**过滤链只有一条**（
 * {@link ToolCatalog#view}），两边共用，因此"哪条规则生效"只有一处需要读懂。
 *
 * @param toolsets     要包含的工具组；空集合表示"不按组过滤"
 * @param tools        额外显式包含的工具名（可越过工具组过滤）
 * @param readOnly     为 true 时排除所有写工具
 * @param role         调用者角色；管理类工具要求 `ADMIN`
 * @param scopes       凭据作用域；工具所需 scope 不在其中即排除
 * @param defaultsOnly 为 true 时只留 `defaultGroup=true` 的工具（内部核心包用）
 */
public record ToolQuery(
        Set<String> toolsets,
        Set<String> tools,
        boolean readOnly,
        String role,
        Set<ToolScope> scopes,
        boolean defaultsOnly) {

    public ToolQuery {
        toolsets = toolsets == null ? Set.of() : Set.copyOf(toolsets);
        tools = tools == null ? Set.of() : Set.copyOf(tools);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /**
     * 内部助手：按工具组取，**只读**。
     *
     * <p><b>只读不是省事，是产品裁决</b>（WORK-006 用户决定：自主只建议不动作）。自家助手能替用户
     * 想、能起草，但不能替他落笔——写库必须经用户在一次点击里确认。所以内部清单永远排除写工具，
     * 包括 `note_create` 与 `assessment_mark_entry`；`assistant_draft_note` 因为是只读（只产草稿）
     * 才留在清单里。
     */
    public static ToolQuery internal(Set<String> toolsets, String role) {
        return new ToolQuery(toolsets, Set.of(), true, role, Set.of(ToolScope.READ), false);
    }

    /** 内部核心包：只要标了 defaultGroup 的那些，同样只读。 */
    public static ToolQuery core(String role) {
        return new ToolQuery(Set.of(), Set.of(), true, role, Set.of(ToolScope.READ), true);
    }

    /**
     * 外部视图。
     *
     * @param toolsets 空集合 = 全部工具组（对应 `/mcp/x/all`）
     * @param write    false 时只读（对应不带 `/write` 的视图）
     * @param scopes   凭据的作用域
     */
    public static ToolQuery view(Set<String> toolsets, boolean write, String role, Set<ToolScope> scopes) {
        return view(toolsets, write, role, scopes, false);
    }

    /**
     * 同上，但可以只要默认组（`/mcp` 的语义）。
     *
     * <p>`/mcp` 是"默认组、只读"，不是"全部只读"——漏掉这个参数会让标了 `defaultGroup=false` 的工具
     * 全部暴露在默认视图里（2026-11-03 修：WORK-019 起一直如此，WORK-044 的 8 个域工具都在其中）。
     */
    public static ToolQuery view(Set<String> toolsets, boolean write, String role, Set<ToolScope> scopes,
                                 boolean defaultsOnly) {
        Set<ToolScope> effective = write
                ? scopes
                : scopes.stream().filter(it -> it != ToolScope.WRITE).collect(java.util.stream.Collectors.toSet());
        return new ToolQuery(toolsets, Set.of(), !write, role, effective, defaultsOnly);
    }
}
