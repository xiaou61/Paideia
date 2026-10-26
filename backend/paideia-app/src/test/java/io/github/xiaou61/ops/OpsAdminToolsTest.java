package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 运维摘要工具（WORK-038 TASK-006）：只读、按组渲染。 */
class OpsAdminToolsTest {

    private static final ToolContext ADMIN =
            new ToolContext(1L, "admin", Set.of(ToolScope.ADMIN), ToolContext.Origin.EXTERNAL);

    private final OpsSummaryService service = mock(OpsSummaryService.class);

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
}
