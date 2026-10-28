package io.github.xiaou61.ai.internal.user;

import io.github.xiaou61.platform.UserDeletedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 注销时删掉本模块的用户数据（用户裁决 10：级联硬删）。
 *
 * <p><b>这里是一张手写的表清单</b>：项目**不建跨模块外键**（见 V4 的约定），数据库不会替我们
 * 级联。清单的正确性由 {@code AccountDeletionCascadeIntegrationTest} 盯着——它在注销后逐表断言
 * 行数为 0，**新增用户私有表却忘了登记，那条用例就会红**。
 *
 * <p>清单里包含 WORK-005 就有的两张表（凭据、精简结果）：注销后留下加密的 API Key 尤其不对，
 * 那是这个人留在这里的一把钥匙。
 *
 * <p><b>表名与列名要成对写</b>：本模块里 `ai_messages` **没有** `user_id`（它随会话走），
 * 按 `user_id` 一刀切会直接 SQL 报错。
 *
 * <p>用 {@code BEFORE_COMMIT}：与账号模块的删除在**同一个事务**里，要么全删干净、要么什么都没发生。
 * 分成两个事务会出现"用户没了但记忆还在"的中间状态，而那种残留没有任何人会去清理。
 */
@Component
class AiUserDeletionListener {

    /** 表 + 用于定位这个人的列。子表在前。 */
    private static final String[][] USER_TABLES = {
            {"ai_memories_structured", "user_id"},
            {"ai_memories_text", "user_id"},
            // 候选（WORK-050）：待确认的提议也是这个人的数据，注销一并删
            {"ai_memory_candidates", "user_id"},
            // 三个开关（WORK-013）：注销就该一并消失，留着一行等于"这个人还在这里有个偏好"
            {"ai_user_settings", "user_id"},
            // 面试评估（WORK-010）：这个人留下的评分与弱项，注销就该一起消失
            {"ai_interview_evaluations", "user_id"},
            {"ai_entry_summaries", "owner_user_id"},
            // 积分流水与账户（WORK-009）。**注销一并删**（用户决定 8）：守住"注销即级联硬删"，
            // 代价是注销用户的账不再完整——这是明确选过的方向，别改成"保留审计"。
            {"ai_credit_ledger", "user_id"},
            {"ai_credit_accounts", "user_id"},
            // 简历（WORK-018）：四张表都要删。`resume_documents` 是主简历本体，另外三张是它的
            // 快照、岗位版本与提案——任何一张留下来都等于"这个人还在这里"
            {"resume_proposals", "user_id"},
            {"resume_versions", "user_id"},
            {"resume_edit_history", "user_id"},
            {"resume_documents", "user_id"},
            // 训练营（WORK-029）：五张表，**子表在前**。题目/作答/计划都挂在 program 上，
            // 任务又挂在 plan 上；顺序反了会留下"计划没了但任务还在"的孤儿行
            {"camp_tasks", "user_id"},
            {"camp_plans", "user_id"},
            {"camp_attempts", "user_id"},
            {"camp_items", "user_id"},
            {"camp_programs", "user_id"},
            // 助手回答的反馈（WORK-036）：这个人点过的赞与踩。**不给别人留他的评价记录**
            {"ai_feedback", "user_id"},
            // AI 调用追踪（WORK-022）：轮次表有 user_id，步骤表没有（它随 trace 走），
            // 所以步骤在下面按子查询删。**先删步骤再删轮次**——顺序反过来就找不到 trace 了。
            {"ai_runs", "user_id"},
            // 会话最后删：消息跟着它（下面单独删）
            {"ai_conversations", "user_id"},
    };

    private final JdbcTemplate jdbcTemplate;

    AiUserDeletionListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onUserDeleted(UserDeletedEvent event) {
        // 消息没有 user_id：先按"属于这个人的会话"删，再删会话本身
        jdbcTemplate.update("""
                DELETE FROM ai_messages
                WHERE conversation_id IN (SELECT id FROM ai_conversations WHERE user_id = ?)
                """, event.userId());
        // 步骤表与载荷表都没有 user_id：它们挂在 trace_id 上。**必须在删 ai_runs 之前**跑，
        // 否则子查询已经查不到那些 trace 了，它们会留下来成为孤儿。
        //
        // 载荷表尤其要紧：它含用户的提示词、记忆片段与笔记正文，比步骤表敏感得多。
        jdbcTemplate.update(
                "DELETE FROM ai_run_payloads "
                        + "WHERE run_trace_id IN (SELECT trace_id FROM ai_runs WHERE user_id = ?)",
                event.userId());
        jdbcTemplate.update(
                "DELETE FROM ai_run_spans "
                        + "WHERE run_trace_id IN (SELECT trace_id FROM ai_runs WHERE user_id = ?)",
                event.userId());
        for (String[] table : USER_TABLES) {
            jdbcTemplate.update("DELETE FROM " + table[0] + " WHERE " + table[1] + " = ?", event.userId());
        }
    }
}
