package io.github.xiaou61;

import io.github.xiaou61.account.AccountDirectory;
import io.github.xiaou61.sandbox.DatasetOwnerNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据集上传者名字的装配（WORK-060）。
 *
 * <p>实现放在装配根：sandbox 不能依赖 account，account 也不该认识 sandbox 的端口。
 * 与 {@link RoleLookupConfiguration}、{@code DatasetStorageConfiguration} 同一先例。
 */
@Configuration
public class DatasetOwnerNamesConfiguration {

    @Bean
    DatasetOwnerNames datasetOwnerNames(AccountDirectory accounts) {
        return accounts::usernames;
    }
}
