package io.github.xiaou61.ops;

import java.time.Instant;
import java.util.List;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 模板同步 + 异常检测的周期任务（WORK-085）。
 *
 * <p>一轮做三件事：同步桶 → 检测异常 → 对新异常尝试触发诊断。三段各自 try/catch：
 * Loki 抖一下不该让检测停摆，检测抛错也不该让已落库的异常丢失触发机会。
 *
 * <p>顺带暴露一个 `paideia_ops_pattern_anomalies_open` 计量：Grafana 告警规则靠它判断
 * 「有未处理模板异常」——DB 里的 Open 异常数没有别的方式变成 Prometheus 指标。
 */
@Component
class PatternSyncJob {

    private static final Logger log = LoggerFactory.getLogger(PatternSyncJob.class);

    private final PatternSyncService syncService;
    private final PatternAnomalyDetector detector;
    private final PatternTriggerService triggerService;

    PatternSyncJob(PatternSyncService syncService, PatternAnomalyDetector detector,
                   PatternTriggerService triggerService, PatternMapper mapper, MeterRegistry registry) {
        this.syncService = syncService;
        this.detector = detector;
        this.triggerService = triggerService;
        // 惰性求值：每次抓取时读一次 Open 异常数；DB 抖动时返回 0，绝不让 /metrics 失败。
        Gauge.builder("paideia_ops_pattern_anomalies_open", mapper, PatternSyncJob::openAnomalies)
                .description("未处理的日志模板异常数（NEW_TEMPLATE + FREQ_SPIKE + DISAPPEARED）")
                .register(registry);
    }

    private static double openAnomalies(PatternMapper mapper) {
        try {
            return mapper.countOpen("NEW_TEMPLATE") + mapper.countOpen("FREQ_SPIKE") + mapper.countOpen("DISAPPEARED");
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    @Scheduled(
            initialDelayString = "${paideia.ops.patterns.sync-initial-delay-seconds:120}000",
            fixedDelayString = "${paideia.ops.patterns.sync-interval-seconds:300}000")
    void run() {
        Instant now = Instant.now();
        int written = 0;
        try {
            written = syncService.syncOnce(now);
        } catch (RuntimeException failure) {
            log.warn("ops.pattern.sync.failed reason={}", failure.toString());
        }

        List<PatternMapper.AnomalyRow> anomalies;
        try {
            anomalies = detector.detect(now);
        } catch (RuntimeException failure) {
            log.warn("ops.pattern.detect.failed reason={}", failure.toString());
            return;
        }

        try {
            int triggered = triggerService.triggerAll(anomalies, now);
            if (written > 0 || !anomalies.isEmpty()) {
                log.info("ops.pattern.round synced={} anomalies={} triggered={}",
                        written, anomalies.size(), triggered);
            }
        } catch (RuntimeException failure) {
            log.warn("ops.pattern.trigger.round.failed reason={}", failure.toString());
        }
    }
}
