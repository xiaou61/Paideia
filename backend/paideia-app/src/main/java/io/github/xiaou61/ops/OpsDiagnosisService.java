package io.github.xiaou61.ops;

import io.github.xiaou61.ai.OpsDiagnoser;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 诊断的编排：冷却、单飞、落库、异步执行（WORK-042 REQ-007/REQ-011/REQ-013）。
 *
 * <p><b>为什么冷却只作用于告警</b>：手动触发是人的决定，重复点两次最多多花一次钱；
 * 告警是机器的事件，风暴时可能每分钟都来。把两者用同一个窗口挡住，只会让管理员在
 * 真的想复诊时被拒。
 *
 * <p><b>为什么单飞</b>：诊断是"读一眼现状"，并发跑多次既浪费积分，也会让报告互相矛盾。
 * 忙时手动返回 429、告警返回 skip，都不排队——排队只会让最后那份报告基于过期证据。
 */
@Service
class OpsDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(OpsDiagnosisService.class);

    /** 超过这个时长的 PENDING/RUNNING 视为应用重启留下的僵尸。 */
    static final long STALE_AFTER_MINUTES = 10;

    private final OpsDiagnosisMapper mapper;
    private final OpsEvidenceCollector evidence;
    private final OpsDiagnoser diagnoser;
    private final OpsIncidentService incidents;
    private final OpsIncidentContext contexts;
    private final OpsProperties properties;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, Instant> alertCooldowns = new ConcurrentHashMap<>();

    OpsDiagnosisService(OpsDiagnosisMapper mapper,
                        OpsEvidenceCollector evidence,
                        OpsDiagnoser diagnoser,
                        OpsIncidentService incidents,
                        OpsIncidentContext contexts,
                        OpsProperties properties,
                        @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.mapper = mapper;
        this.evidence = evidence;
        this.diagnoser = diagnoser;
        this.incidents = incidents;
        this.contexts = contexts;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * 提交一次诊断。
     *
     * @return 诊断 id；告警因冷却或忙碌被跳过时返回 {@code -1}
     * @throws BizException 诊断关闭（409）、手动重复提交（429）
     */
    long start(String trigger, Long incidentId, String alertName, String question, long requestedBy) {
        OpsProperties.Diagnosis config = properties.getDiagnosis();
        if (!config.isEnabled()) {
            throw new BizException(ErrorCode.CONFLICT, "运维诊断已关闭");
        }

        boolean alert = "alert".equals(trigger) && alertName != null && !alertName.isBlank();
        Instant now = Instant.now();

        if (!running.compareAndSet(false, true)) {
            if (alert) {
                log.debug("ops.diagnosis.skipped alert={} reason=busy", alertName);
                return -1;
            }
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "已有一次诊断在进行中，请稍后再试");
        }

        try {
            if (incidentId != null) {
                // 事件级冷却：同一 OPEN 事件在窗口内不重复花钱。先查后写，单飞保证没有并发 claim。
                if (!incidents.canDiagnose(incidentId, now, config.getCooldownSeconds())) {
                    log.debug("ops.diagnosis.skipped incidentId={} reason=cooldown-or-closed", incidentId);
                    running.set(false);
                    return -1;
                }
                // 同实例复用（WORK-049）：告警风暴里不同 alertname 是不同事件，冷却挡不住它们。
                // 只认 DONE；上一次 FAILED 不拦，否则一次失败会让后续告警全部失声。
                long window = config.getRelatedWindowSeconds();
                if (window > 0) {
                    Optional<Long> reused = incidents.recentDoneDiagnosisForInstance(incidentId, now, window);
                    if (reused.isPresent()) {
                        incidents.appendEvent(incidentId, OpsIncidentEvent.KIND_NOTE,
                                "复用诊断 #" + reused.get() + "（同实例，" + window + " 秒内已有结论）",
                                null);
                        log.info("ops.diagnosis.reused incidentId={} diagnosisId={}", incidentId, reused.get());
                        running.set(false);
                        return -1;
                    }
                }
            } else if (alert) {
                Instant previous = alertCooldowns.get(alertName);
                if (previous != null && previous.plusSeconds(config.getCooldownSeconds()).isAfter(now)) {
                    log.debug("ops.diagnosis.skipped alert={} reason=cooldown", alertName);
                    running.set(false);
                    return -1;
                }
            }

            OpsDiagnosisRow row = new OpsDiagnosisRow();
            row.setTriggerType(trigger);
            row.setIncidentId(incidentId);
            row.setAlertName(alert ? alertName : null);
            row.setQuestion(trim(question, 2000));
            row.setStatus(OpsDiagnosisRow.STATUS_PENDING);
            row.setRequestedBy(requestedBy);
            row.setCreatedAt(now);
            mapper.insert(row);
            if (incidentId != null) {
                incidents.markDiagnosed(incidentId, now);
            } else if (alert) {
                alertCooldowns.put(alertName, now);
                purgeCooldowns(now);
            }
            long id = row.getId();
            if (incidentId != null) {
                incidents.markStarted(incidentId, id);
            }
            executor.execute(() -> run(id, requestedBy, trigger, incidentId,
                    row.getAlertName(), row.getQuestion()));
            return id;
        } catch (RuntimeException failure) {
            running.set(false);
            throw failure;
        }
    }

    List<View> list() {
        int limit = Math.max(1, properties.getDiagnosis().getListLimit());
        return mapper.listRecent(limit).stream().map(row -> View.of(row, false)).toList();
    }

    View get(long id) {
        return mapper.findById(id)
                .map(row -> View.of(row, true))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "诊断记录不存在"));
    }

    /**
     * 给一份已完成的诊断评分（WORK-043 REQ-019）。
     *
     * <p>三条约束：
     * <ol>
     *   <li>**只接受 -1/0/1**：不开放"五颗星"，要回答的只是"这份报告能不能照着做"；</li>
     *   <li>**只给 DONE 评分**：失败或进行中的报告没有可评的内容，评了只会污染 helpful 率；</li>
     *   <li>**重评是覆盖**：同一人改主意不该在统计里算两次。</li>
     * </ol>
     * 有 incident 时追加一条 NOTE 到时间线，事后能对上"这次事件里报告有没有用"。
     */
    View feedback(long id, Integer rating, String note, long actorUserId) {
        if (rating == null || rating < -1 || rating > 1) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "评分只能是 -1、0 或 1");
        }
        OpsDiagnosisRow row = mapper.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "诊断记录不存在"));
        if (!OpsDiagnosisRow.STATUS_DONE.equals(row.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只能给已完成的诊断评分");
        }
        String trimmed = trim(note, 500);
        if (mapper.updateFeedback(id, rating, trimmed, Instant.now()) != 1) {
            // 状态在"读出来"与"写回去"之间变了（并发重跑）；如实报冲突，不静默吞掉
            throw new BizException(ErrorCode.CONFLICT, "诊断状态已变化，请刷新后重试");
        }
        if (row.getIncidentId() != null) {
            incidents.appendEvent(row.getIncidentId(), OpsIncidentEvent.KIND_NOTE,
                    "诊断 #" + id + " 评分：" + RATING_LABELS.get(rating)
                            + (trimmed == null ? "" : "；" + trimmed),
                    actorUserId);
        }
        return get(id);
    }

    /** helpful 率（WORK-043 REQ-022）：分母为 0 时给 null，"还没人评"不等于"全都不准"。 */
    Stats stats() {
        OpsDiagnosisMapper.FeedbackStats raw = mapper.stats();
        long rated = raw == null ? 0 : raw.rated();
        long good = raw == null ? 0 : raw.good();
        long partial = raw == null ? 0 : raw.partial();
        long bad = raw == null ? 0 : raw.bad();
        Double helpfulRate = rated == 0 ? null : (double) good / rated;
        return new Stats(rated, good, partial, bad, helpfulRate);
    }

    private static final Map<Integer, String> RATING_LABELS =
            Map.of(1, "准", 0, "部分准", -1, "不准");

    record Stats(long rated, long good, long partial, long bad, Double helpfulRate) {
    }

    /** 应用启动时把上次进程留下的 PENDING/RUNNING 收掉，避免页面永远显示"诊断中"。 */
    @EventListener(ApplicationReadyEvent.class)
    void cleanupStale() {
        Instant now = Instant.now();
        int changed = mapper.markStale(now.minus(Duration.ofMinutes(STALE_AFTER_MINUTES)),
                "应用重启中断", now);
        if (changed > 0) {
            log.warn("ops.diagnosis.stale-cleaned count={}", changed);
        }
        catchUp(now);
    }

    /**
     * 重启补诊断（WORK-049）：应用挂了的那段时间里建立的告警，一次诊断都没有。
     * 上限刻在 SQL 里（OPEN + 6 小时 + 无 DONE + LIMIT 1），这里只负责触发与记账。
     */
    private void catchUp(Instant now) {
        OpsProperties.Diagnosis config = properties.getDiagnosis();
        if (!config.isCatchUpEnabled() || config.getUserId() <= 0) {
            return;
        }
        try {
            Optional<OpsIncident> candidate = incidents.catchUpCandidate(
                    now.minus(Duration.ofHours(6)), now.minus(Duration.ofMinutes(STALE_AFTER_MINUTES)));
            if (candidate.isEmpty()) {
                return;
            }
            OpsIncident incident = candidate.get();
            log.info("ops.diagnosis.catch-up incidentId={}", incident.getId());
            incidents.appendEvent(incident.getId(), OpsIncidentEvent.KIND_NOTE, "重启补诊断", null);
            start("restart", incident.getId(), incident.getAlertName(), null, config.getUserId());
        } catch (RuntimeException failure) {
            // 补诊断失败不能拖住启动；正常诊断路径仍可用
            log.warn("ops.diagnosis.catch-up-failed error={}", failure.toString());
        }
    }

    private void run(long id, long userId, String trigger, Long incidentId,
                     String alertName, String question) {
        try {
            mapper.markRunning(id);
            String context = contexts.build(incidentId, alertName, Instant.now());
            OpsDiagnoser.Evidence collected = evidence.collect(trigger, alertName, question, context);
            OpsDiagnoser.Diagnosis diagnosis = diagnoser.diagnose(userId, collected);
            TokenUsage usage = diagnosis.usage();
            mapper.markDone(id, diagnosis.model(),
                    usage == null ? null : usage.inputTokens(),
                    usage == null ? null : usage.outputTokens(),
                    diagnosis.report(), Instant.now());
            if (incidentId != null) {
                incidents.markDone(incidentId, id, diagnosis.model());
            }
            log.info("ops.diagnosis.done id={} trigger={} model={}", id, trigger, diagnosis.model());
        } catch (RuntimeException failure) {
            String message = messageOf(failure);
            try {
                mapper.markFailed(id, message, Instant.now());
            } catch (RuntimeException persistFailure) {
                log.error("ops.diagnosis.persist-failed id={} error={}", id, messageOf(persistFailure));
            }
            if (incidentId != null) {
                try {
                    incidents.markFailed(incidentId, id, message);
                } catch (RuntimeException timelineFailure) {
                    log.warn("ops.diagnosis.timeline-failed id={} error={}",
                            id, messageOf(timelineFailure));
                }
            }
            log.warn("ops.diagnosis.failed id={} trigger={} error={}", id, trigger, message);
        } finally {
            running.set(false);
        }
    }

    /** 冷却表按告警名增长；数量很小，但长时间运行也不该无界。 */
    private void purgeCooldowns(Instant now) {
        if (alertCooldowns.size() < 200) {
            return;
        }
        long ttl = Math.max(3600, properties.getDiagnosis().getCooldownSeconds() * 6);
        alertCooldowns.entrySet().removeIf(entry -> entry.getValue().plusSeconds(ttl).isBefore(now));
    }

    private static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return trim(message, 1000);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    /** 列表不带报告全文（一次 20 条全文会把响应撑大）；详情才带。 */
    record View(long id, Long incidentId, String triggerType, String alertName, String question,
                String status, String model, Integer inputTokens, Integer outputTokens,
                String reportMd, String errorMessage, Integer rating, String feedbackNote,
                Instant createdAt, Instant finishedAt) {

        static View of(OpsDiagnosisRow row, boolean includeReport) {
            return new View(row.getId(), row.getIncidentId(), row.getTriggerType(), row.getAlertName(),
                    row.getQuestion(), row.getStatus(), row.getModel(), row.getInputTokens(),
                    row.getOutputTokens(), includeReport ? row.getReportMd() : null,
                    row.getErrorMessage(), row.getRating(), row.getFeedbackNote(),
                    row.getCreatedAt(), row.getFinishedAt());
        }
    }
}
