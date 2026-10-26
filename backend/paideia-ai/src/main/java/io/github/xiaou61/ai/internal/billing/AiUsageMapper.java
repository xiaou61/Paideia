package io.github.xiaou61.ai.internal.billing;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 管理端"一批用户的用量"查询（WORK-030）。
 *
 * <p>每条都按 `IN (...)` 批量取：列表一页 20 行，逐个查是 80 次往返。
 *
 * <p><b>已用积分只认流水</b>（`delta &lt; 0` 的绝对值之和），不认 `ai_runs.credits` 累加——
 * 精简那一路未必有 run 行，但一定有流水行。
 */
@Mapper
interface AiUsageMapper {

    record BalanceRow(Long userId, Long balance) {
    }

    record UsedRow(Long userId, Long used) {
    }

    record CallsRow(Long userId, Long calls, Instant lastActive) {
    }

    /** 余额。没有账户行的用户不会出现在结果里，调用方按 0 处理。 */
    @Select("""
            <script>
            SELECT user_id AS userId, balance
            FROM ai_credit_accounts
            WHERE user_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<BalanceRow> balancesOf(@Param("ids") List<Long> ids);

    /**
     * 累计消耗：只算真实调用产生的流出，取绝对值。
     *
     * <p>必须排除 {@code ADJUST}：管理员调减会写负数 delta，不排除的话"被扣了 1000 分"会被
     * 当成"用了 1000 分"，而该用户可能一次 AI 都没调过。
     */
    @Select("""
            <script>
            SELECT user_id AS userId, COALESCE(SUM(-delta), 0) AS used
            FROM ai_credit_ledger
            WHERE delta &lt; 0 AND kind &lt;&gt; 'ADJUST' AND user_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY user_id
            </script>
            """)
    List<UsedRow> usedCreditsOf(@Param("ids") List<Long> ids);

    /** 调用次数与最近活跃。从未调用过的用户不出现在结果里。 */
    @Select("""
            <script>
            SELECT user_id AS userId, COUNT(*) AS calls, MAX(started_at) AS lastActive
            FROM ai_runs
            WHERE user_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY user_id
            </script>
            """)
    List<CallsRow> callsOf(@Param("ids") List<Long> ids);
}
