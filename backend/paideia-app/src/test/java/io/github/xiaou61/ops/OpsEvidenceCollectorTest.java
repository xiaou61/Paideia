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
import java.util.Map;
import java.util.Optional;
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
    private TempoClient tempo;
    private OpsEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        summary = mock(OpsSummaryService.class);
        prometheus = mock(PrometheusClient.class);
        loki = mock(LokiClient.class);
        tempo = mock(TempoClient.class);
        collector = new OpsEvidenceCollector(properties, summary, prometheus, loki, tempo);
        when(tempo.search(any(), any(), anyInt())).thenReturn(List.of());

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
    @DisplayName("基线行包含 1h/24h/7d 与 ratio；样本不足写基线不足")
    void baselineFormatting() {
        when(prometheus.scalar("paideia:baseline_cpu:current")).thenReturn(25.0);
        when(prometheus.scalar("paideia:baseline_cpu:1h")).thenReturn(20.0);
        when(prometheus.scalar("paideia:baseline_cpu:24h")).thenReturn(18.0);
        when(prometheus.scalar("paideia:baseline_cpu:7d")).thenReturn(20.0);
        when(prometheus.scalar("paideia:baseline_cpu:baseline")).thenReturn(20.0);
        when(prometheus.scalar("paideia:baseline_cpu:ratio")).thenReturn(1.25);
        when(prometheus.scalar("paideia:baseline_cpu:samples7d")).thenReturn(20160.0);
        when(loki.scalar(anyString(), any())).thenReturn(2.0);

        String baselines = collector.collect("manual", null, null).baselines();

        assertThat(baselines)
                .contains("cpu current=25.00%", "1h=20.00%", "24h=18.00%",
                        "7d_same_hour=20.00%", "baseline=20.00%", "ratio=1.25x", "samples_7d=20160")
                .contains("http_5xx_percent = 基线不足")
                .contains("log_errors current=2 7d_same_hour=2 ratio=1.00x");
    }

    @Test
    @DisplayName("SLO 的 SLI 与燃尽率进入基线证据，无流量写无流量")
    void sloEvidence() {
        // 默认桩：所有标量都是 1.0 → 四条 SLI 都是 100%、燃尽率 1.0
        when(prometheus.scalar("paideia:sli_ai_success:ratio1h")).thenReturn(null);

        String baselines = collector.collect("alert", "SLO 快烧：HTTP 可用性", null).baselines();

        assertThat(baselines)
                .contains("slo_http_availability sli_1h=100.000% burn_1h=1.00x")
                .contains("slo_http_latency sli_1h=100.000%")
                .contains("slo_login_success sli_1h=100.000%")
                .contains("slo_ai_success = 无流量（SLI 不可计算）")
                .contains("slo_scheduled_success sli=100.0%");
    }

    @Test
    @DisplayName("trace 摘要只带最慢 span 与白名单属性")
    void traceEvidence() {
        when(tempo.search(any(), any(), anyInt())).thenReturn(List.of(
                new TempoClient.TraceSummary("trace-1", "POST /api/v1/ai/chat", 2400, true, Instant.now())));
        when(tempo.trace("trace-1")).thenReturn(Optional.of(new TempoClient.TraceDetail("trace-1",
                List.of(new TempoClient.Span("SELECT users", 1800, "STATUS_CODE_ERROR",
                        Map.of("db.system", "mysql", "db.statement", "select * from users"))))));

        String traces = collector.collect("manual", null, null).traces();

        assertThat(traces)
                .contains("trace=trace-1", "root=POST", "2400ms", "error=true",
                        "slowest=SELECT users", "1800ms", "db.system=mysql")
                .doesNotContain("db.statement", "select * from users");
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
