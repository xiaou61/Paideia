package io.github.xiaou61.mcp.internal.usage;

import io.github.xiaou61.platform.UserDeletedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 注销时删掉这个人的全部凭据用量（WORK-044 TASK-002）。
 *
 * <p>与 `McpUserDeletionListener` 同一条纪律：本模块的表结构不外泄，用公共事件消费。
 * 用 {@code BEFORE_COMMIT} 与账号删除同事务，不留"用户没了但用量还在"的残行。
 */
@Component
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
