package io.github.xiaou61.ai.internal.memory;

import io.github.xiaou61.platform.ScheduledTaskRecorder;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 记忆的按时间淘汰（WORK-050 TASK-007 / REQ-004）。
 *
 * <p>与 {@code TelemetryRetentionPurger} 同一套节奏与批量（每小时、每批 500）：
 * 两处同类任务用同一组参数，运维只需要记一组数字。
 *
 * <p><b>为什么用 `fixedDelay` 而不是 cron</b>：它保证"上一次跑完再过一小时"，
 * 库慢的时候不会叠起来。
 *
 * <p><b>关掉不删</b>：{@code paideia.ai.memory.purge-enabled=false} 只是停止产生新的删除，
 * 历史数据不会因此被动。
 */
@Component
public class MemoryRetentionPurger {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetentionPurger.class);

    private final MemoryStore memories;
    private final MemoryProperties properties;
    /** 可空：测试切片与局部启动里没有这个 bean。 */
    private final ObjectProvider<ScheduledTaskRecorder> recorder;

    MemoryRetentionPurger(MemoryStore memories, MemoryProperties properties,
                          ObjectProvider<ScheduledTaskRecorder> recorder) {
        this.memories = memories;
        this.properties = properties;
        this.recorder = recorder;
    }

    @Scheduled(fixedDelay = 3600_000L)
    public void purge() {
        if (!properties.isPurgeEnabled()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            int deleted = memories.purgeExpired(Instant.now());
            if (deleted > 0) {
                log.info("ai.memory.purged rows={}", deleted);
            }
            record(startedAt, null);
        } catch (RuntimeException failure) {
            record(startedAt, failure);
            throw failure;
        }
    }

    private void record(long startedAt, Throwable error) {
        ScheduledTaskRecorder target = recorder.getIfAvailable();
        if (target != null) {
            target.record("memory-retention", Duration.ofNanos(System.nanoTime() - startedAt), error);
        }
    }
}
