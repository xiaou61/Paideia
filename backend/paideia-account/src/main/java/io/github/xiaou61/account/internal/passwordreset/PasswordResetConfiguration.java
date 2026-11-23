package io.github.xiaou61.account.internal.passwordreset;

import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 重置口令的装配。目前只有一项：站点对外地址（用于拼邮件里的链接）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountWebProperties.class)
class PasswordResetConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetConfiguration.class);

    @Bean
    StartupCheck accountWebStartupCheck(AccountWebProperties properties, Environment environment) {
        return new StartupCheck(properties, environment);
    }

    /**
     * 站点对外地址的启动期校验。
     *
     * <p>为什么必须硬失败而不是只打 warn：{@code base-url} 为空时重置链接的主机名取自请求的
     * {@code Host} 头，攻击者伪造一个 {@code Host: evil.com} 就能让受害者收到指向自己站点的重置邮件，
     * 点开即把一次性令牌交出去。这种风险不该依赖"运维记得改服务器配置"。
     *
     * <p>dev / test / local 三个 profile 保留 warn + 回退（本地开发与 e2e 没有固定域名）；
     * 其他 profile（含生产）缺这项配置时直接启动失败。
     */
    static final class StartupCheck implements InitializingBean {

        private static final Set<String> RELAXED_PROFILES = Set.of("dev", "test", "local");

        private final AccountWebProperties properties;
        private final Environment environment;

        StartupCheck(AccountWebProperties properties, Environment environment) {
            this.properties = properties;
            this.environment = environment;
        }

        @Override
        public void afterPropertiesSet() {
            if (properties.configured()) {
                requireAbsoluteUrl(properties.baseUrl());
                return;
            }
            for (String profile : environment.getActiveProfiles()) {
                if (RELAXED_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
                    log.warn("未配置 paideia.account.web.base-url，重置链接改用请求域名拼装"
                            + "（仅 {} 环境允许；伪造 Host 头可劫持该链接）", profile);
                    return;
                }
            }
            throw new IllegalStateException("""
                    未配置 paideia.account.web.base-url（站点对外地址，形如 https://paideia.example.com）。
                    留空时邮件里的重置链接会回退到请求的 Host 头：伪造 Host 即可把链接指向攻击者站点，
                    受害者点开就把一次性令牌交出去。请在服务器上的 application.yml 里显式配置该地址。""");
        }

        /**
         * 配了但没写 scheme 的值会拼出 mail 客户端不可点的相对链接（{@code paideia.example.com/#/reset-password}），
         * 用户点开是 404 而不是重置页；这里把格式错误提前到启动期。
         */
        private static void requireAbsoluteUrl(String baseUrl) {
            String normalized = baseUrl.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                return;
            }
            throw new IllegalStateException("""
                    paideia.account.web.base-url 必须是 http:// 或 https:// 开头的绝对地址（当前：%s）。
                    缺 scheme 时拼出来的重置链接在邮件客户端里点不开。""".formatted(baseUrl));
        }
    }
}
