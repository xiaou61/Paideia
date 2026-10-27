package io.github.xiaou61.mcp.internal;

import java.util.EnumSet;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletRegistration;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 `/mcp/*` 的请求交给 MCP 传输层（WORK-019 TASK-006）。
 *
 * <p><b>为什么用一个分派 servlet 而不是给每个视图注册一个</b>：视图要按路径段解析，而且应该在
 * 第一次请求时才构建（否则应用启动就要为没人用的视图建 server）。所以这里注册**一个** servlet
 * 挂在 `/mcp/*`，由它按路径取（或首次构建）视图、把请求转给该视图的 transport。
 *
 * <p>`asyncSupported = true` 必须开：Streamable HTTP 的回复可以是 SSE 流，容器需要支持异步
 * 分派，否则长连接在容器层就被切断。
 */
@Configuration
class McpServletConfiguration {

    /**
     * 路径分派。
     *
     * <p>`pathInfo` 是 servlet 规范给的"匹配 `/mcp/*` 之后剩下来的部分"：`/mcp/x/all` → `/x/all`。
     */
    static final class McpDispatcherServlet extends jakarta.servlet.http.HttpServlet {

        private final McpViewRegistry registry;
        private final McpMetrics metrics;

        McpDispatcherServlet(McpViewRegistry registry, McpMetrics metrics) {
            this.registry = registry;
            this.metrics = metrics;
        }

        @Override
        protected void service(jakarta.servlet.http.HttpServletRequest request,
                               jakarta.servlet.http.HttpServletResponse response)
                throws java.io.IOException, jakarta.servlet.ServletException {
            McpViewRegistry.McpView view;
            try {
                // 工具组的白名单来自已认证的凭据：工具清单是构建期固化的，必须按凭据分开建
                java.util.Set<String> toolsets = request.getAttribute(McpViewRegistry.ATTR_TOOLSETS)
                        instanceof java.util.Set<?> set
                        ? set.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet())
                        : java.util.Set.of();
                String role = request.getAttribute(McpViewRegistry.ATTR_ROLE) instanceof String value
                        ? value
                        : "";
                view = registry.view(request.getPathInfo(), toolsets, role);
            } catch (McpViewRegistry.ViewNotFoundException missing) {
                // 视图不存在会在这里就返回，到不了 McpToolInvoker，所以指标只能在这里记
                metrics.denied(Denial.VIEW_NOT_FOUND.key());
                // 视图不存在与"端点不存在"给同一个响应，不告诉探测者哪些组存在
                response.sendError(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            view.transport().service(request, response);
        }
    }

    @Bean
    ServletContextInitializer mcpServletRegistration(McpViewRegistry registry, McpMetrics metrics) {
        McpDispatcherServlet dispatcher = new McpDispatcherServlet(registry, metrics);
        return servletContext -> {
            ServletRegistration.Dynamic registration = servletContext.addServlet("mcp", dispatcher);
            if (registration == null) {
                // 同名 servlet 已存在：说明有第二处注册，属于配置错误，直接失败
                throw new IllegalStateException("servlet 名称 mcp 已被占用");
            }
            registration.setAsyncSupported(true);
            registration.addMapping("/mcp/*");
            registration.setLoadOnStartup(1);
        };
    }

    /** 容器需要显式声明时用得到；当前的 Tomcat 按 `setAsyncSupported` 即可。 */
    static EnumSet<DispatcherType> asyncDispatches() {
        return EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC);
    }
}
