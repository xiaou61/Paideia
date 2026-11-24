package io.github.xiaou61.note.internal.user;

import io.github.xiaou61.platform.UserDeletedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 注销时删掉本模块的用户数据（用户裁决 10）。
 *
 * <p>手写表清单的理由与 AI 那边相同：不建跨模块外键，数据库不会替我们级联；清单由
 * {@code AccountDeletionCascadeIntegrationTest} 的逐表断言盯着。
 *
 * <p>**已发出的分享也要删**：用户裁决 10 明确包含这一条——注销之后那条免鉴权链接不该还能打开。
 *
 * <p><b>表名与列名成对写</b>：`note_reports` 里这个人的列叫 `reporter_user_id`（举报人），
 * 不是 `user_id`。按 `user_id` 一刀切会直接 SQL 报错。
 *
 * <p>别人举报过我的那些行**不动**：它们属于举报人的行为记录，删掉等于替别人撤回一份投诉；
 * 而它们指向的分享已经不存在，管理端列表里会显示"（笔记已删除）"。
 */
@Component
class NoteUserDeletionListener {

    private static final String[][] USER_TABLES = {
            // 先删双链（指着笔记），再删举报（指着分享），再删分享，再删笔记，最后删空库
            {"note_links", "user_id"},
            {"note_reports", "reporter_user_id"},
            {"note_shares", "user_id"},
            {"notes", "user_id"},
            {"note_books", "user_id"},
    };

    private final JdbcTemplate jdbcTemplate;

    NoteUserDeletionListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onUserDeleted(UserDeletedEvent event) {
        for (String[] table : USER_TABLES) {
            jdbcTemplate.update("DELETE FROM " + table[0] + " WHERE " + table[1] + " = ?", event.userId());
        }
    }
}
