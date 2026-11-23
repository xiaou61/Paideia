package io.github.xiaou61.mcp.internal.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 凭据用量的记录与查询（WORK-044 TASK-002）。
 *
 * <p><b>记录失败不抛</b>：计数是旁路，不该让一次成功的工具调用因为写库失败而变成失败。
 * 吞掉并记 warn——真出问题时日志里有痕迹，但用户拿到的仍是工具的真实结果。
 *
 * <p><b>日期由调用方传入</b>：配额判定与计数必须用同一个"今天"，否则跨天前后两次调用会
 * 分别读到一个日期的两半。由调用方算一次传下来，两处才可能一致（也让测试能固定时间）。
 */
@Component
public class McpUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(McpUsageRecorder.class);

    private final McpUsageMapper usage;

    McpUsageRecorder(McpUsageMapper usage) {
        this.usage = usage;
    }

    /** 累计值。 */
    public record Totals(long calls, long errors) {
    }

    /**
     * 记一次调用。
     *
     * <p>用 {@code Instant.now()} 而不是调用方传时间：这一列的用途只有"最后一次写是什么时候"，
     * 精度不重要，少一个参数少一处不一致。
     */
    public void record(long tokenId, LocalDate day, boolean error) {
        try {
            usage.increment(tokenId, day, error ? 1 : 0, Instant.now());
        } catch (RuntimeException failure) {
            log.warn("mcp.usage.record.failed tokenId={} day={}", tokenId, day, failure);
        }
    }

    /** 某天的调用次数。没有行就是 0。 */
    public int callsToday(long tokenId, LocalDate day) {
        Integer calls = usage.callsOn(tokenId, day);
        return calls == null ? 0 : calls;
    }

    /** 历史累计。没有行时 mapper 的 COALESCE 会给出 0，但查询本身可能返回 null 行。 */
    public Totals totals(long tokenId) {
        McpUsageMapper.Totals row = usage.totals(tokenId);
        return row == null ? new Totals(0, 0) : new Totals(row.calls(), row.errors());
    }

    /**
     * 一批凭据的历史累计。列表页用这个，别在循环里逐条调 {@link #totals}（N+1）。
     *
     * <p>没出现过用量的凭据不会在结果里，调用方按"缺失即 0"处理。
     */
    public Map<Long, Totals> totalsFor(List<Long> tokenIds) {
        if (tokenIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Totals> result = new HashMap<>();
        for (McpUsageMapper.TokenTotals row : usage.totalsFor(tokenIds)) {
            result.put(row.tokenId(), new Totals(row.calls(), row.errors()));
        }
        return result;
    }

    /** 一批凭据在某天的调用次数。缺失即 0，与 {@link #callsToday} 同语义。 */
    public Map<Long, Integer> callsOnFor(List<Long> tokenIds, LocalDate day) {
        if (tokenIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (McpUsageMapper.TokenCalls row : usage.callsOnFor(tokenIds, day)) {
            result.put(row.tokenId(), row.calls());
        }
        return result;
    }

    /** 注销级联调用。 */
    public int deleteAllFor(long userId) {
        return usage.deleteByUser(userId);
    }
}
