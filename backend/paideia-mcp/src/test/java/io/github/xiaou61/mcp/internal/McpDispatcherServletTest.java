package io.github.xiaou61.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.mcp.internal.usage.McpUsageRecorder;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 视图不存在时的指标（WORK-044 REQ-016）。
 *
 * <p>这一条的存在理由：`ViewNotFoundException` 在 `McpViewRegistry` 里抛出、由分派 servlet 捕获，
 * **到不了 `McpToolInvoker`**。指标若只在 invoker 里记，`reason="view_not_found"` 永远是 0——
 * 而"有人在探测不存在的工具组"恰恰是最该看见的信号之一。
 */
class McpDispatcherServletTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private McpServletConfiguration.McpDispatcherServlet servlet() {
        PaideiaTool tool = new PaideiaTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("knowledge_search", "knowledge", "搜索", "描述",
                        Map.of("type", "object", "properties", Map.of()), null,
                        true, false, true, ToolScope.READ, false);
            }

            @Override
            public ToolResult call(ToolContext context, Map<String, Object> arguments) {
                return ToolResult.ok("ok");
            }
        };
        ToolCatalog catalog = new ToolCatalog(List.of(tool));
        McpViewRegistry views = new McpViewRegistry(catalog, new McpToolInvoker(
                catalog, new McpRateLimiter(100), mock(McpUsageRecorder.class), new McpMetrics(registry)));
        return new McpServletConfiguration.McpDispatcherServlet(views, new McpMetrics(registry));
    }

    @Test
    void unknownViewIsCountedAndReturns404() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/x/nope");
        request.setPathInfo("/x/nope");
        request.setAttribute(McpViewRegistry.ATTR_SCOPES, Set.of(ToolScope.READ));
        request.setAttribute(McpViewRegistry.ATTR_TOOLSETS, Set.of());
        request.setAttribute(McpViewRegistry.ATTR_ROLE, "learner");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet().service(request, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(registry.get("paideia.mcp.denied")
                .tag("reason", "view_not_found").counter().count()).isEqualTo(1.0);
    }

    /** 管理组的探测对非管理员也算 view_not_found——不泄露组名存在。 */
    @Test
    void adminGroupProbeByLearnerIsCountedAsViewNotFound() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/x/admin_users");
        request.setPathInfo("/x/admin_users");
        request.setAttribute(McpViewRegistry.ATTR_SCOPES, Set.of(ToolScope.READ));
        request.setAttribute(McpViewRegistry.ATTR_TOOLSETS, Set.of());
        request.setAttribute(McpViewRegistry.ATTR_ROLE, "learner");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet().service(request, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(registry.get("paideia.mcp.denied")
                .tag("reason", "view_not_found").counter().count()).isEqualTo(1.0);
    }
}
