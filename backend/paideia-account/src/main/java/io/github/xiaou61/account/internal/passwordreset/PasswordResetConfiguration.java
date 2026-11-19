package io.github.xiaou61.account.internal.passwordreset;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 重置口令的装配。目前只有一项：站点对外地址（用于拼邮件里的链接）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountWebProperties.class)
class PasswordResetConfiguration {
}
