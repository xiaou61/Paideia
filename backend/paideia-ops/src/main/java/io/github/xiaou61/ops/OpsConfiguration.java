package io.github.xiaou61.ops;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 运维面板的配置绑定（WORK-028）。 */
@Configuration
@EnableConfigurationProperties(OpsProperties.class)
class OpsConfiguration {
}
