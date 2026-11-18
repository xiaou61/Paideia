package io.github.xiaou61.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运维诊断邮件用到的 SMTP 传输参数。
 *
 * <p>与 account 模块的 {@code MailProperties} 绑定**同一个前缀** {@code paideia.mail}：
 * 传输配置只应在一处维护，重复绑定同一前缀不会冲突（Spring 允许多个
 * {@code @ConfigurationProperties} 指向同一前缀）。这里只取发信需要的字段，
 * 并且**不引用 account.internal 包**——跨模块访问别人的 internal 包会被
 * ModularityTest（ApplicationModules.verify）拦下。
 *
 * <p>凭据只来自配置（本地文件或环境变量），不写进仓库、不进日志。
 */
@ConfigurationProperties(prefix = "paideia.mail")
public record OpsMailProperties(
        String host,
        int port,
        String username,
        String password,
        String fromAddress,
        String fromName,
        boolean startTls,
        boolean ssl) {

    public OpsMailProperties {
        port = port <= 0 ? 587 : port;
        fromName = fromName == null || fromName.isBlank() ? "Paideia 运维" : fromName;
        fromAddress = fromAddress == null || fromAddress.isBlank() ? username : fromAddress;
    }

    public boolean hasHost() {
        return host != null && !host.isBlank();
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    /** record 默认 toString 会带出 password；排障时整个对象常被打印，必须自己写。 */
    @Override
    public String toString() {
        return ("OpsMailProperties[host=%s, port=%d, username=%s, password=%s, fromAddress=%s,"
                + " fromName=%s, startTls=%s, ssl=%s]")
                .formatted(host, port, username, password == null || password.isEmpty() ? null : "***",
                        fromAddress, fromName, startTls, ssl);
    }
}
