package io.github.xiaou61.account.internal.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.account.internal.support.RateLimiter;
import io.github.xiaou61.account.internal.token.TokenService;
import io.github.xiaou61.account.internal.user.PasswordHasher;
import io.github.xiaou61.account.internal.user.User;
import io.github.xiaou61.account.internal.user.UserMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 登录链路里那些**行为断言看不见**的约束（WORK-037）。
 *
 * <p>为什么不用集成测试守：无密码账号与"密码错"给出的是同一句话、同一个错误码，
 * 集成测试看到的两条路径完全一样——它测不出差别，也就不会因为修复被删掉而变红。
 * 真正的差别是"有没有跑一次 BCrypt"，只能在这一层钉住。
 */
class LoginServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final TokenService tokenService = mock(TokenService.class);
    private final RateLimiter rateLimiter = new RateLimiter(1_000, Clock.systemUTC());
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final LoginService loginService =
            new LoginService(userMapper, passwordHasher, tokenService, rateLimiter, metrics);

    @Test
    @DisplayName("无密码账号也要付出一次假校验的代价，否则响应耗时可枚举第三方账号")
    void passwordlessAccountStillPaysTheTimingCost() {
        User user = new User();
        user.setId(1L);
        user.setUsername("oauth-only");
        user.setEmail(null);
        user.setPasswordHash(null);
        user.setRole("learner");
        when(userMapper.findByIdentifier(anyString())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> loginService.login("oauth-only", "whatever", "10.0.0.1"))
                .isInstanceOf(io.github.xiaou61.platform.BizException.class)
                .hasMessageContaining("用户名或密码不正确");
        assertThat(metrics.get("paideia.login.attempts").tag("outcome", "failure").counter().count())
                .isGreaterThanOrEqualTo(1);

        verify(passwordHasher).wasteTimeLikeAMatch();
        // 没有摘要可比，就不该拿 null 去比——那条路径不跑 BCrypt，会快出一个可观测的差
        verify(passwordHasher, never()).matches(any(), any());
    }
}
