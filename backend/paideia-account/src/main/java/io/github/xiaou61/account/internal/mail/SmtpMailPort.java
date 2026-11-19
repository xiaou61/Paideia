package io.github.xiaou61.account.internal.mail;

import java.time.Duration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * 真实 SMTP 实现。
 *
 * <p>用 {@code JavaMailSenderImpl} 手工装配而不是依赖 Boot 的 {@code spring.mail.*} 自动配置：
 * 本项目的配置命名空间统一是 {@code paideia.*}，把邮件参数放进这里可以避免两套配置风格并存。
 *
 * <p><b>凭据只来自配置</b>（本地配置文件或环境变量），不写进仓库、不进日志。
 * 认证用 SMTP 授权码而不是邮箱登录密码——多数邮箱服务只接受授权码（QQ 邮箱就是如此）。
 */
public final class SmtpMailPort implements MailPort {

    private final JavaMailSenderImpl sender;
    private final MailProperties properties;

    SmtpMailPort(MailProperties properties) {
        this.properties = properties;
        JavaMailSenderImpl configured = new JavaMailSenderImpl();
        configured.setHost(properties.host());
        configured.setPort(properties.port());
        configured.setUsername(properties.username());
        configured.setPassword(properties.password());
        configured.setDefaultEncoding("UTF-8");

        var javaMail = configured.getJavaMailProperties();
        javaMail.put("mail.smtp.auth", String.valueOf(properties.hasCredentials()));
        // 两种加密方式互斥：465 用隐式 TLS，587 用 STARTTLS
        javaMail.put("mail.smtp.ssl.enable", String.valueOf(properties.ssl()));
        javaMail.put("mail.smtp.starttls.enable", String.valueOf(properties.startTls()));
        javaMail.put("mail.smtp.connectiontimeout", "10000");
        javaMail.put("mail.smtp.timeout", "15000");
        // 认证失败时把服务端的原始响应带进异常，否则只看到一句 "Authentication failed" 无从排查
        javaMail.put("mail.debug", "false");
        this.sender = configured;
    }

    @Override
    public void sendVerificationCode(String to, String code, Duration validFor) {
        try {
            var message = sender.createMimeMessage();
            // 第二个参数 false 表示非 multipart；UTF-8 是必须的，否则中文主题会乱码
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.fromAddress(), properties.fromName());
            helper.setTo(to);
            helper.setSubject("Paideia 注册验证码");
            helper.setText(body(code, validFor), false);
            sender.send(message);
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException
                | org.springframework.mail.MailException exception) {
            // 发不出去就必须让注册流程失败：悄悄吞掉会让用户卡在"没收到验证码"而无人知道原因。
            // 异常消息里带上主机与端口（不含凭据），方便判断是配置问题还是网络问题。
            //
            // MailException 必须一并接住：sender.send() 抛的是 Spring 包装过的运行时异常
            // （MessagingException 被包进 MailSendException），只 catch 上面两种的话，
            // 真正发信失败时冒出去的是原始异常，既不带上主机端口、也不进这条统一的说明。
            throw new IllegalStateException(
                    "通过 %s:%d 发送验证码邮件失败：%s".formatted(
                            properties.host(), properties.port(), exception.getMessage()),
                    exception);
        }
    }

    private static String body(String code, Duration validFor) {
        return """
                你正在注册 Paideia 账号。

                验证码：%s
                有效期：%d 分钟。

                如果这不是你本人的操作，忽略此邮件即可。""".formatted(code, validFor.toMinutes());
    }

    @Override
    public void sendPasswordReset(String to, String resetUrl, Duration validFor) {
        try {
            var message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.fromAddress(), properties.fromName());
            helper.setTo(to);
            helper.setSubject("Paideia 重置密码");
            helper.setText(resetBody(resetUrl, validFor), false);
            sender.send(message);
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException
                | org.springframework.mail.MailException exception) {
            throw new IllegalStateException(
                    "通过 %s:%d 发送重置密码邮件失败：%s".formatted(
                            properties.host(), properties.port(), exception.getMessage()),
                    exception);
        }
    }

    private static String resetBody(String resetUrl, Duration validFor) {
        return """
                有人（希望是你）申请重置 Paideia 账号的密码。

                请打开下面的链接设置新密码，链接 %d 分钟内有效，且只能用一次：

                %s

                如果这不是你本人的操作，忽略此邮件即可——你的密码不会被改动，
                但建议你登录后检查一下账号。""".formatted(validFor.toMinutes(), resetUrl);
    }
}
