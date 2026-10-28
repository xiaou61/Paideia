package io.github.xiaou61.mcp.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按 token 的固定窗口限流（WORK-019 REQ-021）。
 *
 * <p><b>为什么记在内存里</b>：这是"防一次失控的客户端把上游打穿"的护栏，不是计费依据。
 * 多实例部署时每个实例各算各的（总量会翻倍），但代价是漏放几次而不是错杀用户；真要精确限流
 * 需要共享存储，一期不做。`ponytail:` 单实例内存窗口，多实例时换成 Redis 或用网关限流。
 *
 * <p>窗口固定 60 秒：`分钟` 这个粒度与用户能感知到的"等一下再试"一致，也最省内存。
 */
@Component
class McpRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int perMinute;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    McpRateLimiter(@Value("${paideia.mcp.rate-limit-per-minute:120}") int perMinute) {
        this.perMinute = Math.max(1, perMinute);
    }

    boolean allow(long tokenId) {
        Instant now = Instant.now();
        Window updated = windows.compute(tokenId, (key, existing) -> {
            if (existing == null || existing.startedAt.plus(WINDOW).isBefore(now)) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt, existing.count + 1);
        });
        // 顺手清掉过期窗口：Map 会随"用过的 token 数"增长，而 token 总数很小；
        // 真到几万个 token 时再加定时清理，现在每天清一次都不值得写。
        if (windows.size() > 5_000) {
            windows.entrySet().removeIf(entry ->
                    entry.getValue().startedAt.plus(WINDOW).isBefore(now));
        }
        return updated.count <= perMinute;
    }

    /**
     * 当前窗口还剩多少秒。
     *
     * <p>告诉客户端"等多久"而不是只说"过于频繁"：模型拿到秒数才知道该等待还是该放弃重试。
     * 没有窗口（或已经过期但还没被 {@link #allow} 重置）时给 1 秒，避免回一个 0 让它立刻重试。
     */
    long secondsUntilReset(long tokenId) {
        Window window = windows.get(tokenId);
        if (window == null) {
            return 0;
        }
        long left = Duration.between(Instant.now(), window.startedAt().plus(WINDOW)).toSeconds();
        return Math.max(1, left);
    }

    int perMinute() {
        return perMinute;
    }

    private record Window(Instant startedAt, int count) {
    }
}
