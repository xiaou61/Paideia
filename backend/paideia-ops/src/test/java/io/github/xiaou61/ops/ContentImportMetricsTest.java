package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 内容导入时效 Gauge（WORK-074 C4）。纯内存，无 Spring/DB。 */
class ContentImportMetricsTest {

    @Test
    void gaugeIsMinusOneWhenNeverImportedAndZeroAfterImport() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ContentImportRunRegistry runs = new ContentImportRunRegistry();
        new ContentImportMetrics(meters, runs);

        assertThat(meters.get("paideia.content.days_since_last_import").gauge().value()).isEqualTo(-1.0);

        runs.record("content/courses", "h", 1, 0, 0);
        assertThat(meters.get("paideia.content.days_since_last_import").gauge().value()).isEqualTo(0.0);
    }
}
