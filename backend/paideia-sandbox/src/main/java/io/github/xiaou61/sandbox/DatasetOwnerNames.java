package io.github.xiaou61.sandbox;

import java.util.Collection;
import java.util.Map;

/**
 * 数据集上传者名字（WORK-060）。
 *
 * <p>sandbox 不认识账号表，也不该认识——它只需要把 `owner_user_id` 变成可显示的名字。
 * 实现放在 `paideia-app` 装配根，用 account 的只读目录桥接（与 `DatasetStorage` 同一先例）。
 *
 * <p>**只返回用户名**；不存在的 id 不返回。没配置实现时用 {@link #NONE}，
 * 目录会退化成「用户 <id>」，不会让整个接口 500。
 */
public interface DatasetOwnerNames {

    Map<Long, String> usernames(Collection<Long> ids);

    DatasetOwnerNames NONE = ids -> Map.of();
}
