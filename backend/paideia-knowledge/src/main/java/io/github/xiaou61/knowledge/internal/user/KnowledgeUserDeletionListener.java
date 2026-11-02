package io.github.xiaou61.knowledge.internal.user;

import io.github.xiaou61.platform.UserDeletedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 注销时删掉本模块的用户私有数据。
 *
 * <p>`knowledge_self_assessments` 是本项目第一张用户私有业务数据表（"我对哪些条目懂／不懂"）。
 * 它的外键只指向 `entry_id`，**用户删除不会级联到它**——不在这里删，注销后这份数据会一直留着，
 * 与"注销硬删"的承诺不符。
 *
 * <p>表清单与 AI/笔记/沙箱那边同样手写（不建跨模块外键），由
 * {@code AccountDeletionCascadeIntegrationTest} 逐表断言盯着。
 */
@Component
class KnowledgeUserDeletionListener {

    private static final String[][] USER_TABLES = {
            {"knowledge_self_assessments", "user_id"},
            // 学习记录（WORK-020）：外键只指向 entry_id，注销不会级联到它
            {"learning_progress", "user_id"},
            // 浏览记录（WORK-021）：同上，外键只指向 entry_id
            {"knowledge_entry_views", "user_id"},
            // 逐题作答与掌握状态（WORK-033）：外键只指向条目/概念，注销不会级联到它们
            {"question_attempts", "user_id"},
            {"concept_mastery_state", "user_id"},
            // 复习会话（WORK-034）：含出题与作答，注销必须一并删
            {"review_sessions", "user_id"},
            // 学习计划项（WORK-053）：`goal`/`reason` 是用户原文，没有跨模块外键，必须显式删
            {"learning_plan_items", "user_id"},
            // 搜索关键词（WORK-045）：`keyword` 可能含邮箱/手机号；它是数据大屏的输入，
            // 但大屏看的是聚合趋势，删掉某一个人的行不影响结论
            {"knowledge_search_log", "user_id"},
    };

    private final JdbcTemplate jdbcTemplate;

    KnowledgeUserDeletionListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onUserDeleted(UserDeletedEvent event) {
        for (String[] table : USER_TABLES) {
            jdbcTemplate.update("DELETE FROM " + table[0] + " WHERE " + table[1] + " = ?", event.userId());
        }
    }
}
