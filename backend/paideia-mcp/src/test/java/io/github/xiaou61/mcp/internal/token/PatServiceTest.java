package io.github.xiaou61.mcp.internal.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * PAT 的核心纪律：**明文不落库**、过期与吊销都判在校验里、别人吊销不到我的。
 *
 * <p>用假 mapper 而不是真库：这些是纯判定，不需要 MySQL；真正"删干净了没有"由集成测试盯。
 */
class PatServiceTest {

    private final McpTokenMapper mapper = mock(McpTokenMapper.class);
    private final PatService service = new PatService(mapper);

    @Test
    void storesOnlyHashAndPrefix() {
        AtomicReference<McpAccessToken> saved = new AtomicReference<>();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            McpAccessToken token = invocation.getArgument(0);
            token.setId(1L);
            saved.set(token);
            return 1;
        });

        PatService.CreatedToken created = service.create(7L, "笔记本", Set.of(ToolScope.READ), Set.of(), 90, null, "learner");

        assertThat(created.plaintext()).startsWith(PatService.TOKEN_PREFIX);
        assertThat(created.plaintext().length()).isGreaterThan(PatService.TOKEN_PREFIX.length() + 30);
        assertThat(saved.get().getTokenHash()).hasSize(64).isNotEqualTo(created.plaintext());
        assertThat(saved.get().getTokenPrefix()).isEqualTo(created.plaintext().substring(0, 12));
        assertThat(created.scopes()).containsExactly(ToolScope.READ);
        assertThat(created.expiresAt()).isAfter(Instant.now().plusSeconds(89 * 24 * 3600L));
    }

    @Test
    void nullExpiryMeansNeverExpires() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            ((McpAccessToken) invocation.getArgument(0)).setId(1L);
            return 1;
        });

        assertThat(service.create(7L, "长期", Set.of(ToolScope.READ), Set.of(), null, null, "learner").expiresAt()).isNull();
    }

    @Test
    void rejectsBlankNameAndMissingScopes() {
        assertThatThrownBy(() -> service.create(7L, "  ", Set.of(ToolScope.READ), Set.of(), 90, null, "learner"))
                .isInstanceOf(BizException.class).hasMessageContaining("名称");
        assertThatThrownBy(() -> service.create(7L, "x", Set.of(), Set.of(), 90, null, "learner"))
                .isInstanceOf(BizException.class).hasMessageContaining("权限范围");
        // 管理能力是两把锁：非管理员申请 admin scope 必须被拒，不能静默降级
        assertThatThrownBy(() -> service.create(7L, "x", Set.of(ToolScope.ADMIN), Set.of(), 90, null, "learner"))
                .isInstanceOf(BizException.class).hasMessageContaining("管理员");
        verify(mapper, never()).insert(any());
    }

    @Test
    void adminScopeIsIssuedOnlyToAdminRole() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            ((McpAccessToken) invocation.getArgument(0)).setId(1L);
            return 1;
        });

        PatService.CreatedToken created = service.create(7L, "管理", Set.of(ToolScope.ADMIN),
                Set.of("admin_users"), 90, null, "admin");

        assertThat(created.scopes()).containsExactly(ToolScope.ADMIN);
        assertThat(created.toolsets()).containsExactly("admin_users");
    }

    @Test
    void authenticateAcceptsActiveToken() {
        String token = PatService.TOKEN_PREFIX + "abcdefghijklmnopqrstuvwxyz0123456789";
        when(mapper.findByHash(any())).thenReturn(row(token, Instant.now().plusSeconds(3600), null, null));

        assertThat(service.authenticate(token)).isPresent()
                .get().extracting(PatService.PatPrincipal::userId).isEqualTo(7L);
    }

    @Test
    void authenticateRejectsWrongShapeAndUnknownToken() {
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("Bearer something")).isEmpty();
        when(mapper.findByHash(any())).thenReturn(null);
        assertThat(service.authenticate(PatService.TOKEN_PREFIX + "missingmissingmissingmissing1234")).isEmpty();
    }

    @Test
    void authenticateRejectsExpiredAndRevoked() {
        String token = PatService.TOKEN_PREFIX + "abcdefghijklmnopqrstuvwxyz0123456789";
        when(mapper.findByHash(any()))
                .thenReturn(row(token, Instant.now().minusSeconds(1), null, null))
                .thenReturn(row(token, Instant.now().plusSeconds(60), Instant.now(), null));

        assertThat(service.authenticate(token)).as("已过期").isEmpty();
        assertThat(service.authenticate(token)).as("已吊销").isEmpty();
    }

    @Test
    void authenticateTouchesLastUsedAtMostOncePerMinute() {
        String token = PatService.TOKEN_PREFIX + "abcdefghijklmnopqrstuvwxyz0123456789";
        // 桩要模拟"写入后能读回"：真 mapper 每次查库，第一次 touch 之后库里就有值了。
        // 桩永远返回 null 的话，服务每次都会正确地再写一次——那测的就不是节流了。
        AtomicReference<McpAccessToken> stored = new AtomicReference<>(row(token, null, null, null));
        when(mapper.findByHash(any())).thenAnswer(invocation -> stored.get());
        when(mapper.touchLastUsed(anyLong(), any())).thenAnswer(invocation -> {
            stored.get().setLastUsedAt(invocation.getArgument(1));
            return 1;
        });

        service.authenticate(token);
        service.authenticate(token);

        // 第一次写（LastUsed 为 null），第二次读到刚写的时间、不超过 60 秒，因此不再写
        verify(mapper, org.mockito.Mockito.times(1)).touchLastUsed(anyLong(), any());
    }

    @Test
    void revokeIsScopedToOwner() {
        when(mapper.revoke(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any())).thenReturn(0);

        assertThatThrownBy(() -> service.revoke(7L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void listNeverExposesHash() {
        when(mapper.listByUser(7L)).thenReturn(List.of(row(
                PatService.TOKEN_PREFIX + "abcdefghijklmnopqrstuvwxyz0123456789", null, null, null)));

        PatService.TokenView view = service.list(7L).get(0);

        // 前缀是**展示用**的（本来就该能看见），但不能出现完整 token，也不能出现哈希
        assertThat(view.prefix()).startsWith(PatService.TOKEN_PREFIX);
        assertThat(view.prefix()).isNotEqualTo("pd_mcp_abcdefghijklmnopqrstuvwxyz0123456789");
        assertThat(view.toString()).doesNotContain("tokenHash").doesNotContain("0123456789");
    }

    // ---------------------------------------------------------------- WORK-044

    /** 凭据数量上限：第 21 个被拒。 */
    @Test
    void rejectsTwentyFirstToken() {
        when(mapper.countActiveByUser(7L)).thenReturn(PatService.MAX_TOKENS_PER_USER);

        assertThatThrownBy(() -> service.create(7L, "多一个", Set.of(ToolScope.READ), Set.of(),
                90, null, "learner"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(String.valueOf(PatService.MAX_TOKENS_PER_USER));
        verify(mapper, never()).insert(any());
    }

    /** 吊销过的凭据不占名额——否则换设备时得先清理历史。 */
    @Test
    void revokedTokensDoNotCountTowardLimit() {
        // countActiveByUser 只数 revoked_at IS NULL 的行，19 个活凭据可以再建一个
        when(mapper.countActiveByUser(7L)).thenReturn(19);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            ((McpAccessToken) invocation.getArgument(0)).setId(20L);
            return 1;
        });

        PatService.CreatedToken created = service.create(7L, "第 20 个", Set.of(ToolScope.READ),
                Set.of(), 90, null, "learner");

        assertThat(created.id()).isEqualTo(20L);
    }

    /** 每日上限：0 与 null 都表示不限，负数与超上界被拒。 */
    @Test
    void normalizesDailyCallLimit() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            ((McpAccessToken) invocation.getArgument(0)).setId(1L);
            return 1;
        });

        assertThat(service.create(7L, "零", Set.of(ToolScope.READ), Set.of(), 90, 0, "learner")
                .dailyCallLimit()).isNull();
        assertThat(service.create(7L, "空", Set.of(ToolScope.READ), Set.of(), 90, null, "learner")
                .dailyCallLimit()).isNull();
        assertThat(service.create(7L, "有", Set.of(ToolScope.READ), Set.of(), 90, 100, "learner")
                .dailyCallLimit()).isEqualTo(100);

        assertThatThrownBy(() -> service.create(7L, "负", Set.of(ToolScope.READ), Set.of(),
                90, -1, "learner"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("负数");
        assertThatThrownBy(() -> service.create(7L, "大", Set.of(ToolScope.READ), Set.of(),
                90, 100_001, "learner"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能超过");
    }

    /** 上限随身份一起返回，供配额判定使用。 */
    @Test
    void authenticateCarriesDailyCallLimit() {
        String token = PatService.TOKEN_PREFIX + "abcdefghijklmnopqrstuvwxyz0123456789";
        McpAccessToken stored = row(token, Instant.now().plusSeconds(3600), null, null);
        stored.setDailyCallLimit(50);
        when(mapper.findByHash(any())).thenReturn(stored);

        assertThat(service.authenticate(token)).isPresent()
                .get().extracting(PatService.PatPrincipal::dailyCallLimit).isEqualTo(50);
    }

    private static McpAccessToken row(String plaintext, Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {        McpAccessToken token = new McpAccessToken();
        token.setId(1L);
        token.setUserId(7L);
        token.setName("测试");
        token.setTokenPrefix(plaintext.substring(0, Math.min(12, plaintext.length())));
        token.setTokenHash(PatService.hash(plaintext));
        token.setScopes("read");
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        token.setLastUsedAt(lastUsedAt);
        token.setCreatedAt(Instant.now());
        return token;
    }
}
