package io.github.xiaou61.mcp.internal;

import io.github.xiaou61.platform.ToolQuery;
import io.github.xiaou61.platform.ToolScope;
import java.util.Optional;
import java.util.Set;

/**
 * 视图 = 路径 → 一份工具查询（WORK-019 REQ-012）。
 *
 * <p>形状照 GitHub 官方 MCP Server：默认组、单工具组、"全部"，每个都可以带 `/write`。
 * **视图是路径的一部分**，所以"清单里有什么"与"能调什么"天然一致——不需要按请求裁剪清单，
 * 也就不依赖 2026-07-28 才允许的每请求列表变化。
 *
 * <p>未知路径一律 {@link Optional#empty()}，由调用方回 404。**不用 400**：400 会告诉探测者
 * "这个格式对、那个组不存在"，而 404 与"没有这个端点"没有区别。
 *
 * @param name      视图名（日志、插件缓存键）
 * @param toolsets  只留这些工具组；空集合表示不按组过滤（`/mcp/x/all`）
 * @param write     是否含写工具
 * @param defaultsOnly 是否只留默认组
 */
record McpViews(String name, Set<String> toolsets, boolean write, boolean defaultsOnly) {

    /** 默认视图：默认组、只读。 */
    static final McpViews DEFAULT = new McpViews("default", Set.of(), false, true);

    /**
     * 解析请求路径。
     *
     * <p>支持的形状（`{toolset}` 只允许小写字母数字下划线）：
     * <pre>
     *   /mcp                     默认组，只读
     *   /mcp/write               默认组，可写（需要 write scope）
     *   /mcp/x/all               全部，只读
     *   /mcp/x/all/write         全部，可写
     *   /mcp/x/{toolset}         单组，只读
     *   /mcp/x/{toolset}/write   单组，可写
     * </pre>
     *
     * @param path servlet pathInfo（以 `/` 开头，可能为空）
     */
    static Optional<McpViews> parse(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return Optional.of(DEFAULT);
        }
        boolean write = trimmed.equals("write") || trimmed.endsWith("/write");
        if (trimmed.equals("write")) {
            return Optional.of(new McpViews("default-write", Set.of(), true, true));
        }
        if (write) {
            trimmed = trimmed.substring(0, trimmed.length() - "/write".length());
        }
        if (!trimmed.startsWith("x/")) {
            return Optional.empty();
        }
        String toolset = trimmed.substring(2);
        if (toolset.isEmpty() || !toolset.matches("[a-z][a-z0-9_]*")) {
            return Optional.empty();
        }
        if ("all".equals(toolset)) {
            return Optional.of(new McpViews(write ? "all-write" : "all", Set.of(), write, false));
        }
        return Optional.of(new McpViews(write ? toolset + "-write" : toolset, Set.of(toolset), write, false));
    }

    /**
     * 本视图对**这个调用者**的可调用集合。
     *
     * <p>write 视图要求 token 带 write scope；这里把 scope 也带进查询，于是"清单里有、但 scope 不够"
     * 的工具在调用阶段同样不可见——两道判断用的是同一份过滤链。
     */
    ToolQuery queryFor(Set<ToolScope> scopes, String role) {
        return ToolQuery.view(toolsets, write, role, scopes);
    }

    /**
     * 本视图对**这张凭据**的可调用集合：在视图自身的组过滤之上再交一次凭据的工具组白名单。
     *
     * <p>空白名单（老凭据）时保持原语义。两者取交集：视图说"只看 knowledge"、
     * 凭据只准 `note` 时结果是空——这正是"缩小权限"该有的样子。
     */
    ToolQuery queryFor(Set<ToolScope> scopes, Set<String> allowedToolsets, String role) {
        if (allowedToolsets == null || allowedToolsets.isEmpty()) {
            return queryFor(scopes, role);
        }
        Set<String> intersected = toolsets.isEmpty()
                ? allowedToolsets
                : toolsets.stream().filter(allowedToolsets::contains)
                        .collect(java.util.stream.Collectors.toSet());
        return ToolQuery.view(intersected, write, role, scopes);
    }

    /**
     * 构建视图时用的查询（服务端视角）。
     *
     * <p>scope 带全三档：清单要按视图的 write 设置过滤，而**不能**按某张凭据的 scope 过滤——
     * 否则管理员的第一张凭据会把清单固化成它自己那一份。凭据的真实 scope 在调用阶段校验。
     */
    ToolQuery query(String role) {
        Set<ToolScope> scopes = write
                ? Set.of(ToolScope.READ, ToolScope.WRITE, ToolScope.ADMIN)
                : Set.of(ToolScope.READ, ToolScope.ADMIN);
        return ToolQuery.view(toolsets, write, role, scopes);
    }
}
