package io.github.xiaou61.mcp.internal;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 执行一次外部工具调用（WORK-019 TASK-008）。
 *
 * <p>它负责四件在目录与工具实现之间发生的事：
 * <ol>
 *   <li><b>视图校验</b>：工具必须在当前视图里，否则当作未知工具——清单里没有的东西不能因为
 *       猜到一个名字就调得动；</li>
 *   <li><b>归属绑定</b>：`ToolContext.userId` 只来自 PAT；参数里的 `userId` 一律删除；</li>
 *   <li><b>限流</b>：按 token 计数，超限不执行；</li>
 *   <li><b>审计</b>：一行结构化日志，不含参数正文。</li>
 * </ol>
 *
 * <p>**失败不抛**：内部工具本身就是"返回错误结果"的风格；这里把异常也收敛成一条脱敏文本，
 * 因为外部客户端看到的堆栈会泄露表名与连接串。
 */
@Component
class McpToolInvoker {

    private static final Logger audit = LoggerFactory.getLogger("mcp.audit");
    private static final Logger errors = LoggerFactory.getLogger("mcp.error");

    private final ToolCatalog catalog;
    private final McpRateLimiter limiter;

    McpToolInvoker(ToolCatalog catalog, McpRateLimiter limiter) {
        this.catalog = catalog;
        this.limiter = limiter;
    }

    ToolResult invoke(McpViewRegistry.PatCaller caller, McpViews view, String toolName,
                      Map<String, Object> arguments) {
        long started = System.nanoTime();
        ToolResult result;
        try {
            result = doInvoke(caller, view, toolName, arguments);
        } catch (RuntimeException unexpected) {
            // 服务端日志留完整堆栈；返回给客户端的只有一句
            errors.error("mcp.tool.failed userId={} tool={}", caller.userId(), toolName, unexpected);
            result = ToolResult.fail("工具执行失败，请稍后重试");
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        audit.info("tokenId={} userId={} tool={} view={} durationMs={} isError={} bytes={}",
                caller.tokenId(), caller.userId(), toolName, view.name(), durationMs, result.error(),
                result.text() == null ? 0 : result.text().length());
        return result;
    }

    private ToolResult doInvoke(McpViewRegistry.PatCaller caller, McpViews view, String toolName,
                                Map<String, Object> arguments) {
        Optional<PaideiaTool> found = catalog.find(toolName);
        if (found.isEmpty()) {
            return ToolResult.fail("没有名为「" + toolName + "」的工具");
        }
        ToolDescriptor descriptor = found.get().descriptor();

        // 视图校验：当前视图的清单是唯一权威（按调用者的 scope 与角色过滤后仍须包含它）
        boolean visible = catalog.view(view.queryFor(caller.scopes(), caller.toolsets(), caller.role()))
                .stream().anyMatch(candidate -> candidate.name().equals(toolName));
        if (!visible) {
            return ToolResult.fail("没有名为「" + toolName + "」的工具");
        }
        if (!caller.scopes().contains(descriptor.scope())) {
            return ToolResult.fail("当前凭据没有执行这个工具的权限");
        }
        // 角色再判一次：清单过滤已经拦过，但"清单可见 ≠ 可调"必须两处一致
        if (descriptor.scope() == ToolScope.ADMIN && !caller.isAdmin()) {
            return ToolResult.fail("没有名为「" + toolName + "」的工具");
        }
        if (!limiter.allow(caller.tokenId())) {
            return ToolResult.fail("调用过于频繁，请稍后再试");
        }
        return found.get().call(contextOf(caller), sanitize(arguments));
    }

    /**
     * 参数清洗：**把归属类字段直接删掉**。
     *
     * <p>删而不是报错，是因为"校验失败"会让模型以为可以换一种写法再试；删掉之后它连表达的
     * 机会都没有。工具实现本来也不读这些键。
     */
    static Map<String, Object> sanitize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(arguments);
        copy.remove("userId");
        copy.remove("user_id");
        copy.remove("ownerId");
        copy.remove("owner_id");
        return Map.copyOf(copy);
    }

    static ToolContext contextOf(McpViewRegistry.PatCaller caller) {
        return new ToolContext(caller.userId(), caller.role(), caller.scopes(), ToolContext.Origin.EXTERNAL);
    }
}
