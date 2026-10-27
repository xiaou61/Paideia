package io.github.xiaou61.mcp.internal.token;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.platform.ToolScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 外部接入凭据（WORK-019 REQ-015/016）。
 *
 * <p>三条设计要求，都在这个类里落地：
 * <ul>
 *   <li><b>明文只出现一次</b>：创建时返回，之后库里只有 sha256 与展示前缀；</li>
 *   <li><b>恒定时间比较</b>：查到行之后仍用 {@link MessageDigest#isEqual} 比一次哈希，避免
 *       "比较耗时泄露前缀"这类侧信道；</li>
 *   <li><b>过期与吊销都在校验里判</b>：不依赖定时任务清理，读到的每一行都自己算一次有效性。</li>
 * </ul>
 *
 * <p>token 形状 `pd_mcp_` + 32 字节 base64url（43 字符）。前缀固定是刻意的：日志、代码扫描与
 * 泄露通报都能一眼认出这是我们的 MCP 凭据，而不是把它当成一个随机串。
 */
@Service
public class PatService {

    /** 明文前缀。长度不算在随机部分里。 */
    static final String TOKEN_PREFIX = "pd_mcp_";

    /** 展示用的前缀长度（含 pd_mcp_）。**不足以暴力猜出 token**。 */
    private static final int DISPLAY_PREFIX_LENGTH = 12;

    private static final int RANDOM_BYTES = 32;

    /** 默认有效期：90 天（用户裁决 2026-10-18）；传 null 表示永不过期。 */
    public static final int DEFAULT_EXPIRES_IN_DAYS = 90;

    /**
     * 每用户凭据数量上限（WORK-044 REQ-010）。
     *
     * <p>单机个人项目里这是零成本护栏：没有它，一个写错的客户端可以无限建凭据，
     * 而每条凭据都是一把能用的钥匙。
     */
    public static final int MAX_TOKENS_PER_USER = 20;

    /** 每日调用上限的上界。防手滑写成 8 位数。 */
    static final int MAX_DAILY_CALL_LIMIT = 100_000;

    /** 最后使用时间的写入节流：60 秒内同一 token 只写一次。 */
    private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(60);

    private final McpTokenMapper tokens;
    private final SecureRandom random = new SecureRandom();

    PatService(McpTokenMapper tokens) {
        this.tokens = tokens;
    }

    /** 创建结果。**明文只在这里出现一次**。 */
    public record CreatedToken(long id, String plaintext, String prefix, Set<ToolScope> scopes,
                               Set<String> toolsets, Integer dailyCallLimit, Instant expiresAt) {
    }

    /** 列表项：不含 hash，也不含明文。`toolsets` 为空表示不限制工具组。 */
    public record TokenView(long id, String name, String prefix, Set<ToolScope> scopes,
                            Set<String> toolsets, Integer dailyCallLimit,
                            Instant expiresAt, Instant lastUsedAt, Instant revokedAt, Instant createdAt) {

        public boolean active(Instant now) {
            return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
        }
    }

    /** 校验通过后的身份。**userId 只从这里来**。`dailyCallLimit` 为 null 表示不限。 */
    public record PatPrincipal(long userId, long tokenId, Set<ToolScope> scopes, Set<String> toolsets,
                               Integer dailyCallLimit) {

        /**
         * 这张凭据是否允许某个工具组。
         *
         * <p>**空集合 = 不限制**：本次改动前签发的凭据没有这一列，语义必须与之前一致，
         * 否则线上已有的十几把钥匙会突然只剩一个组。
         */
        public boolean allowsToolset(String toolset) {
            return toolsets.isEmpty() || toolsets.contains(toolset);
        }

        /** 交给视图层的组过滤集合：不限制时返回空集合（`ToolQuery` 里空集合即"不按组过滤"）。 */
        public Set<String> toolsetFilter() {
            return toolsets;
        }
    }

    /**
     * 建一张凭据。
     *
     * @param dailyCallLimit 每日调用上限；null 或 0 表示不限
     * @param callerRole     建凭据的账号角色。管理能力是两把锁：凭据要带 admin scope，账号要是
     *                       ADMIN 角色——这里拒绝非管理员申请 admin scope，不静默降级。
     */
    public CreatedToken create(long userId, String name, Set<ToolScope> scopes,
                               Set<String> toolsets, Integer expiresInDays,
                               Integer dailyCallLimit, String callerRole) {
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "凭据名称不能为空");
        }
        if (trimmedName.length() > 64) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "凭据名称不能超过 64 个字符");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "至少要选择一个权限范围");
        }
        if (scopes.contains(ToolScope.ADMIN) && !"admin".equalsIgnoreCase(callerRole)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "只有管理员可以创建带 admin 权限的凭据");
        }
        Integer limit = normalizeDailyLimit(dailyCallLimit);
        // 只数未吊销的：吊销过的凭据不该占名额，否则换设备时得先清理历史
        if (tokens.countActiveByUser(userId) >= MAX_TOKENS_PER_USER) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "最多只能有 " + MAX_TOKENS_PER_USER + " 个凭据；先吊销不用的再建新的。");
        }

        String plaintext = TOKEN_PREFIX + randomToken();
        Instant now = Instant.now();
        Instant expiresAt = expiresInDays == null
                ? null
                : now.plus(Duration.ofDays(Math.max(1, expiresInDays)));

        McpAccessToken token = new McpAccessToken();
        token.setUserId(userId);
        token.setName(trimmedName);
        token.setTokenPrefix(plaintext.substring(0, DISPLAY_PREFIX_LENGTH));
        token.setTokenHash(hash(plaintext));
        token.setScopes(join(scopes));
        token.setToolsets(toolsets == null || toolsets.isEmpty() ? null : String.join(",", new java.util.TreeSet<>(toolsets)));
        token.setDailyCallLimit(limit);
        token.setExpiresAt(expiresAt);
        token.setCreatedAt(now);
        tokens.insert(token);

        return new CreatedToken(token.getId(), plaintext, token.getTokenPrefix(), Set.copyOf(scopes),
                parseToolsets(token.getToolsets()), limit, expiresAt);
    }

    /**
     * 归一每日上限：`null` 与 `0` 都表示不限。
     *
     * <p>**用 0 表达"不限"是接口约定**：请求体里字段缺失与显式传 null 在 JSON 里无法区分，
     * 所以前端用 0 表示"我不设上限"（与 `expiresInDays` 同一条约定）。
     */
    private static Integer normalizeDailyLimit(Integer raw) {
        if (raw == null || raw == 0) {
            return null;
        }
        if (raw < 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "每日调用上限不能是负数");
        }
        if (raw > MAX_DAILY_CALL_LIMIT) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "每日调用上限不能超过 " + MAX_DAILY_CALL_LIMIT);
        }
        return raw;
    }

    /**
     * 校验一个 Bearer token。
     *
     * <p>五道判定按顺序：格式 → 查表 → 恒定时间比较 → 吊销 → 过期。任何一道不过都返回空，
     * **调用方据此回 401**，不区分"不存在"与"过期"——区分开就等于告诉探测者"这个前缀是真的"。
     */
    public Optional<PatPrincipal> authenticate(String bearer) {
        if (bearer == null || !bearer.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        McpAccessToken row = tokens.findByHash(hash(bearer));
        if (row == null) {
            return Optional.empty();
        }
        // 查到行之后仍比一次：哈希相等是查询条件，这里的比较是为了"即便索引被绕开也不泄露"
        if (!MessageDigest.isEqual(row.getTokenHash().getBytes(StandardCharsets.UTF_8),
                hash(bearer).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (row.getRevokedAt() != null) {
            return Optional.empty();
        }
        if (row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        // 节流：距上次记录超过 60 秒才写，避免每次工具调用都打一次 UPDATE
        if (row.getLastUsedAt() == null || row.getLastUsedAt().isBefore(now.minus(TOUCH_INTERVAL))) {
            tokens.touchLastUsed(row.getId(), now);
        }
        return Optional.of(new PatPrincipal(row.getUserId(), row.getId(), parseScopes(row.getScopes()),
                parseToolsets(row.getToolsets()), row.getDailyCallLimit()));
    }

    public List<TokenView> list(long userId) {
        return tokens.listByUser(userId).stream().map(PatService::toView).toList();
    }

    public void revoke(long userId, long id) {
        if (tokens.revoke(id, userId, Instant.now()) == 0) {
            // 不存在、不是本人的、已经吊销过的——统一一条，不区分原因
            throw new BizException(ErrorCode.NOT_FOUND, "凭据不存在");
        }
    }

    /** 注销级联调用。 */
    public int deleteAllFor(long userId) {
        return tokens.deleteByUser(userId);
    }

    private static TokenView toView(McpAccessToken token) {
        return new TokenView(token.getId(), token.getName(), token.getTokenPrefix(), parseScopes(token.getScopes()),
                parseToolsets(token.getToolsets()), token.getDailyCallLimit(),
                token.getExpiresAt(), token.getLastUsedAt(), token.getRevokedAt(), token.getCreatedAt());
    }

    static Set<ToolScope> parseScopes(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<ToolScope> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            ToolScope scope = ToolScope.of(part);
            if (scope != null) {
                result.add(scope);
            }
        }
        return Set.copyOf(result);
    }

    /** `toolsets` 列 → 集合。空白与 null 都是"不限制"。 */
    static Set<String> parseToolsets(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Set.copyOf(result);
    }

    private static String join(Set<ToolScope> scopes) {
        return scopes.stream().map(ToolScope::key).sorted().reduce((a, b) -> a + "," + b).orElse("read");
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** sha256 十六进制。不用加盐：token 本身是 32 字节随机，字典攻击没有意义。 */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 是 JDK 必须提供的算法；走到这里说明运行环境坏了，继续下去没有意义
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
