package io.github.xiaou61.mcp.internal;

import io.github.xiaou61.mcp.internal.usage.McpUsageRecorder;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 执行一次外部工具调用（WORK-019 TASK-008，WORK-044 扩配额/截断/指标）。
 *
 * <p>它负责在目录与工具实现之间发生的几件事，**顺序固定**：
 * <ol>
 *   <li><b>视图校验</b>：工具必须在当前视图里，否则当作未知工具——清单里没有的东西不能因为
 *       猜到一个名字就调得动；</li>
 *   <li><b>归属绑定</b>：`ToolContext.userId` 只来自 PAT；参数里的 `userId` 一律删除；</li>
 *   <li><b>限流</b>：按 token 计数，超限不执行（内存固定窗口，防瞬时爆发）；</li>
 *   <li><b>配额</b>：按 token 查当天累计次数，达到凭据上限不执行（数据库，防长期失控）；</li>
 *   <li><b>执行</b>；</li>
 *   <li><b>截断</b>：超长文本裁到上限并标记；</li>
 *   <li><b>审计 + 计数 + 指标</b>：三者同一处收尾，不会出现"日志记了但计数没记"的分叉。</li>
 * </ol>
 *
 * <p>**限流在配额之前**：限流是内存判定（几乎零成本），配额要查库。先过便宜的。
 *
 * <p>**失败不抛**：内部工具本身就是"返回错误结果"的风格；这里把异常也收敛成一条脱敏文本，
 * 因为外部客户端看到的堆栈会泄露表名与连接串。
 */
@Component
class McpToolInvoker {

    private static final Logger audit = LoggerFactory.getLogger("mcp.audit");
    private static final Logger errors = LoggerFactory.getLogger("mcp.error");

    /**
     * 外部调用的返回体上限。
     *
     * <p>内部 harness 不受影响——它有自己按轮计算的上下文预算，两处的约束理由不同。
     */
    static final int MAX_RESULT_CHARS = 32 * 1024;

    private final ToolCatalog catalog;
    private final McpRateLimiter limiter;
    private final McpUsageRecorder usage;
    private final McpMetrics metrics;

    McpToolInvoker(ToolCatalog catalog, McpRateLimiter limiter, McpUsageRecorder usage,
                   McpMetrics metrics) {
        this.catalog = catalog;
        this.limiter = limiter;
        this.usage = usage;
        this.metrics = metrics;
    }

    ToolResult invoke(McpViewRegistry.PatCaller caller, McpViews view, String toolName,
                      Map<String, Object> arguments) {
        long started = System.nanoTime();
        // 配额判定与计数必须用同一个"今天"，否则跨天前后两次调用会读到一个日期的两半
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Outcome outcome;
        try {
            outcome = doInvoke(caller, view, toolName, arguments, today);
        } catch (RuntimeException unexpected) {
            // 服务端日志留完整堆栈；返回给客户端的只有一句
            errors.error("mcp.tool.failed userId={} tool={}", caller.userId(), toolName, unexpected);
            outcome = Outcome.ok(ToolResult.fail("工具执行失败，请稍后重试"));
        }
        ToolResult result = cap(outcome.result());
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;

        audit.info("tokenId={} userId={} tool={} view={} durationMs={} isError={} bytes={} denied={}",
                caller.tokenId(), caller.userId(), toolName, view.name(),
                (long) (seconds * 1000), result.error(),
                result.text() == null ? 0 : result.text().length(),
                outcome.denial().key());
        record(caller, toolName, today, result, outcome.denial(), seconds);
        return result;
    }

    private Outcome doInvoke(McpViewRegistry.PatCaller caller, McpViews view, String toolName,
                             Map<String, Object> arguments, LocalDate today) {
        Optional<PaideiaTool> found = catalog.find(toolName);
        if (found.isEmpty()) {
            return Outcome.denied("没有名为「" + toolName + "」的工具", Denial.UNKNOWN_TOOL);
        }
        ToolDescriptor descriptor = found.get().descriptor();

        // 视图校验：当前视图的清单是唯一权威（按调用者的 scope 与角色过滤后仍须包含它）
        boolean visible = catalog.view(view.queryFor(caller.scopes(), caller.toolsets(), caller.role()))
                .stream().anyMatch(candidate -> candidate.name().equals(toolName));
        if (!visible) {
            return Outcome.denied("没有名为「" + toolName + "」的工具", Denial.PERMISSION);
        }
        if (!caller.scopes().contains(descriptor.scope())) {
            return Outcome.denied("当前凭据没有执行这个工具的权限", Denial.PERMISSION);
        }
        // 角色再判一次：清单过滤已经拦过，但"清单可见 ≠ 可调"必须两处一致
        if (descriptor.scope() == ToolScope.ADMIN && !caller.isAdmin()) {
            return Outcome.denied("没有名为「" + toolName + "」的工具", Denial.PERMISSION);
        }
        if (!limiter.allow(caller.tokenId())) {
            return Outcome.denied("调用过于频繁，请稍后再试", Denial.RATE_LIMIT);
        }
        Integer dailyLimit = caller.dailyCallLimit();
        if (dailyLimit != null && usage.callsToday(caller.tokenId(), today) >= dailyLimit) {
            return Outcome.denied(
                    "今天已经调用 " + dailyLimit + " 次，达到这个凭据的每日上限；"
                            + "明天再来，或让账号主人调高上限。", Denial.QUOTA);
        }
        return Outcome.ok(found.get().call(contextOf(caller), sanitize(arguments)));
    }

    /**
     * 截断超长文本。
     *
     * <p>**只截 text，不截 structured**：structured 是给客户端程序读的，截断 JSON 会让它变成
     * 非法结构；text 是给模型读的，截断并标记是安全降级。
     *
     * <p>不超限时返回原引用，不复制。
     */
    static ToolResult cap(ToolResult result) {
        String text = result.text();
        if (text == null || text.length() <= MAX_RESULT_CHARS) {
            return result;
        }
        return new ToolResult(result.error(),
                text.substring(0, MAX_RESULT_CHARS)
                        + "\n\n[已截断：原文 " + text.length() + " 字符，只返回前 " + MAX_RESULT_CHARS
                        + " 字符。需要完整内容时请缩小查询范围。]",
                result.structured());
    }

    /**
     * 收尾三件事：写用量、打指标。
     *
     * <p>计数失败不传播（{@code McpUsageRecorder.record} 内部已吞）；指标写入理论上不会失败，
     * 但仍包一层，避免一处旁路把整次调用炸掉。
     */
    private void record(McpViewRegistry.PatCaller caller, String toolName, LocalDate today,
                        ToolResult result, Denial denial, double seconds) {
        try {
            usage.record(caller.tokenId(), today, result.error());
        } catch (RuntimeException failure) {
            errors.warn("mcp.usage.record.failed tokenId={} tool={}", caller.tokenId(), toolName, failure);
        }
        try {
            String toolset = catalog.find(toolName)
                    .map(tool -> tool.descriptor().toolset())
                    .orElse("unknown");
            metrics.calls(toolset, resultClass(result, denial));
            metrics.duration(toolName, seconds);
            if (denial.denied()) {
                metrics.denied(denial.key());
            }
        } catch (RuntimeException failure) {
            errors.warn("mcp.metrics.failed tool={}", toolName, failure);
        }
    }

    /** 结果分类：被拒 / 工具自己失败 / 成功。 */
    private static String resultClass(ToolResult result, Denial denial) {
        if (denial.denied()) {
            return McpMetrics.DENIED;
        }
        return result.error() ? McpMetrics.TOOL_ERROR : McpMetrics.OK;
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
