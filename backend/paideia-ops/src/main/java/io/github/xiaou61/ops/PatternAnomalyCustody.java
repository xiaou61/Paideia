package io.github.xiaou61.ops;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模板异常账本的收尾（WORK-085 补强，2026-11-19）。
 *
 * <p>检测只负责「发现」，此前没有任何「关闭」路径：异常一旦写入就是 OPEN，{@code paideia_ops_pattern_anomalies_open}
 * 只增不减，Grafana 规则 {@code > bool 0} 因此永久 firing——应用每次重启让指标短暂缺失（noData 按 OK 处理），
 * 恢复 5 分钟后又重新触发一次告警、再发一封诊断邮件。收尾分两档：
 *
 * <ol>
 *   <li>诊断已完成（DONE）：报告本身就是处置结果，关闭异常；</li>
 *   <li>超过 {@code auto-resolve-minutes} 仍无人处理：自动归档（诊断失败/未受理时的兜底）。</li>
 * </ol>
 *
 * <p>两档都写 {@code resolved_at}，不删行：账本和全文检索仍能查到这次异常以及它当时的数据。
 */
@Service
class PatternAnomalyCustody {

    private static final Logger log = LoggerFactory.getLogger(PatternAnomalyCustody.class);

    private final OpsProperties properties;
    private final PatternMapper mapper;

    PatternAnomalyCustody(OpsProperties properties, PatternMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 一轮收尾；返回本轮关闭的异常条数。任一步失败都不影响同步与检测继续跑。 */
    int close(Instant now) {
        int closed = 0;
        try {
            closed += mapper.resolveDiagnosed(now);
        } catch (RuntimeException failure) {
            log.warn("ops.pattern.close.failed step=diagnosed reason={}", failure.toString());
        }
        try {
            Instant before = now.minusSeconds(properties.getPatterns().getAutoResolveMinutes() * 60L);
            closed += mapper.closeStale(before, now);
        } catch (RuntimeException failure) {
            log.warn("ops.pattern.close.failed step=stale reason={}", failure.toString());
        }
        if (closed > 0) {
            log.info("ops.pattern.close closed={}", closed);
        }
        return closed;
    }
}
