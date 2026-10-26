package io.github.xiaou61.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 外部工具执行的三道闸：视图可见性、归属绑定、限流。
 *
 * <p>每条都是"静默放行"式的失败——不测就只会在某个外泄事件里被发现。
 */
class McpToolInvokerTest {

    private static final McpViewRegistry.PatCaller READER =
            new McpViewRegistry.PatCaller(7L, 1L, "learner", Set.of(ToolScope.READ), Set.of());
    private static final McpViewRegistry.PatCaller WRITER =
            new McpViewRegistry.PatCaller(7L, 1L, "learner", Set.of(ToolScope.READ, ToolScope.WRITE), Set.of());

    private final AtomicReference<ToolContext> lastContext = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> lastArguments = new AtomicReference<>();

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
        return new McpToolInvoker(catalog, new McpRateLimiter(perMinute));
    }

    private McpToolInvoker invokerWithAdminTool() {
        ToolCatalog catalog = new ToolCatalog(List.of(
                recording("knowledge_search", "knowledge", true),
                recording("admin_users_list", "admin_users", true, ToolScope.ADMIN)));
        return new McpToolInvoker(catalog, new McpRateLimiter(100));
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
        assertThat(third.text()).contains("过于频繁");
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
        McpToolInvoker invoker = new McpToolInvoker(catalog, new McpRateLimiter(100));

        ToolResult result = invoker.invoke(READER, McpViews.parse("/x/knowledge").orElseThrow(),
                "knowledge_boom", Map.of());

        assertThat(result.error()).isTrue();
        assertThat(result.text()).doesNotContain("paideia.notes", "jdbc:mysql", "10.0.0.1");
        assertThat(result.text()).contains("失败");
    }

    /**
     * 审计日志（REQ-020 / AC-010）：每次调用一行，七个字段齐全，**不含参数正文**。
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
                    .contains("bytes=");
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
     * 管理工具是两把锁（WORK-038）。
     *
     * <p>过期未清理的 admin scope + 已降权账号必须调不动；只靠"清单里没有"不够——
     * 猜到名字直接调也必须失败。
     */
    @Test
    void adminToolRequiresAdminRoleEvenWithAdminScope() {
        McpViewRegistry.PatCaller demoted = new McpViewRegistry.PatCaller(
                7L, 1L, "learner", Set.of(ToolScope.READ, ToolScope.ADMIN), Set.of());

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
                7L, 1L, "admin", Set.of(ToolScope.ADMIN), Set.of());

        ToolResult result = invokerWithAdminTool()
                .invoke(admin, McpViews.parse("/x/admin_users").orElseThrow(),
                        "admin_users_list", Map.of());

        assertThat(result.error()).isFalse();
        assertThat(lastContext.get().admin()).isTrue();
        assertThat(lastContext.get().role()).isEqualTo("admin");
    }
}
