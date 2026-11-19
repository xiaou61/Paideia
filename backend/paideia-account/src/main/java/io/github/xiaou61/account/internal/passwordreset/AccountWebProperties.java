package io.github.xiaou61.account.internal.passwordreset;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站点对外的访问地址，用于拼邮件里的链接（重置口令、以后可能有别的）。
 *
 * <p><b>为什么要显式配</b>：链接主机名如果取自 HTTP 请求的 {@code Host} 头，
 * 攻击者构造一个 {@code Host: evil.com} 的请求，受害者收到的邮件里就会是一个指向
 * evil.com 的链接——点开就把令牌交给了对方。所以这里优先用配置值，
 * <b>只有配置为空时才退回请求域名</b>（本地开发与测试），并在退回时打一条告警。
 *
 * <p>形如 {@code https://paideia.example.com}，不要带尾部斜杠（带了也会被去掉）。
 */
@ConfigurationProperties(prefix = "paideia.account.web")
public record AccountWebProperties(String baseUrl) {

    public AccountWebProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public boolean configured() {
        return !baseUrl.isBlank();
    }
}
