package io.github.xiaou61;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 测试共享件（WORK-062）：deadline 轮询。
 *
 * <p>只提供机制：**不做断言、不替调用方决定超时怎么收场**——超时值（2s/5s/10s）、间隔（25/50ms）
 * 与超时动作（静默返回 / 返回 null / 抛带中文消息的 AssertionError）全部留在调用点。
 * 这是刻意的：把这些"统一"掉等于改掉各用例的失败语义。
 *
 * <p>中断处理沿用本目录既有 `sleep()` 的语义：**吞掉 InterruptedException 并还原中断标志**，
 * 循环提前结束、交给调用方按超时处理——调用点因此不需要改签名。
 */
public final class Await {

    private Await() {
    }

    /** 轮询到条件为真；超时返回 false（调用方自己判怎么失败）。 */
    public static boolean until(BooleanSupplier ready, long timeoutMs, long intervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ready.getAsBoolean()) {
                return true;
            }
            if (!sleep(intervalMs)) {
                return false;
            }
        }
        return ready.getAsBoolean();
    }

    /** 轮询取值直到谓词满足；超时返回空。probe 不应返回 null（谓词要对它求值）。 */
    public static <T> Optional<T> value(Supplier<T> probe, Predicate<T> ready, long timeoutMs, long intervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            T current = probe.get();
            if (ready.test(current)) {
                return Optional.of(current);
            }
            if (System.currentTimeMillis() >= deadline || !sleep(intervalMs)) {
                // 边界上再探一次：条件恰好在超时那刻成立时不该判失败
                T last = probe.get();
                return ready.test(last) ? Optional.of(last) : Optional.empty();
            }
        }
    }

    /** 睡一觉；被中断时还原标志并返回 false（与既有 sleep() 同一口径）。 */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}