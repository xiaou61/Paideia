package io.github.xiaou61.mcp.internal.token;

import io.github.xiaou61.platform.UserDeletedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 注销时删掉这个人的全部 MCP 凭据（WORK-019 REQ-022）。
 *
 * <p><b>为什么单独一个 listener 而不是登记到 ai 模块那张表清单里</b>：那张清单在
 * `paideia-ai` 的内部，而 MCP 是另一个模块；让 AI 模块知道 `mcp_access_tokens` 这张表，
 * 等于把本模块的表结构泄漏出去。事件是公共契约（`paideia-platform`），两个模块各自消费。
 *
 * <p>用 {@code BEFORE_COMMIT}：与账号删除在同一个事务里，不会出现"用户没了但凭据还能用"的
 * 中间状态——那种残留尤其危险，因为它是一条**能登录的**残留。
 *
 * <p><b>必须排在 `McpUsageUserDeletionListener` 之后</b>：用量表靠 JOIN 本表来定位行，
 * 本表先删掉就等于用量永远删不掉。见那个类上的说明。
 */
@Component
@Order(10)
class McpUserDeletionListener {

    private final PatService tokens;

    McpUserDeletionListener(PatService tokens) {
        this.tokens = tokens;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onUserDeleted(UserDeletedEvent event) {
        tokens.deleteAllFor(event.userId());
    }
}
