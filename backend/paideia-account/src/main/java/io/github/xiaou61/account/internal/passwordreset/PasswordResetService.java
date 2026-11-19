package io.github.xiaou61.account.internal.passwordreset;

import io.github.xiaou61.account.internal.credential.CaptchaService;
import io.github.xiaou61.account.internal.mail.MailPort;
import io.github.xiaou61.account.internal.support.Digest;
import io.github.xiaou61.account.internal.support.RateLimiter;
import io.github.xiaou61.account.internal.token.RefreshTokenMapper;
import io.github.xiaou61.account.internal.user.PasswordHasher;
import io.github.xiaou61.account.internal.user.User;
import io.github.xiaou61.account.internal.user.UserMapper;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 忘记密码：发一封带一次性链接的邮件，凭链接换一个新口令。
 *
 * <p><b>为什么是链接而不是"把新口令发给你"</b>：邮件在到达前会经过很多跳，平文口令一旦
 * 落在一个邮箱里就永久有效；一次性、会过期的链接即使被转发出去，用过一次也就废了。
 *
 * <p><b>三个不泄漏账号是否存在的设计</b>：
 *
 * <ol>
 *   <li>邮箱不存在时不报错、也不发信，接口一律返回成功。前端因此只能提示"如果该邮箱注册过"。</li>
 *   <li>发信前必须先过图形验证码，机器批量试探的成本被抬到"每次都要认一张图"。</li>
 *   <li>三条限流（同邮箱间隔、同邮箱每小时、同来源每小时）按邮箱与来源分别计，
 *       与注册验证码那套是同一条理由：没有它们，这个接口就是替别人烧发信配额的工具。</li>
 * </ol>
 *
 * <p>剩下的差别只有耗时（查不到邮箱时更快、发信那一路还要等 SMTP）。这不构成可用的
 * 枚举手段：拿到"是/否"仍需要先过图形验证码与限流，而这条信息本身也只够让他多发几封邮件。
 *
 * <p><b>改口令会吊销该用户全部 refresh 令牌</b>：重置口令的典型场景就是"账号可能被别人
 * 动过"，此时把所有已登录会话踢下线是必须的，否则改完口令对方照样能用旧的 refresh 续期。
 * 已签发的 access 是无状态 JWT，只能等它自己过期（最长 15 分钟）——与登出同一个取舍。
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** 链接有效期。比注册验证码长（用户要去开邮箱），但短到"一夜之间被翻出来"也没用。 */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(60);
    private static final int SENDS_PER_EMAIL_PER_HOUR = 5;
    private static final int SENDS_PER_SOURCE_PER_HOUR = 20;
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    /** 32 字节 = 256 位：与 refresh 令牌同一强度，安全性来自不可猜测而不是签名。 */
    private static final int TOKEN_BYTES = 32;
    /** 前端路由。加参数时注意 HashRouter：路径在 # 之后，查询串也在 # 之后。 */
    private static final String RESET_PATH = "/#/reset-password?token=";

    private final PasswordResetTokenMapper tokens;
    private final UserMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final RefreshTokenMapper refreshTokenMapper;
    private final CaptchaService captchaService;
    private final RateLimiter rateLimiter;
    private final MailPort mailPort;
    private final AccountWebProperties webProperties;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean fallbackWarningLogged = new AtomicBoolean();

    PasswordResetService(PasswordResetTokenMapper tokens,
                         UserMapper userMapper,
                         PasswordHasher passwordHasher,
                         RefreshTokenMapper refreshTokenMapper,
                         CaptchaService captchaService,
                         RateLimiter rateLimiter,
                         MailPort mailPort,
                         AccountWebProperties webProperties) {
        this.tokens = tokens;
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.refreshTokenMapper = refreshTokenMapper;
        this.captchaService = captchaService;
        this.rateLimiter = rateLimiter;
        this.mailPort = mailPort;
        this.webProperties = webProperties;
    }

    /**
     * 申请一枚重置链接。
     *
     * <p>无论邮箱是否注册过都正常返回：这条端点的返回值不该能回答"这个邮箱有没有账号"。
     *
     * @param requestBaseUrl 配置为空时用来兜底拼链接的请求地址（见 {@link AccountWebProperties}）
     */
    public void request(String email, String captchaId, String captchaAnswer,
                        String sourceAddress, String requestBaseUrl) {
        captchaService.verifyAndConsume(captchaId, captchaAnswer);

        String emailKey = hashOf(email);
        rateLimiter.requireInterval("password-reset:" + emailKey, SEND_INTERVAL);
        rateLimiter.requireUnderLimit("password-reset-hour:" + emailKey, SENDS_PER_EMAIL_PER_HOUR, ONE_HOUR);
        rateLimiter.requireUnderLimit(
                "password-reset-source-hour:" + hashOf(sourceAddress), SENDS_PER_SOURCE_PER_HOUR, ONE_HOUR);

        Optional<User> found = userMapper.findByEmail(email.trim());
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        Instant now = Instant.now();

        // 先作废旧令牌再发新的：否则申请 N 次就有 N 个可用链接
        tokens.invalidateActiveForUser(user.getId(), now);

        String rawToken = newToken();
        tokens.insert(user.getId(), Digest.sha256Hex(rawToken), now.plus(TOKEN_TTL));

        // 发信失败按异常向上抛：与注册同一条理由——静默成功会让用户永远等一封不会来的邮件
        mailPort.sendPasswordReset(user.getEmail(), link(requestBaseUrl, rawToken), TOKEN_TTL);
    }

    /**
     * 用链接里的令牌换掉口令。
     *
     * <p>整个方法在一个事务里：令牌消费、口令写入、令牌吊销要么全成要么全不成。
     * 中途失败时消费也回滚，用户手上的链接仍然有效，可以重试。
     *
     * @throws BizException 令牌不存在、已用过、或已过期
     */
    @Transactional
    public void confirm(String rawToken, String newPassword) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty()) {
            throw invalid();
        }
        String tokenHash = Digest.sha256Hex(token);
        Instant now = Instant.now();

        // 带条件的 UPDATE 是唯一的准入闸：抢不到就说明这枚令牌已经被用过或过期了
        if (tokens.consume(tokenHash, now) == 0) {
            throw invalid();
        }
        Long userId = tokens.findUserIdByHash(tokenHash).orElseThrow(PasswordResetService::invalid);
        User user = userMapper.findById(userId).orElseThrow(PasswordResetService::invalid);

        userMapper.updatePasswordHash(user.getId(), passwordHasher.hash(newPassword));
        // 口令变了，旧会话必须失效：否则"改口令把对方踢下线"这一半目的做不到
        refreshTokenMapper.revokeAllForUser(user.getId(), now);
    }

    private String link(String requestBaseUrl, String rawToken) {
        String base = webProperties.configured() ? webProperties.baseUrl() : requestBaseUrl;
        if (!webProperties.configured() && fallbackWarningLogged.compareAndSet(false, true)) {
            log.warn("""
                    未配置 paideia.account.web.base-url，重置链接改用请求域名拼装（当前：{}）。
                    这允许攻击者用伪造的 Host 头把链接指向自己的站点，只适合本地开发；
                    任何他人可访问的环境都必须显式配置该地址。""", requestBaseUrl);
        }
        String normalized = base == null ? "" : base.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + RESET_PATH + rawToken;
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashOf(String value) {
        return Digest.sha256Hex(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
    }

    private static BizException invalid() {
        return new BizException(ErrorCode.INVALID_ARGUMENT, "重置链接无效或已过期，请重新申请");
    }
}
