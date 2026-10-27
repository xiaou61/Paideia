package io.github.xiaou61.mcp.internal.security;

import io.github.xiaou61.mcp.internal.token.PatService;
import io.github.xiaou61.platform.RoleLookup;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * `/mcp/**` 的认证：`Authorization: Bearer <PAT>`（WORK-019 REQ-013）。
 *
 * <p><b>为什么不复用 JWT 过滤器</b>：JWT 的 access token 只有 15 分钟，而 MCP 客户端把凭据写在
 * 配置文件里——用户不可能每 15 分钟改一次配置。PAT 是另一条凭据通道，有独立的表、独立的撤销语义。
 * 两条通道用**独立的 SecurityFilterChain**（按 matcher 互斥），所以不会出现"JWT 链先匹配走、
 * PAT 永远不生效"这种顺序陷阱。
 *
 * <p>认证成功后做两件事：
 * <ol>
 *   <li>写 request attribute（`pd.pat.*`）：MCP transport 的 contextExtractor 会把它们搬进
 *       {@code McpTransportContext}，工具处理函数只能从这里拿到"谁在调"；</li>
 *   <li>设置 SecurityContext：让 Spring Security 的 `authenticated()` 判定通过。</li>
 * </ol>
 *
 * <p>失败**一律 401，且响应体不带原因**：不区分"没有 token""token 过期""token 被吊销"——
 * 区分开就等于告诉探测者"这个 token 曾经存在"。
 */
class PatAuthenticationFilter extends OncePerRequestFilter {

    /** 与 {@code McpViewRegistry} 的常量保持一致（同一组 key）。 */
    static final String ATTR_USER_ID = "pd.pat.userId";
    static final String ATTR_TOKEN_ID = "pd.pat.tokenId";
    static final String ATTR_SCOPES = "pd.pat.scopes";
    static final String ATTR_TOOLSETS = "pd.pat.toolsets";
    static final String ATTR_ROLE = "pd.pat.role";
    static final String ATTR_DAILY_CALL_LIMIT = "pd.pat.dailyCallLimit";

    private final PatService tokens;
    private final RoleLookup roles;

    PatAuthenticationFilter(PatService tokens, RoleLookup roles) {
        this.tokens = tokens;
        this.roles = roles;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response);
            return;
        }
        var principal = tokens.authenticate(header.substring("Bearer ".length()).trim());
        if (principal.isEmpty()) {
            unauthorized(response);
            return;
        }
        PatService.PatPrincipal found = principal.get();
        // 角色每次请求从账号表解析：降权后旧凭据必须立刻失去管理能力，不能靠 token 里的快照
        String role = roles.roleOf(found.userId()).orElse("");
        request.setAttribute(ATTR_USER_ID, found.userId());
        request.setAttribute(ATTR_TOKEN_ID, found.tokenId());
        request.setAttribute(ATTR_SCOPES, found.scopes());
        request.setAttribute(ATTR_TOOLSETS, found.toolsets());
        request.setAttribute(ATTR_ROLE, role);
        request.setAttribute(ATTR_DAILY_CALL_LIMIT, found.dailyCallLimit());

        var authentication = new UsernamePasswordAuthenticationToken(
                String.valueOf(found.userId()), null,
                Set.of(new SimpleGrantedAuthority(
                        "admin".equalsIgnoreCase(role) ? "ROLE_ADMIN" : "ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        // 不写 WWW-Authenticate 的 scope 提示：我们不提供 OAuth 发现流程，带它只会诱导客户端去尝试
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
