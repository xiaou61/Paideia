package io.github.xiaou61.mcp.internal.usage;

import io.github.xiaou61.platform.UserDeletedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 注销时删掉这个人的全部凭据用量（WORK-044 TASK-002）。
 *
 * <p>与 `McpUserDeletionListener` 同一条纪律：本模块的表结构不外泄，用公共事件消费。
 * 用 {@code BEFORE_COMMIT} 与账号删除同事务，不留"用户没了但用量还在"的残行。
 *
 * <p><b>必须排在删凭据之前</b>：用量删除是 `DELETE u FROM mcp_token_usage u JOIN
 * mcp_access_tokens t ON t.id = u.token_id WHERE t.user_id = ?`，而两张表之间没有外键
 * （`mcp_token_usage` 建表时就没建）。凭据先被删掉的话这个 JOIN 匹配不到任何行，
 * 用量会**永远**留在库里（2026-11-02 审查抓到）。两个 listener 都监听同一个事件、
 * 都按包扫描顺序注册，靠顺序巧合是不可靠的，所以显式定序。
 */
@Component
@Order(0)
class McpUsageUserDeletionListener {

    private final McpUsageRecorder usage;

    McpUsageUserDeletionListener(McpUsageRecorder usage) {
        this.usage = usage;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onUserDeleted(UserDeletedEvent event) {
        usage.deleteAllFor(event.userId());
    }
}
