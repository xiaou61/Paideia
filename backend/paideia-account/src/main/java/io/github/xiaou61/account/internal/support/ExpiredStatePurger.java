package io.github.xiaou61.account.internal.support;

import io.github.xiaou61.account.internal.credential.VerificationCode;
import io.github.xiaou61.account.internal.passwordreset.PasswordResetTokenMapper;
import io.github.xiaou61.account.internal.token.RefreshTokenMapper;
import io.github.xiaou61.platform.ScheduledTaskRecorder;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期清理进程内的过期状态，以及库里已过期的刷新令牌。
 *
 * <p>惰性清理已经能保证内存里的存储不无界增长（读取时顺手删过期项），这里只是把过期项更早还回去。
 * 库里的 {@code refresh_tokens} 与 {@code password_reset_tokens} 则**只有这里清理**：
 * 没有这两条语句，那两张表就是只进不出。
 *
 * <p>状态从构造注入而不是调用装配类的方法：装配类关掉了 {@code proxyBeanMethods}，
 * 直接调用会拿到新实例，等于每次清理都去清一份空状态——这种 bug 不会报错，只会静默失效。
 *
 * <p>{@code @Scheduled} 是任务基础设施的雏形。项目规划里有独立的异步/定时任务模块，
 * 那个模块建立后把这个清理任务迁过去，不要在这里长成一堆定时任务。
 */
@Component
public class ExpiredStatePurger {

    private static final Logger log = LoggerFactory.getLogger(ExpiredStatePurger.class);

    private final ExpiringStore<String> captchaAnswerStore;
    private final ExpiringStore<VerificationCode> emailCodeStore;
    private final RateLimiter rateLimiter;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordResetTokenMapper passwordResetTokenMapper;
    /** 可空：测试切片与局部启动里没有这个 bean，任务照常跑、只是不留痕。 */
    private final ObjectProvider<ScheduledTaskRecorder> recorder;

    ExpiredStatePurger(ExpiringStore<String> captchaAnswerStore,
                       ExpiringStore<VerificationCode> emailCodeStore,
                       RateLimiter rateLimiter,
                       RefreshTokenMapper refreshTokenMapper,
                       PasswordResetTokenMapper passwordResetTokenMapper,
                       ObjectProvider<ScheduledTaskRecorder> recorder) {
        this.captchaAnswerStore = captchaAnswerStore;
        this.emailCodeStore = emailCodeStore;
        this.rateLimiter = rateLimiter;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordResetTokenMapper = passwordResetTokenMapper;
        this.recorder = recorder;
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    void purge() {
        long startedAt = System.nanoTime();
        try {
            int removed = captchaAnswerStore.purgeExpired()
                    + emailCodeStore.purgeExpired()
                    + rateLimiter.purgeExpired();
            int expiredTokens = refreshTokenMapper.deleteExpiredBefore(Instant.now());
            int expiredResetTokens = passwordResetTokenMapper.deleteExpiredBefore(Instant.now());
            if (removed > 0 || expiredTokens > 0 || expiredResetTokens > 0) {
                log.debug("清理过期的验证码与限流记录 {} 条；过期的刷新令牌 {} 条；过期的重置口令令牌 {} 条",
                        removed, expiredTokens, expiredResetTokens);
            }
            record(startedAt, null);
        } catch (RuntimeException e) {
            // 先记失败再抛出：@Scheduled 只会打一行错误，不留记录的话"上次什么时候失败过"无从查起
            record(startedAt, e);
            throw e;
        }
    }

    private void record(long startedAt, Throwable error) {
        ScheduledTaskRecorder target = recorder.getIfAvailable();
        if (target != null) {
            target.record("expired-state-purger",
                    Duration.ofNanos(System.nanoTime() - startedAt), error);
        }
    }
}
