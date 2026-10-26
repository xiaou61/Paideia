package io.github.xiaou61.mcp.internal.security;

import io.github.xiaou61.mcp.internal.token.PatService;
import io.github.xiaou61.platform.RoleLookup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * `/mcp/**` 的安全链（WORK-019 TASK-007）。
 *
 * <p><b>为什么是独立一条链</b>：现有链按路径顺序匹配，`/mcp/**` 若加进去就要和 `anyRequest()`
 * 拼先后；而"顺序写反"在这个项目里是有前科的静默失效模式（知识库管理端那次）。
 * 用 `securityMatcher` 把两条链**按 matcher 互斥**，就不存在"谁先匹配"的问题。
 *
 * <p>`@Order(1)`：比现有链更早评估。两条链的 matcher 不重叠，所以顺序在语义上无所谓，
 * 但显式写出来可以让"这条链只管 /mcp"在代码里可读。
 *
 * <p>CORS 与 CSRF 的处理与主链一致：CORS 由全局配置接管（MCP 客户端是服务端→服务端调用，
 * 一般不带 Origin，但浏览器里的 MCP client 可能带）；CSRF 关掉（无 Cookie，凭据在头里）。
 */
@Configuration
class McpSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http, PatService tokens, RoleLookup roles)
            throws Exception {
        PatAuthenticationFilter patFilter = new PatAuthenticationFilter(tokens, roles);
        http
                // Spring Security 7 移除了 AntPathRequestMatcher；用 lambda 表达同一条规则。
                // **必须同时覆盖 `/mcp` 与 `/mcp/**`**：只写前者会让子视图落到主链（JWT）上，
                // 表现为"默认视图能用、/mcp/x/all 一律 401"。
                .securityMatcher(request -> {
                    String path = request.getServletPath();
                    return "/mcp".equals(path) || path.startsWith("/mcp/");
                })
                .cors(cors -> {
                })
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                // 过滤器自己写 401，并**不抛 AuthenticationException**：避免被默认的
                // entry point 换成带 WWW-Authenticate 的响应（我们不做 OAuth 发现）。
                .addFilterBefore(patFilter, AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"error\":\"unauthorized\"}");
                        }));
        return http.build();
    }
}
