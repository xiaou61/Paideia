package io.github.xiaou61.account.internal.mail;

import java.time.Duration;

/**
 * 邮件发送端口。业务代码只依赖它，不依赖具体供应商。
 *
 * <p>把发信收在端口后面有一个直接好处：**注册链路的正确性不依赖真实 SMTP**。
 * 集成测试注入内存信箱实现，就能断言"验证码是不是发出去了、内容对不对"，
 * 因此 SMTP 凭据可以最后再配，中间任何一步验证都不必等它。
 */
public interface MailPort {

    /**
     * 发送注册用的邮箱验证码。
     *
     * @param to      收件地址
     * @param code    验证码明文（**只出现在这里，不得写日志**）
     * @param validFor 有效期，用于文案
     */
    void sendVerificationCode(String to, String code, Duration validFor);

    /**
     * 发送"重置口令"的邮件，正文里是一条一次性链接。
     *
     * @param to       收件地址
     * @param resetUrl 完整链接（**含令牌明文，只出现在这里，不得写日志**）
     * @param validFor 生效期，用于文案
     */
    void sendPasswordReset(String to, String resetUrl, Duration validFor);
}
