package io.github.xiaou61.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.mcp.internal.usage.McpUsageRecorder;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 外部工具执行的几道闸：视图可见性、归属绑定、限流、每日配额、返回体截断。
 *
 * <p>每条都是"静默放行"式的失败——不测就只会在某个外泄事件里被发现。
 */
class McpToolInvokerTest {

    private static final McpViewRegistry.PatCaller READER =
            new McpViewRegistry.PatCaller(7L, 1L, "learner", Set.of(ToolScope.READ), Set.of(), null);
    private static final McpViewRegistry.PatCaller WRITER =
            new McpViewRegistry.PatCaller(7L, 1L, "learner", Set.of(ToolScope.READ, ToolScope.WRITE),
                    Set.of(), null);

    private final AtomicReference<ToolContext> lastContext = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> lastArguments = new AtomicReference<>();
    private final McpUsageRecorder usage = mock(McpUsageRecorder.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private PaideiaTool recording(String name, String toolset, boolean readOnly) {
        return recording(name, toolset, readOnly, readOnly ? ToolScope.READ : ToolScope.WRITE);
    }

    private PaideiaTool recording(String name, String toolset, boolean readOnly, ToolScope scope) {
        return new PaideiaTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, toolset, name, "描述 " + name,
                        Map.of("type", "object", "properties", Map.of()), null,
                        readOnly, false, true, scope, false);
            }

            @Override
            public ToolResult call(ToolContext context, Map<String, Object> arguments) {
                lastContext.set(context);
                lastArguments.set(arguments);
                return ToolResult.ok("ok " + name);
            }
        };
    }

    private McpToolInvoker invoker(int perMinute) {
        ToolCatalog catalog = new ToolCatalog(List.of(
                recording("knowledge_search", "knowledge", true),
                recording("note_create", "note", false)));
        return new McpToolInvoker(catalog, new McpRateLimiter(perMinute), usage, new McpMetrics(registry));
    }

    private McpToolInvoker invokerWithAdminTool() {
        ToolCatalog catalog = new ToolCatalog(List.of(
                recording("knowledge_search", "knowledge", true),
                recording("admin_users_list", "admin_users", true, ToolScope.ADMIN)));
        return new McpToolInvoker(catalog, new McpRateLimiter(100), usage, new McpMetrics(registry));
    }

    @Test
    void toolOutsideViewIsUnknown() {
        // 写工具在只读视图里：即便名字对，也必须当作"没有这个工具"
        ToolResult result = invoker(100).invoke(WRITER, McpViews.DEFAULT, "note_create", Map.of());

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("没有名为");
        assertThat(lastContext.get()).as("不该执行").isNull();
    }

    @Test
    void toolInsideWriteViewRunsForWriteToken() {
        McpViews writeView = McpViews.parse("/x/note/write").orElseThrow();

        ToolResult result = invoker(100).invoke(WRITER, writeView, "note_create", Map.of("title", "x"));

        assertThat(result.error()).isFalse();
        assertThat(lastContext.get().origin()).isEqualTo(ToolContext.Origin.EXTERNAL);
    }

    @Test
    void writeToolIsInvisibleToReadOnlyTokenEvenInWriteView() {
        McpViews writeView = McpViews.parse("/x/note/write").orElseThrow();

        ToolResult result = invoker(100).invoke(READER, writeView, "note_create", Map.of());

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("没有名为");
    }

    @Test
    void ownerFieldsAreStrippedFromArguments() {
        invoker(100).invoke(READER, McpViews.parse("/x/knowledge").orElseThrow(),
                "knowledge_search", Map.of("keyword", "索引", "userId", 999L, "ownerId", 999L));

        assertThat(lastArguments.get())
                .containsEntry("keyword", "索引")
                .doesNotContainKeys("userId", "ownerId", "user_id", "owner_id");
        assertThat(lastContext.get().userId()).isEqualTo(7L);
    }

    @Test
    void rateLimitStopsBeforeInvoking() {
        McpToolInvoker invoker = invoker(2);
        McpViews view = McpViews.parse("/x/knowledge").orElseThrow();

        assertThat(invoker.invoke(READER, view, "knowledge_search", Map.of()).error()).isFalse();
        assertThat(invoker.invoke(READER, view, "knowledge_search", Map.of()).error()).isFalse();
        ToolResult third = invoker.invoke(READER, view, "knowledge_search", Map.of());

        assertThat(third.error()).isTrue();
        // 只说"过于频繁"会让模型立刻重试；必须给出上限与剩余秒数
        assertThat(third.text()).contains("过于频繁").contains("每分钟最多 2 次").contains("秒再试");
    }

    @Test
    void internalFailureIsRedacted() {
        PaideiaTool exploding = new PaideiaTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("knowledge_boom", "knowledge", "炸", "描述",
                        Map.of("type", "object", "properties", Map.of()), null,
                        true, false, true, ToolScope.READ, false);
            }

            @Override
            public ToolResult call(ToolContext context, Map<String, Object> arguments) {
                throw new IllegalStateException("Table 'paideia.notes' doesn't exist at jdbc:mysql://10.0.0.1");
            }
        };
        ToolCatalog catalog = new ToolCatalog(List.of(exploding));
        McpToolInvoker invoker = new McpToolInvoker(catalog, new McpRateLimiter(100), usage,
                new McpMetrics(registry));

        ToolResult result = invoker.invoke(READER, McpViews.parse("/x/knowledge").orElseThrow(),
                "knowledge_boom", Map.of());

        assertThat(result.error()).isTrue();
        assertThat(result.text()).doesNotContain("paideia.notes", "jdbc:mysql", "10.0.0.1");
        assertThat(result.text()).contains("失败");
    }

    /**
     * 审计日志（REQ-020 / AC-010）：每次调用一行，字段齐全，**不含参数正文**。
     *
     * <p>用 logback 的内存 appender 捕获：这是唯一能证明"字段真写出来了"的方式——
     * 读代码只能证明"我们打算写"。
     */
    @Test
    void auditLogRecordsRequiredFieldsWithoutPayload() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("mcp.audit");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            invoker(100).invoke(READER, McpViews.parse("/x/knowledge").orElseThrow(),
                    "knowledge_search", Map.of("keyword", "不该出现在日志里的正文"));

            assertThat(appender.list).hasSize(1);
            String line = appender.list.get(0).getFormattedMessage();
            assertThat(line)
                    .contains("tokenId=1")
                    .contains("userId=7")
                    .contains("tool=knowledge_search")
                    .contains("view=knowledge")
                    .contains("durationMs=")
                    .contains("isError=false")
                    .contains("bytes=")
                    .contains("denied=");
            // 参数正文不得进日志
            assertThat(line).doesNotContain("不该出现在日志里的正文");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void unknownToolNameFailsWithoutTouchingAnything() {
        ToolResult result = invoker(100).invoke(READER, McpViews.parse("/x/all").orElseThrow(),
                "delete_everything", Map.of());

        assertThat(result.error()).isTrue();
        assertThat(lastContext.get()).isNull();
    }

    /**
     * 管理工具是两把锁（WORK-039）。
     *
     * <p>过期未清理的 admin scope + 已降权账号必须调不动；只靠"清单里没有"不够——
     * 猜到名字直接调也必须失败。
     */
    @Test
    void adminToolRequiresAdminRoleEvenWithAdminScope() {
        McpViewRegistry.PatCaller demoted = new McpViewRegistry.PatCaller(
                7L, 1L, "learner", Set.of(ToolScope.READ, ToolScope.ADMIN), Set.of(), null);

        ToolResult result = invokerWithAdminTool()
                .invoke(demoted, McpViews.parse("/x/admin_users").orElseThrow(),
                        "admin_users_list", Map.of());

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("没有名为");
        assertThat(lastContext.get()).as("不该执行").isNull();
    }

    @Test
    void adminToolRunsForAdminRoleAndCarriesRoleIntoContext() {
        McpViewRegistry.PatCaller admin = new McpViewRegistry.PatCaller(
                7L, 1L, "admin", Set.of(ToolScope.ADMIN), Set.of(), null);

        ToolResult result = invokerWithAdminTool()
                .invoke(admin, McpViews.parse("/x/admin_users").orElseThrow(),
                        "admin_users_list", Map.of());

        assertThat(result.error()).isFalse();
        assertThat(lastContext.get().admin()).isTrue();
        assertThat(lastContext.get().role()).isEqualTo("admin");
    }

    // ---------------------------------------------------------------- WORK-044

    /** 配额用尽时工具不执行，且原因是 QUOTA 而不是 PERMISSION。 */
    @Test
    void quotaExhaustedStopsBeforeInvoking() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(usage.callsToday(1L, today)).thenReturn(5);
        McpViewRegistry.PatCaller limited = new McpViewRegistry.PatCaller(
                7L, 1L, "learner", Set.of(ToolScope.READ), Set.of(), 5);

        ToolResult result = invoker(100).invoke(limited, McpViews.parse("/x/knowledge").orElseThrow(),
                "knowledge_search", Map.of());

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("每日上限");
        assertThat(lastContext.get()).as("不该执行").isNull();
        assertThat(registry.get("paideia.mcp.denied").tag("reason", "quota").counter().count())
                .isEqualTo(1.0);
    }

    /** 上限为 null 时不查库——省掉一次无意义的 SELECT。 */
    @Test
    void nullLimitDoesNotQueryUsage() {
        invoker(100).invoke(READER, McpViews.parse("/x/knowledge").orElseThrow(),
                "knowledge_search", Map.of());

        verify(usage, never()).callsToday(anyLong(), any());
    }

    /** 限流是内存判定，配额要查库；两者都触发时应当报限流（先过的那个）。 */
    @Test
    void rateLimitTakesPrecedenceOverQuota() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(usage.callsToday(1L, today)).thenReturn(99);
        McpViewRegistry.PatCaller both = new McpViewRegistry.PatCaller(
                7L, 1L, "learner", Set.of(ToolScope.READ), Set.of(), 1);
        McpToolInvoker invoker = invoker(1);

        invoker.invoke(both, McpViews.parse("/x/knowledge").orElseThrow(), "knowledge_search", Map.of());
        ToolResult second = invoker.invoke(both, McpViews.parse("/x/knowledge").orElseThrow(),
                "knowledge_search", Map.of());

        assertThat(second.text()).contains("过于频繁");
        assertThat(registry.get("paideia.mcp.denied").tag("reason", "rate_limit").counter().count())
                .isEqualTo(1.0);
    }

    /** 每次调用都记一次用量，失败也记（errors 靠 errorDelta 区分）。 */
    @Test
    void everyCallIsRecorded() {
        invoker(100).invoke(READER, McpViews.parse("/x/knowledge").orElseThrow(),
                "knowledge_search", Map.of());

        verify(usage).record(eq(1L), any(), anyBoolean());
    }

    /**
     * 被拒的调用不烧每日配额。
     *
     * <p>配额按"今天累计"判、按"实际执行"记。若被拒的也累加，一个对配额上限反复重试的客户端
     * 会把自己的计数越推越高，一整天都解不开——而它一次活都没干。
     */
    @Test
    void deniedCallDoesNotConsumeQuota() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(usage.callsToday(1L, today)).thenReturn(5);
        McpViewRegistry.PatCaller limited = new McpViewRegistry.PatCaller(
                7L, 1L, "learner", Set.of(ToolScope.READ), Set.of(), 5);

        ToolResult result = invoker(100).invoke(limited, McpViews.parse("/x/knowledge").orElseThrow(),
                "knowledge_search", Map.of());

        assertThat(result.text()).contains("每日上限");
        verify(usage, never()).record(anyLong(), any(), anyBoolean());
    }

    @Test
    void capLeavesShortTextAlone() {
        ToolResult result = ToolResult.ok("短文本");

        assertThat(McpToolInvoker.cap(result)).isSameAs(result);
    }

    @Test
    void capTruncatesLongTextAndMarksIt() {
        String longText = "字".repeat(McpToolInvoker.MAX_RESULT_CHARS + 1);

        ToolResult capped = McpToolInvoker.cap(ToolResult.ok(longText));

        assertThat(capped.text()).contains("[已截断");
        assertThat(capped.text()).contains("原文 " + longText.length() + " 字符");
        assertThat(capped.text().length()).isGreaterThan(McpToolInvoker.MAX_RESULT_CHARS);
    }

    /** 截断 JSON 会让客户端拿到非法结构——structured 必须原样保留。 */
    @Test
    void capKeepsStructuredIntact() {
        Map<String, Object> structured = Map.of("noteId", 42L);
        ToolResult longOne = ToolResult.ok("x".repeat(McpToolInvoker.MAX_RESULT_CHARS + 1), structured);

        ToolResult capped = McpToolInvoker.cap(longOne);

        assertThat(capped.structured()).isEqualTo(structured);
    }

    /** 指标标签里不许出现用户输入，否则时间序列会无限增长。 */
    @Test
    void labelsCarryNoUserInput() {
        invoker(100).invoke(READER, McpViews.parse("/x/knowledge").orElseThrow(),
                "knowledge_search", Map.of("keyword", "某个用户的搜索词"));

        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getValue()).doesNotContain("某个用户的搜索词")));
    }
}
