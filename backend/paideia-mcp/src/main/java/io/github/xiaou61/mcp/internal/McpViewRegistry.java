package io.github.xiaou61.mcp.internal;

import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把「视图」变成一台 MCP server（WORK-019 TASK-006）。
 *
 * <p>每个视图一个 {@link McpSyncServer} + 一个 Streamable HTTP transport，**惰性构建并缓存**。
 * 为什么不是一台 server 动态切换工具：SDK 的 server 在构建时确定工具清单，而我们的视图是路径的
 * 函数——按路径分别构建既简单又不会串味；视图数量是个位数，内存代价可以忽略。
 *
 * <p><b>工具清单来自目录</b>（`ToolCatalog.view`），所以外部清单与内部清单不会分叉；
 * 这里只做翻译（{@code ToolDescriptor} → MCP 的 {@code Tool}）。
 */
@Component
class McpViewRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpViewRegistry.class);

    /**
     * 传输层放用户身份的 key。
     *
     * <p>由 `PatAuthenticationFilter` 写进 request attribute、由 transport 的 contextExtractor
     * 搬进 {@code McpTransportContext}，再由工具处理函数取出。
     */
    static final String ATTR_USER_ID = "pd.pat.userId";
    static final String ATTR_TOKEN_ID = "pd.pat.tokenId";
    static final String ATTR_SCOPES = "pd.pat.scopes";
    static final String ATTR_TOOLSETS = "pd.pat.toolsets";
    static final String ATTR_ROLE = "pd.pat.role";

    private final ToolCatalog catalog;
    private final McpToolInvoker invoker;
    private final ConcurrentHashMap<String, McpView> views = new ConcurrentHashMap<>();

    McpViewRegistry(ToolCatalog catalog, McpToolInvoker invoker) {
        this.catalog = catalog;
        this.invoker = invoker;
    }

    /** 一个已构建的视图。transport 需要被 servlet 拿去转发请求。 */
    record McpView(String name, HttpServletStreamableServerTransportProvider transport, McpSyncServer server) {
    }

    /**
     * 取（或首次构建）某个视图。
     *
     * <p>{@code computeIfAbsent} 保证并发下只构建一次：两个请求同时首次访问同一视图时，
     * 重复构建会留下一个没人引用的 server（它的 transport 还注册着会话清理任务）。
     */
    /**
     * 取（或首次构建）某个视图。
     *
     * <p><b>缓存键必须带上凭据的工具组</b>（2026-10-22）：MCP SDK 在构建 server 时固化工具清单，
     * 而"能看见哪些工具"现在取决于凭据。只用视图名当键会让第一个请求的工具清单服务所有凭据——
     * 表现为"勾了只授权知识库，清单里却仍是全部 11 个工具"（线上实测就是这样）。
     *
     * @param allowedToolsets 凭据的工具组白名单；空集合表示不限制
     * @param role            本次调用者的角色（来自账号表，每次请求解析）；缓存键必须带上它
     */
    McpView view(String path, Set<String> allowedToolsets, String role) {
        McpViews parsed = McpViews.parse(path)
                .orElseThrow(() -> new ViewNotFoundException(path));
        // **形状合法不等于存在**：`/mcp/x/nope` 能解析出组名 `nope`，但目录里没有这个组。
        // 不拦的话它会建出一台"工具为零"的 server 并回 200，等于确认了"这个路径格式是对的"，
        // 也让拼错组名的人看到一个空列表而不是明确的"没有这个视图"。
        if (!parsed.toolsets().isEmpty() && !catalog.toolsets().containsAll(parsed.toolsets())) {
            throw new ViewNotFoundException(path);
        }
        // 管理组对非管理员就是"没有这个视图"：返回 200 + 空清单会确认组名存在，与 404 的约定相反。
        // 组名 = 工具名前缀是目录启动期校验的硬约束（ToolCatalog.validateDescriptor）。
        if (!isAdmin(role) && parsed.toolsets().stream().anyMatch(group -> group.startsWith("admin_"))) {
            throw new ViewNotFoundException(path);
        }
        String key = cacheKey(parsed, allowedToolsets, role);
        return views.computeIfAbsent(key, ignored -> build(parsed, pathOf(path), allowedToolsets, role));
    }

    /**
     * 视图缓存键。
     *
     * <p><b>角色必须进键</b>（WORK-038）：SDK 在构建时固化清单，漏了角色会让管理员先建好的
     * 管理视图把管理工具清单缓存给非管理员。用布尔而非角色字符串，避免按角色数膨胀。
     */
    static String cacheKey(McpViews view, Set<String> allowedToolsets, String role) {
        Set<String> toolsets = allowedToolsets == null ? Set.of() : allowedToolsets;
        return view.name() + "|" + String.join(",", new java.util.TreeSet<>(toolsets))
                + "|" + (isAdmin(role) ? "admin" : "user");
    }

    private McpView build(McpViews view, String endpoint, Set<String> allowedToolsets, String role) {
        // scope 带全三档（清单按视图的 write 设置过滤，不按某张凭据的 scope 过滤）；
        // 工具组必须与凭据白名单取交集——漏了它会让"只授权知识库"的凭据看到全部工具。
        List<ToolDescriptor> descriptors = catalog.view(view.queryFor(
                Set.of(ToolScope.READ, ToolScope.WRITE, ToolScope.ADMIN), allowedToolsets, role));
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint(endpoint)
                        // 把请求里的身份搬进 McpTransportContext：工具处理函数只能从这里拿到"谁在调"
                        .contextExtractor(request -> io.modelcontextprotocol.common.McpTransportContext.create(
                                Map.of(
                                        ATTR_USER_ID, request.getAttribute(ATTR_USER_ID),
                                        ATTR_TOKEN_ID, request.getAttribute(ATTR_TOKEN_ID),
                                        ATTR_SCOPES, request.getAttribute(ATTR_SCOPES),
                                        ATTR_TOOLSETS, request.getAttribute(ATTR_TOOLSETS),
                                        ATTR_ROLE, request.getAttribute(ATTR_ROLE))))
                        .build();

        var specification = McpServer.sync(transport)
                .serverInfo("paideia", "0.0.1")
                .instructions("Paideia 个性化学习平台。工具按视图分组：" + view.name() + "。");
        for (ToolDescriptor descriptor : descriptors) {
            specification.tool(toMcpTool(descriptor), (exchange, arguments) ->
                    invoke(exchange, view, descriptor.name(), arguments));
        }
        McpSyncServer server = specification.build();
        log.info("mcp.view.built view={} endpoint={} tools={}", view.name(), endpoint, descriptors.size());
        return new McpView(view.name(), transport, server);
    }

    /** 描述符 → MCP 的 Tool。注解与 outputSchema 都要带上：客户端据此决定是否二次确认。 */
    private static McpSchema.Tool toMcpTool(ToolDescriptor descriptor) {
        var builder = McpSchema.Tool.builder()
                .name(descriptor.name())
                .title(descriptor.title())
                .description(descriptor.description())
                .inputSchema(toJsonSchema(descriptor.inputSchema()))
                .annotations(new McpSchema.ToolAnnotations(descriptor.title(),
                        descriptor.readOnly(), descriptor.destructive(), descriptor.idempotent(),
                        false, null));
        if (descriptor.outputSchema() != null) {
            builder.outputSchema(descriptor.outputSchema());
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static McpSchema.JsonSchema toJsonSchema(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        Object required = schema.get("required");
        return new McpSchema.JsonSchema(
                String.valueOf(schema.getOrDefault("type", "object")),
                properties instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(),
                required instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(),
                schema.get("additionalProperties") instanceof Boolean flag ? flag : null,
                null, null);
    }

    /** 路径里去掉首尾斜杠当端点：transport 用 `requestURI.endsWith(mcpEndpoint)` 判定归属。 */
    private static String pathOf(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "/mcp";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private McpSchema.CallToolResult invoke(McpSyncServerExchange exchange, McpViews view,
                                            String toolName, Map<String, Object> arguments) {
        var context = exchange.transportContext();
        Object rawUserId = context.get(ATTR_USER_ID);
        Object rawTokenId = context.get(ATTR_TOKEN_ID);
        Object rawScopes = context.get(ATTR_SCOPES);
        if (!(rawUserId instanceof Long userId) || !(rawTokenId instanceof Long tokenId)) {
            // 安全链没跑或没写入 → 不当成"匿名可用"，而是按未认证处理
            return McpSchema.CallToolResult.builder()
                    .addTextContent("未认证：缺少有效的 MCP 凭据")
                    .isError(true).build();
        }
        @SuppressWarnings("unchecked")
        Set<ToolScope> scopes = rawScopes instanceof Set<?> set
                ? (Set<ToolScope>) set
                : Set.of();
        @SuppressWarnings("unchecked")
        Set<String> toolsets = context.get(ATTR_TOOLSETS) instanceof Set<?> set
                ? (Set<String>) set
                : Set.of();
        String role = context.get(ATTR_ROLE) instanceof String value ? value : "";
        PatCaller caller = new PatCaller(userId, tokenId, role, scopes, toolsets);
        ToolResult result = invoker.invoke(caller, view, toolName, arguments == null ? Map.of() : arguments);
        var builder = McpSchema.CallToolResult.builder().addTextContent(result.text());
        if (result.structured() != null) {
            builder.structuredContent(result.structured());
        }
        if (result.error()) {
            builder.isError(true);
        }
        return builder.build();
    }

    /** 调用者身份（来自 PAT 解析结果）。`toolsets` 为空表示不限制工具组。 */
    record PatCaller(long userId, long tokenId, String role, Set<ToolScope> scopes, Set<String> toolsets) {

        boolean isAdmin() {
            return "admin".equalsIgnoreCase(role);
        }
    }

    /** 角色判定只认 `admin`（与账号模块的取值一致），大小写不敏感。 */
    static boolean isAdmin(String role) {
        return "admin".equalsIgnoreCase(role);
    }

    /** 路径不是一个已知视图。 */
    static final class ViewNotFoundException extends RuntimeException {

        ViewNotFoundException(String path) {
            super("未知的 MCP 视图：" + path);
        }
    }

    /** 视图名（测试与日志用）。 */
    Optional<String> viewName(String path) {
        return McpViews.parse(path).map(McpViews::name);
    }

}
