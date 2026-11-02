package io.github.xiaou61.account;

import java.util.Collection;
import java.util.Map;

/**
 * 只读用户名目录（WORK-060）。
 *
 * <p>与 {@link AccountApi} 分开：那个接口是「任何已登录的人按自己的 id 读自己的东西」，
 * 这里是「社区内容展示需要看到别人是谁上传的」。两类能力混在一起会让每个业务模块
 * 顺手拿到一批账号读取能力。
 *
 * <p>**只返回用户名**：不返回邮箱、角色、口令摘要；不存在的 id 不返回。
 */
public interface AccountDirectory {

    /** 批量读用户名；空集合直接返回空 Map，不查库。 */
    Map<Long, String> usernames(Collection<Long> ids);
}
