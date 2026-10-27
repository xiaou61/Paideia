package io.github.xiaou61.mcp.internal.usage;

import java.time.Instant;
import java.time.LocalDate;
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

    /** 注销级联调用。 */
    public int deleteAllFor(long userId) {
        return usage.deleteByUser(userId);
    }
}
