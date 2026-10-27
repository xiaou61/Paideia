package io.github.xiaou61.mcp.internal.token;

import java.time.Instant;

/**
 * `mcp_access_tokens` 的一行。
 *
 * <p>可变类 + {@code useGeneratedKeys}，与项目其它模块一致。**没有明文 token 字段**：
 * 这个类从头到尾都没有机会持有凭据本身，减少一处可能被日志打出来的东西。
 */
class McpAccessToken {

    private Long id;
    private Long userId;
    private String name;
    private String tokenPrefix;
    private String tokenHash;
    /** "read" 或 "read,write"；解析在服务层。 */
    private String scopes;
    /** 工具组白名单（逗号分隔）；**null = 不限制**，与本次改动前的语义一致。 */
    private String toolsets;
    /** 每日调用上限；**null = 不限**（WORK-044）。 */
    private Integer dailyCallLimit;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant revokedAt;
    private Instant createdAt;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    Long getUserId() {
        return userId;
    }

    void setUserId(Long userId) {
        this.userId = userId;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    String getTokenPrefix() {
        return tokenPrefix;
    }

    void setTokenPrefix(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    String getTokenHash() {
        return tokenHash;
    }

    void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    String getScopes() {
        return scopes;
    }

    void setScopes(String scopes) {
        this.scopes = scopes;
    }

    String getToolsets() {
        return toolsets;
    }

    void setToolsets(String toolsets) {
        this.toolsets = toolsets;
    }

    Integer getDailyCallLimit() {
        return dailyCallLimit;
    }

    void setDailyCallLimit(Integer dailyCallLimit) {
        this.dailyCallLimit = dailyCallLimit;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    Instant getLastUsedAt() {
        return lastUsedAt;
    }

    void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }

    void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
