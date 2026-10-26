package io.github.xiaou61.ai.internal.billing;

import io.github.xiaou61.ai.AiAdminApi;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 一批用户的用量快照 + 管理端调积分（WORK-030）。
 *
 * <p>三条查询、每批一次，不按用户逐个查：列表一页 20 行，逐个查就是 80 次往返。
 *
 * <p><b>口径写死在这里，别处不再各算一遍</b>：
 * <ul>
 *   <li>余额取 `ai_credit_accounts`，无行视为 0</li>
 *   <li>已用取 `ai_credit_ledger` 里 **delta &lt; 0** 的绝对值之和——**不取 `ai_runs.credits`
 *       累加**：精简那一路未必有 run 行，但一定有流水行，按 run 累加会少算</li>
 *   <li>调用次数与最近活跃取 `ai_runs`</li>
 * </ul>
 *
 * <p>调积分直接委托 {@link CreditService#adjust}：计费规则只在那一个地方。
 */
@Service
class AiAdminApiImplementation implements AiAdminApi {

    private final AiUsageMapper usage;
    private final CreditService credits;

    AiAdminApiImplementation(AiUsageMapper usage, CreditService credits) {
        this.usage = usage;
        this.credits = credits;
    }

    @Override
    public List<UserUsage> usage(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> balances = toMap(usage.balancesOf(ids),
                AiUsageMapper.BalanceRow::userId, AiUsageMapper.BalanceRow::balance);
        Map<Long, Long> used = toMap(usage.usedCreditsOf(ids),
                AiUsageMapper.UsedRow::userId, AiUsageMapper.UsedRow::used);
        Map<Long, AiUsageMapper.CallsRow> calls = usage.callsOf(ids).stream()
                .collect(Collectors.toMap(AiUsageMapper.CallsRow::userId, Function.identity()));

        return ids.stream().map(id -> {
            AiUsageMapper.CallsRow call = calls.get(id);
            return new UserUsage(
                    id,
                    balances.getOrDefault(id, 0L),
                    used.getOrDefault(id, 0L),
                    call == null ? 0L : call.calls(),
                    call == null || call.lastActive() == null ? null : call.lastActive().toString());
        }).toList();
    }

    @Override
    public long adjust(long userId, long delta, long operatorId, String note) {
        return credits.adjust(userId, delta, operatorId, note);
    }

    @Override
    public io.github.xiaou61.platform.PageResult<LedgerEntry> ledger(
            Long userId, String kind, java.time.Instant from, java.time.Instant to,
            int page, int size) {
        var result = credits.auditPage(userId, kind, from, to,
                io.github.xiaou61.platform.PageQuery.of(page, size, null));
        return new io.github.xiaou61.platform.PageResult<>(result.total(), result.page(), result.size(),
                result.items().stream().map(row -> new LedgerEntry(
                        row.id(), row.userId(), row.delta(), row.kind(), row.model(),
                        row.inputTokens(), row.outputTokens(), row.refMessageId(),
                        row.operatorUserId(), row.note(),
                        row.createdAt() == null ? null : row.createdAt().toString())).toList());
    }

    private static <T> Map<Long, Long> toMap(List<T> rows,
                                             Function<T, Long> id,
                                             Function<T, Long> value) {
        return rows.stream().collect(Collectors.toMap(id, row -> {
            Long raw = value.apply(row);
            return raw == null ? 0L : raw;
        }, (left, right) -> left));
    }
}
