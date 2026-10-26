package io.github.xiaou61;

import io.github.xiaou61.account.AccountApi;
import io.github.xiaou61.platform.RoleLookup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 角色查询的装配（WORK-039）。
 *
 * <p><b>为什么实现放在装配根</b>：`paideia-mcp` 不能 import 业务包（WORK-019 的边界约定），
 * 而 `paideia-app` 同时看得见 mcp 与 account。与 {@code ToolCatalogConfiguration} 同一先例。
 *
 * <p>每次 MCP 请求查一次账号表：降权立即生效。代价是一次主键查询，当前规模远不到需要缓存。
 */
@Configuration
public class RoleLookupConfiguration {

    @Bean
    RoleLookup roleLookup(AccountApi accounts) {
        return userId -> accounts.findRoleById(String.valueOf(userId));
    }
}
