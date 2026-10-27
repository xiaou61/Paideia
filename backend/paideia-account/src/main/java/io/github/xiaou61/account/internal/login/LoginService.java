package io.github.xiaou61.account.internal.login;

import io.github.xiaou61.account.internal.support.Digest;
import io.github.xiaou61.account.internal.support.RateLimiter;
import io.github.xiaou61.account.internal.token.AuthTokens;
import io.github.xiaou61.account.internal.token.TokenService;
import io.github.xiaou61.account.internal.user.PasswordHasher;
import io.github.xiaou61.account.internal.user.User;
import io.github.xiaou61.account.internal.user.UserMapper;
import io.github.xiaou61.security.AuthPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 登录。用户名与邮箱都能作为标识。
 *
 * <p>三条纪律，都是安全敏感路径上不能省的东西：
 *
 * <ul>
 *   <li><b>失败信息不区分原因</b>："账号不存在"与"密码错误"必须给出同一句话，
 *       否则登录接口就成了账号枚举器。</li>
 *   <li><b>耗时也要对齐</b>：账号不存在时对着一个假摘要跑一次同样的 BCrypt 校验，
 *       否则响应快慢本身就泄露了账号是否存在。</li>
 *   <li><b>限流只统计失败</b>：用"每次调用都计数"的写法会导致用户正常登录几次就被封——
 *       所以进门只查、失败才记，成功即清零。</li>
 * </ul>
 */
@Service
public class LoginService {

    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final int FAILURES_PER_IDENTIFIER = 5;
    private static final int FAILURES_PER_SOURCE = 20;

    /** 与账号不存在时保持一致的文案。 */
    private static final String GENERIC_FAILURE = "用户名或密码不正确";

    private final UserMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final RateLimiter rateLimiter;
    private final MeterRegistry metrics;

    LoginService(UserMapper userMapper,
                 PasswordHasher passwordHasher,
                 TokenService tokenService,
                 RateLimiter rateLimiter,
                 MeterRegistry metrics) {
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    /**
     * 登录成功/失败各记一次（WORK-043 SLO）。只区分 success/failure：
     * 失败原因已在日志里，不把错误码做成标签以免基数随错误码增长。
     */
    public AuthTokens login(String identifier, String rawPassword, String sourceAddress) {
        try {
            AuthTokens tokens = doLogin(identifier, rawPassword, sourceAddress);
            metrics.counter("paideia.login.attempts", "outcome", "success").increment();
            return tokens;
        } catch (RuntimeException failure) {
            metrics.counter("paideia.login.attempts", "outcome", "failure").increment();
            throw failure;
        }
    }

    private AuthTokens doLogin(String identifier, String rawPassword, String sourceAddress) {
        String trimmed = identifier.trim();
        String identifierKey = "login-id:" + Digest.sha256Hex(trimmed.toLowerCase(Locale.ROOT));
        String sourceKey = "login-source:" + Digest.sha256Hex(sourceAddress);

        rateLimiter.checkUnderLimit(identifierKey, FAILURES_PER_IDENTIFIER, FAILURE_WINDOW);
        rateLimiter.checkUnderLimit(sourceKey, FAILURES_PER_SOURCE, FAILURE_WINDOW);

        Optional<User> found = userMapper.findByIdentifier(trimmed);
        if (found.isEmpty()) {
            // 关键：账号不存在也要付出同样的代价，否则响应耗时就泄露了账号是否存在
            passwordHasher.wasteTimeLikeAMatch();
            rateLimiter.recordFailure(identifierKey, FAILURE_WINDOW);
            rateLimiter.recordFailure(sourceKey, FAILURE_WINDOW);
            throw AuthPort.unauthenticated(GENERIC_FAILURE);
        }

        User user = found.get();
        // 第三方注册的账号没有密码（WORK-037）。`matches` 对 null 摘要本就返回 false，
        // 但那条路径不跑 BCrypt，响应会明显快于"密码错"——又是一个可枚举的信号。
        // 所以这里显式走一次假校验，与"账号不存在"那条路径同等代价。
        if (user.getPasswordHash() == null) {
            passwordHasher.wasteTimeLikeAMatch();
            rateLimiter.recordFailure(identifierKey, FAILURE_WINDOW);
            rateLimiter.recordFailure(sourceKey, FAILURE_WINDOW);
            throw AuthPort.unauthenticated(GENERIC_FAILURE);
        }
        if (!passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            rateLimiter.recordFailure(identifierKey, FAILURE_WINDOW);
            rateLimiter.recordFailure(sourceKey, FAILURE_WINDOW);
            throw AuthPort.unauthenticated(GENERIC_FAILURE);
        }

        // 成功清掉**标识维度**的失败计数，避免历史失败把正常用户挡在门外。
        //
        // 来源（sourceKey）维度**不清**：它防的是"同一出口横向撞库"，而攻击者只要手里有一个
        // 合法账号，就能每撞 19 次成功登录一次把来源计数清零、无限绕过 FAILURES_PER_SOURCE。
        // NAT/公司出口被历史失败误伤的代价（阈值 20 比单账号的 5 高得多）小于放掉横向撞库。
        rateLimiter.clear(identifierKey);
        return tokenService.issueFor(user);
    }
}
