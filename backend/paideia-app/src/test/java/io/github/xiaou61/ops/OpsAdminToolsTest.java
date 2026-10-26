package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolCatalog;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolQuery;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 运维摘要工具（WORK-039 TASK-006）：只读、按组渲染。 */
class OpsAdminToolsTest {

    private static final ToolContext ADMIN =
            new ToolContext(1L, "admin", Set.of(ToolScope.ADMIN), ToolContext.Origin.EXTERNAL);

    private final OpsSummaryService service = mock(OpsSummaryService.class);
    private final OpsEvidenceCollector collector = mock(OpsEvidenceCollector.class);

    @Test
    void summaryIsReadOnlyAdminTool() {
        PaideiaTool tool = new OpsAdminTools.Summary(service);
        assertThat(tool.descriptor().name()).isEqualTo("admin_ops_summary");
        assertThat(tool.descriptor().toolset()).isEqualTo("admin_ops");
        assertThat(tool.descriptor().scope()).isEqualTo(ToolScope.ADMIN);
        assertThat(tool.descriptor().readOnly()).isTrue();
    }

    @Test
    void rendersGroupsAndMetrics() {
        when(service.summary()).thenReturn(new OpsSummary("2026-10-26T10:00:00Z", true, List.of(
                new OpsSummary.Group("paideia", "平台服务", OpsStatus.WARN, "内存偏高", List.of(
                        OpsSummary.Metric.number("内存", 1.8, "GB"),
                        OpsSummary.Metric.text("备份", "6 小时前"))))));

        ToolResult result = new OpsAdminTools.Summary(service).call(ADMIN, Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.text())
                .contains("平台服务")
                .contains("WARN")
                .contains("内存偏高")
                .contains("1.8 GB")
                .contains("6 小时前");
    }

    @Test
    void newOpsToolsAreReadOnlyAndAdminScoped() {
        for (PaideiaTool tool : List.of(
                new OpsAdminTools.Metrics(collector),
                new OpsAdminTools.Logs(collector),
                new OpsAdminTools.Alerts(collector))) {
            ToolDescriptor descriptor = tool.descriptor();
            assertThat(descriptor.readOnly()).isTrue();
            assertThat(descriptor.destructive()).isFalse();
            assertThat(descriptor.scope()).isEqualTo(ToolScope.ADMIN);
            assertThat(descriptor.defaultGroup()).isFalse();
        }
    }

    @Test
    void onlyAdminViewsSeeTheNewTools() {
        ToolCatalog catalog = new ToolCatalog(List.of(
                new OpsAdminTools.Metrics(collector),
                new OpsAdminTools.Logs(collector),
                new OpsAdminTools.Alerts(collector)));

        assertThat(catalog.view(new ToolQuery(
                Set.of("admin_ops"), Set.of(), false, "ADMIN", Set.of(ToolScope.ADMIN), false)))
                .extracting(ToolDescriptor::name)
                .containsExactly("admin_ops_alerts", "admin_ops_logs", "admin_ops_metrics");

        assertThat(catalog.view(new ToolQuery(
                Set.of("admin_ops"), Set.of(), false, "USER", Set.of(ToolScope.READ, ToolScope.ADMIN), false)))
                .isEmpty();
        // 内部助手只拿 READ scope（既有行为），ADMIN 工具不会进它的清单。
        assertThat(catalog.view(ToolQuery.internal(Set.of("admin_ops"), "ADMIN"))).isEmpty();
    }

    @Test
    void unknownMetricIsRejected() {
        ToolResult result = new OpsAdminTools.Metrics(collector)
                .call(ADMIN, Map.of("query", "up{job=~\".*\"}"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("未知指标", "cpu_percent");
    }

    @Test
    void metricsLogsAndAlertsReturnText() {
        when(collector.metric("cpu_percent")).thenReturn("cpu_percent = 12.0 %");
        when(collector.logs("paideia", "error", 30, 10)).thenReturn("2026-10-26T10:00:00Z error ***");
        when(collector.firingAlerts()).thenReturn("- disk-low severity=warning");

        assertThat(new OpsAdminTools.Metrics(collector)
                .call(ADMIN, Map.of("query", "cpu_percent")).text())
                .isEqualTo("cpu_percent = 12.0 %");
        assertThat(new OpsAdminTools.Logs(collector)
                .call(ADMIN, Map.of("unit", "paideia", "level", "error", "minutes", 30, "limit", 10)).text())
                .contains("error ***");
        assertThat(new OpsAdminTools.Alerts(collector).call(ADMIN, Map.of()).text())
                .contains("disk-low");
    }

    @Test
    void unknownLogEnumsAreRejected() {
        assertThat(new OpsAdminTools.Logs(collector).call(ADMIN, Map.of("unit", "mysql")).error()).isTrue();
        assertThat(new OpsAdminTools.Logs(collector).call(ADMIN, Map.of("level", "debug")).error()).isTrue();
    }
}
