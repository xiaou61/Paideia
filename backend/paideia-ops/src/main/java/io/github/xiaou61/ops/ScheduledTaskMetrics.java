package io.github.xiaou61.ops;

import io.github.xiaou61.platform.ScheduledTaskRecorder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 把定时任务结果记成 Micrometer 指标（WORK-028 REQ-016）。
 *
 * <p>为什么不写库：运维页只需要回答"这几件事分别上次什么时候跑、成没成"，这一屏状态
 * 不值得为它建一张表加一个清理任务。Prometheus 的点本身带时间戳。
 *
 * <p>指标（`task` 是标签）：`paideia_scheduled_last_success`（1/0）、
 * `paideia_scheduled_last_duration_seconds`、`paideia_scheduled_last_run_epoch_seconds`
 * （0 = 从未运行）、`paideia_scheduled_runs_total`。
 *
 * <p>**「未启用」与「跑了但失败」靠 last_run 区分**（REQ-018）：前者 last_run 恒为 0，
 * 后者有值且 success 为 0。
 */
@Component
class ScheduledTaskMetrics implements ScheduledTaskRecorder {

    /**
     * 已知任务先注册一遍，让"从没跑过"能在面板上显示出来。
     * 新增定时任务时要在这里加一行——否则它第一次执行前是不可见的。
     * （WORK-025 的 essay-collection 尚未合入本分支，合入时补上。）
     */
    static final List<String> KNOWN_TASKS = List.of("expired-state-purger", "telemetry-retention");

    private final Map<String, TaskState> states = new ConcurrentHashMap<>();

    ScheduledTaskMetrics(MeterRegistry registry) {
        for (String name : KNOWN_TASKS) {
            stateOf(name, registry);
        }
    }

    @Override
    public void record(String name, Duration duration, Throwable error) {
        TaskState state = states.get(name);
        if (state == null) {
            return;
        }
        state.runs.incrementAndGet();
        state.lastRun.set(Instant.now().getEpochSecond());
        state.lastDuration.set(duration.toMillis());
        state.lastSuccess.set(error == null ? 1 : 0);
    }

    private TaskState stateOf(String name, MeterRegistry registry) {
        return states.computeIfAbsent(name, key -> {
            TaskState state = new TaskState();
            Gauge.builder("paideia.scheduled.last.success", state.lastSuccess, AtomicLong::doubleValue)
                    .tag("task", key)
                    .register(registry);
            Gauge.builder("paideia.scheduled.last.duration.seconds", state, TaskState::durationSeconds)
                    .tag("task", key)
                    .register(registry);
            Gauge.builder("paideia.scheduled.last.run.epoch.seconds", state.lastRun, AtomicLong::doubleValue)
                    .tag("task", key)
                    .register(registry);
            Gauge.builder("paideia.scheduled.runs.total", state.runs, AtomicLong::doubleValue)
                    .tag("task", key)
                    .register(registry);
            return state;
        });
    }

    private static final class TaskState {
        /** 初始为 1：从未运行不等于失败；摘要页看 lastRun 是否为 0 来区分。 */
        private final AtomicLong lastSuccess = new AtomicLong(1);
        private final AtomicLong lastDuration = new AtomicLong(0);
        private final AtomicLong lastRun = new AtomicLong(0);
        private final AtomicLong runs = new AtomicLong(0);

        /** 存毫秒、报秒：Prometheus 的 duration 约定单位是秒。 */
        private double durationSeconds() {
            return lastDuration.get() / 1000.0;
        }
    }
}
