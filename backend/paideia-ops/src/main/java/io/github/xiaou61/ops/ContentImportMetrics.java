package io.github.xiaou61.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 内容导入时效指标（WORK-074 C4）。
 *
 * <p>暴露 `paideia.content.days_since_last_import`：距上次成功导入的天数（-1=从未导入）。
 * Grafana 规则据此告警"内容很久没导入了"——这是部署机**能看见**的信号（它看不到仓库内容）。
 * 真正的"源改了没导入"由仓库侧 `scripts/check-content-drift.mjs` 用哈希比对，两者互补。
 */
@Component
class ContentImportMetrics {

    ContentImportMetrics(MeterRegistry registry, ContentImportRunRegistry runs) {
        Gauge.builder("paideia.content.days_since_last_import", runs, r -> {
                    Instant last = r.lastAt();
                    return last == null ? -1.0 : (double) Duration.between(last, Instant.now()).toDays();
                })
                .description("距上次内容导入的天数（-1 表示从未导入，WORK-074）")
                .register(registry);
    }
}
