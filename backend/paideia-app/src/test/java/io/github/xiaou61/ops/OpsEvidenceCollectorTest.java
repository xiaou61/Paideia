package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.OpsDiagnoser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 证据采集的固定查询、脱敏与降级（WORK-042 REQ-009/REQ-010）。
 *
 * <p>数据源全部打桩，不出网。重点不是"值是多少"，而是：日志先脱敏、Loki 不可达不抛异常、
 * 证据预算用完后不再塞日志。
 */
class OpsEvidenceCollectorTest {

    private final OpsProperties properties = new OpsProperties();
    private OpsSummaryService summary;
    private PrometheusClient prometheus;
    private LokiClient loki;
    private OpsEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        summary = mock(OpsSummaryService.class);
        prometheus = mock(PrometheusClient.class);
        loki = mock(LokiClient.class);
        collector = new OpsEvidenceCollector(properties, summary, prometheus, loki);

        when(summary.summary()).thenReturn(new OpsSummary("2026-10-26T10:00:00Z", true, List.of(
                new OpsSummary.Group("host", "主机", OpsStatus.OK, "磁盘正常",
                        List.of(OpsSummary.Metric.number("diskUsed", 0.42, "ratio"))))));
        when(prometheus.scalar(anyString())).thenReturn(1.0);
        when(prometheus.query(anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("摘要、固定指标与日志都进入证据，日志先脱敏")
    void collectsAndRedactsLogs() {
        when(loki.queryRange(anyString(), any(), any(), anyInt())).thenReturn(List.of(
                new LokiClient.LogLine(Instant.parse("2026-10-26T10:00:00Z"),
                        "ERROR login failed password=Hunter2 from alice@example.com")));

        OpsDiagnoser.Evidence evidence = collector.collect("manual", null, "为什么慢");

        assertThat(evidence.summary()).contains("[主机] OK", "diskUsed = 0.42 ratio");
        assertThat(evidence.metrics()).contains("cpu_percent", "disk_percent");
        assertThat(evidence.logs())
                .contains("***")
                .doesNotContain("Hunter2", "alice@example.com");
    }

    @Test
    @DisplayName("Loki 不可达时写明确占位，不抛异常")
    void lokiDownIsExplicit() {
        when(loki.queryRange(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        String logs = collector.collect("alert", "磁盘告警", null).logs();

        assertThat(logs).contains("无匹配日志（或 Loki 不可达）");
    }

    @Test
    @DisplayName("证据预算用完后不再采集日志")
    void evidenceBudgetCapsLogs() {
        properties.getDiagnosis().setMaxEvidenceChars(50);
        when(loki.queryRange(anyString(), any(), any(), anyInt())).thenReturn(List.of(
                new LokiClient.LogLine(Instant.parse("2026-10-26T10:00:00Z"), "error something")));

        String logs = collector.collect("manual", null, null).logs();

        assertThat(logs).contains("证据预算已用完");
        assertThat(logs).doesNotContain("something");
    }

    @Test
    @DisplayName("未知发版时间如实写未记录")
    void missingDeployIsExplicit() {
        when(prometheus.scalar("time() - max(paideia_deploy_epoch_seconds)")).thenReturn(null);

        assertThat(collector.collect("manual", null, null).deploys())
                .contains("未记录发版时间");
    }
}
