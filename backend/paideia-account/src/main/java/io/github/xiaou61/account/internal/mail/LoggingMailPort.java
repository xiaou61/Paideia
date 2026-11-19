package io.github.xiaou61.account.internal.mail;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 未配置 SMTP、且**显式开启**了日志降级时的实现：把验证码写进日志，不出网。
 *
 * <p>用途只有一个——本地开发与自我验证时不必先配一个邮箱账号。
 *
 * <p><b>它是一条真实的风险线</b>：验证码落在日志里，任何能看到日志的人都能注册他人邮箱。
 * 所以它不再自动启用——装配层只在 {@code paideia.mail.allow-log-fallback=true} 时才用它，
 * 缺 host 又不显式开启时直接让应用启动失败（见 {@link MailConfiguration}）。
 * 误配到他人可访问的环境不该表现为"一切正常"。
 */
public final class LoggingMailPort implements MailPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailPort.class);

    @Override
    public void sendVerificationCode(String to, String code, Duration validFor) {
        log.warn("""
                paideia.mail.allow-log-fallback=true 已显式开启，验证码改为写日志：收件人={}，验证码={}，有效期={} 分钟。
                这只适用于本地开发；部署到任何他人可访问的环境前必须关闭该开关并配置真实 SMTP。""",
                to, code, validFor.toMinutes());
    }

    @Override
    public void sendPasswordReset(String to, String resetUrl, Duration validFor) {
        log.warn("""
                paideia.mail.allow-log-fallback=true 已显式开启，重置链接改为写日志：收件人={}，链接={}，有效期={} 分钟。
                这只适用于本地开发；部署到任何他人可访问的环境前必须关闭该开关并配置真实 SMTP。""",
                to, resetUrl, validFor.toMinutes());
    }
}
