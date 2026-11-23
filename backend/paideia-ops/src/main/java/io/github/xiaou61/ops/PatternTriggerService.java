package io.github.xiaou61.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把「新写入的模板异常」接进既有的诊断流水线（WORK-085）。
 *
 * <p>复用 {@link OpsDiagnosisService#start} 的冷却、单飞、落库、邮件、时间线，不另起一套：
 * 触发类型用 {@code "pattern"}，告警名固定 {@code LogPatternAnomaly}，问题文本带异常详情。
 * 触发失败（诊断关闭 / 手动单飞占满 / 冷却中）只把该异常标成 {@code TRIGGER_SKIPPED}，
 * 异常本身仍留在账本里供端点展示——不是丢了。
 */
@Service
class PatternTriggerService {

    private static final Logger log = LoggerFactory.getLogger(PatternTriggerService.class);
    private static final String TRIGGER = "pattern";
    private static final String ALERT_NAME = "LogPatternAnomaly";

    private final OpsProperties properties;
    private final PatternMapper mapper;
    private final OpsDiagnosisService diagnosisService;

    PatternTriggerService(OpsProperties properties, PatternMapper mapper,
                          OpsDiagnosisService diagnosisService) {
        this.properties = properties;
        this.mapper = mapper;
        this.diagnosisService = diagnosisService;
    }

    /** 对一批新异常尝试触发诊断；返回实际触发成功（拿到诊断 id）的条数。 */
    int triggerAll(List<PatternMapper.AnomalyRow> anomalies, Instant now) {
        OpsProperties.Patterns cfg = properties.getPatterns();
        if (!cfg.isEnabled() || anomalies == null || anomalies.isEmpty()) {
            return 0;
        }
        // 冷却不能等于窗口宽度：异常每窗口重记一条，900s 冷却 + 15 分钟窗口意味着
        // 持续异常每 15~20 分钟就重跑一次完整诊断（证据采集 + 模型调用 + 落库）。
        // 至少取两倍窗口，让「持续异常」变成隔窗口才诊断一次。
        long cooldownSeconds = Math.max(cfg.getTriggerCooldownSeconds(), cfg.getWindowMinutes() * 60L * 2);
        long requestedBy = properties.getDiagnosis().getUserId();
        if (requestedBy <= 0) {
            // 与 OpsDiagnosisService.catchUp 同一守卫：没有配置请求人时不触发（诊断记录需要归属）。
            log.debug("ops.pattern.trigger.skip reason=no-requested-by");
            return 0;
        }
        int triggered = 0;
        for (PatternMapper.AnomalyRow row : anomalies) {
            if (row.getId() == null) {
                continue;
            }
            try {
                Instant last = mapper.lastTriggeredAt(row.getKind(), row.getTemplateHash(), row.getUnit());
                if (last != null && Duration.between(last, now).getSeconds() < cooldownSeconds) {
                    markSkipped(row, "同模板同类型在冷却窗口内，已跳过诊断");
                    continue;
                }
                String question = buildQuestion(row);
                long diagnosisId = diagnosisService.start(
                        TRIGGER, null, ALERT_NAME, question, requestedBy);
                if (diagnosisId > 0) {
                    mapper.setDiagnosis(row.getId(), diagnosisId);
                    triggered++;
                } else {
                    markSkipped(row, "诊断未受理（已关闭或单飞占用）");
                }
            } catch (RuntimeException failure) {
                // 一条异常触发失败不影响其余异常，也不影响下一轮同步。
                log.warn("ops.pattern.trigger.failed anomalyId={} kind={} reason={}",
                        row.getId(), row.getKind(), failure.toString());
                markSkipped(row, "诊断触发异常：" + failure.getClass().getSimpleName());
            }
        }
        return triggered;
    }

    private void markSkipped(PatternMapper.AnomalyRow row, String reason) {
        String detail = row.getDetail() == null ? reason : row.getDetail() + "（" + reason + "）";
        mapper.updateStatus(row.getId(), "TRIGGER_SKIPPED", PatternSyncService.truncate(detail, 1000));
    }

    private static String buildQuestion(PatternMapper.AnomalyRow row) {
        String detail = row.getDetail() == null ? "日志模板异常" : row.getDetail();
        return PatternSyncService.truncate(
                "检测到日志模板异常（" + row.getKind() + "，单元 " + row.getUnit() + "）：" + detail
                        + "。请结合证据判断这是否为故障信号、可能原因与建议动作。", 500);
    }
}
