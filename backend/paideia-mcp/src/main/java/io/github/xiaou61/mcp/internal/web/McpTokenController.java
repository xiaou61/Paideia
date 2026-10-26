package io.github.xiaou61.mcp.internal.web;

import io.github.xiaou61.mcp.internal.token.PatService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.platform.ToolScope;
import io.github.xiaou61.security.CurrentUser;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 凭据管理（WORK-019 REQ-015 / REQ-025）。
 *
 * <p>三个端点、一份归属：列表与吊销都带当前用户 id，拿不到就是同一条 404。
 *
 * <p><b>创建响应里是唯一一次出现明文的地方</b>：之后的列表只给前缀。这不是"前端记得别缓存"，
 * 而是服务端根本不保存明文——刷新之后拿不回来，这是设计而不是缺陷。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/ai/mcp-tokens")
class McpTokenController {

    private final PatService tokens;

    McpTokenController(PatService tokens) {
        this.tokens = tokens;
    }

    record CreateRequest(String name, List<String> scopes, List<String> toolsets, Integer expiresInDays) {
    }

    /** 创建响应：**只此一次**带明文。`toolsets` 空表示不限制工具组。 */
    record CreatedView(long id, String token, String prefix, List<String> scopes, List<String> toolsets,
                       Instant expiresAt) {
    }

    record TokenView(long id, String name, String prefix, List<String> scopes, List<String> toolsets,
                     Instant expiresAt, Instant lastUsedAt, Instant revokedAt, Instant createdAt, boolean active) {
    }

    @PostMapping
    ApiResponse<CreatedView> create(@RequestBody CreateRequest request) {
        long userId = requester();
        Set<ToolScope> scopes = parse(request == null ? null : request.scopes());
        // 默认 90 天（用户裁决）；显式传 null 表示永不过期——两者在请求里都表现为字段缺失，
        // 所以用 0 表示"永不过期"这个约定必须写清，否则没法表达。
        Integer days = request == null ? null : request.expiresInDays();
        Integer effectiveDays = Integer.valueOf(0).equals(days) ? null
                : (days == null ? PatService.DEFAULT_EXPIRES_IN_DAYS : days);
        Set<String> toolsets = parseToolsets(request == null ? null : request.toolsets());
        var created = tokens.create(userId, request == null ? null : request.name(), scopes,
                toolsets, effectiveDays, CurrentUser.role());
        return ApiResponse.ok(new CreatedView(created.id(), created.plaintext(), created.prefix(),
                created.scopes().stream().map(ToolScope::key).sorted().toList(),
                created.toolsets().stream().sorted().toList(), created.expiresAt()));
    }

    @GetMapping
    ApiResponse<List<TokenView>> list() {
        Instant now = Instant.now();
        return ApiResponse.ok(tokens.list(requester()).stream()
                .map(view -> new TokenView(view.id(), view.name(), view.prefix(),
                        view.scopes().stream().map(ToolScope::key).sorted().toList(),
                        view.toolsets().stream().sorted().toList(),
                        view.expiresAt(), view.lastUsedAt(), view.revokedAt(), view.createdAt(),
                        view.active(now)))
                .toList());
    }

    /**
     * 工具组白名单。**不校验组名是否存在**：组来自 `ToolCatalog`，而这是 account/mcp 之外的
     * 装配期知识；传一个不存在的组只会让这张凭据看起来什么都不能调（安全侧失败），不会放大权限。
     * 这样也避免让「新增一个工具组」必须改动这里。
     */
    private static Set<String> parseToolsets(List<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return Set.copyOf(result);
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> revoke(@PathVariable("id") long id) {
        tokens.revoke(requester(), id);
        return ApiResponse.ok(null);
    }

    private static Set<ToolScope> parse(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<ToolScope> scopes = new LinkedHashSet<>();
        for (String value : raw) {
            ToolScope scope = ToolScope.of(value);
            if (scope == null) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "不支持的权限范围：" + value);
            }
            scopes.add(scope);
        }
        return scopes;
    }

    private static long requester() {
        try {
            return Long.parseLong(CurrentUser.subjectId());
        } catch (NumberFormatException | BizException failure) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "令牌主体不是有效的账号标识");
        }
    }
}
