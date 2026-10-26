package io.github.xiaou61.ai;

import java.util.List;

/**
 * AI 模块的**管理端**契约（WORK-030）。
 *
 * <p><b>为什么要有这个端口</b>：管理端的"用户列表"要显示每个人的余额、已用积分、调用次数、
 * 最近活跃。这四样分散在 `ai_credit_accounts` / `ai_credit_ledger` / `ai_runs` 三张表里，
 * 而 `paideia-account` 不能依赖本模块（依赖方向反过来也不成立），所以聚合成
 * "按一批 userId 取四个指标"这件事只能由本模块出口。
 *
 * <p><b>调积分也在这里</b>：`CreditService.adjust` 是本模块的内部实现，
 * 管理端要调它只能经一个公共入口。放在同一个端口里，比再开一个只有一个方法的接口清楚。
 */
public interface AiAdminApi {

    /**
     * 积分流水（管理端审计的分页视图，WORK-039 供管理工具使用）。
     *
     * <p>返回公共记录而不是内部 `CreditService.LedgerRow`：内部类型穿过模块边界会让
     * `paideia-app` 必须 import `ai.internal`，Modulith 边界就破了。
     *
     * @param userId 只看某个人；null 表示全部
     * @param kind   流水种类；空表示不过滤
     * @param from   起始时间（含）；null 不限
     * @param to     结束时间（含）；null 不限
     */
    io.github.xiaou61.platform.PageResult<LedgerEntry> ledger(Long userId, String kind,
                                                              java.time.Instant from,
                                                              java.time.Instant to,
                                                              int page, int size);

    /** 一条积分流水。字段与内部行一一对应，`createdAt` 用 ISO-8601 字符串。 */
    record LedgerEntry(long id, long userId, long delta, String kind, String model,
                       Integer inputTokens, Integer outputTokens, Long refMessageId,
                       Long operatorUserId, String note, String createdAt) {
    }

    /**
     * 一批用户的用量快照。
     *
     * @param userIds 要查的用户 id；空集合直接返回空，不查库
     */
    List<UserUsage> usage(java.util.Collection<Long> userIds);

    /**
     * 管理员调整某人的积分。
     *
     * @return 调整后的余额
     * @throws io.github.xiaou61.platform.BizException 余额不足（扣成负数）时
     */
    long adjust(long userId, long delta, long operatorId, String note);

    /**
     * 一个用户的用量。
     *
     * @param balance      当前余额（没有账户行时 0）
     * @param usedCredits  累计消耗的积分（**只算流出**，即 `delta < 0` 的绝对值之和）
     * @param runCount     累计调用次数
     * @param lastActiveAt 最近一次调用的开始时间（ISO-8601）；从未调用过时为 null
     */
    record UserUsage(long userId, long balance, long usedCredits, long runCount, String lastActiveAt) {
    }
}
