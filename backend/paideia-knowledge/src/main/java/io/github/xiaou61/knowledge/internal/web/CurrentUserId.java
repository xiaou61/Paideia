package io.github.xiaou61.knowledge.internal.web;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;

/**
 * 把令牌主体转成账号 id。
 *
 * <p>令牌里的 {@code sub} 是账号 id 的**字符串形式**，而自评表的外键是数字。合法令牌必然
 * 是数字（账号主键是自增）；非数字只可能来自伪造或版本错配的令牌。
 *
 * <p>两种用法刻意分开：**写操作**用 {@link #require()}——写不进去就明确拒绝；
 * **读路径**用 {@link #orNull()}——详情本身是可读的，不该因为"读不出我的标记"整页失败。
 */
final class CurrentUserId {

    private CurrentUserId() {
    }

    static Long require() {
        // 收敛到共享件（WORK-062）：文案与判定只在 CurrentUser.userId() 一处
        return CurrentUser.userId();
    }

    static Long orNull() {
        try {
            return Long.valueOf(CurrentUser.subjectId());
        } catch (NumberFormatException | BizException exception) {
            // BizException：CurrentUser 在匿名请求上会抛 UNAUTHENTICATED；这条路径由安全链
            // 兜住，这里只是不让它变成 500。
            return null;
        }
    }
}
