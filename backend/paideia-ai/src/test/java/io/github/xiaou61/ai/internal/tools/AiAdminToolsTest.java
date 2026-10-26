package io.github.xiaou61.ai.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.internal.telemetry.AiRunAdminService;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** AI 观测工具的不变量与一次真实返回形状（WORK-039 TASK-004）。 */
class AiAdminToolsTest {

    private static final ToolContext ADMIN =
            new ToolContext(1L, "admin", Set.of(ToolScope.ADMIN), ToolContext.Origin.EXTERNAL);

    private final AiRunAdminService runs = mock(AiRunAdminService.class);

    @Test
    void allAiToolsAreReadOnlyAdminScoped() throws Exception {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (Class<?> type : AiAdminTools.class.getDeclaredClasses()) {
            if (!PaideiaTool.class.isAssignableFrom(type)) {
                continue;
            }
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object[] args = new Object[constructor.getParameterCount()];
            descriptors.add(((PaideiaTool) constructor.newInstance(args)).descriptor());
        }

        assertThat(descriptors).hasSize(5);
        Set<String> names = new HashSet<>();
        for (ToolDescriptor descriptor : descriptors) {
            assertThat(descriptor.name()).startsWith("admin_ai_");
            assertThat(descriptor.toolset()).isEqualTo("admin_ai");
            assertThat(descriptor.scope()).isEqualTo(ToolScope.ADMIN);
            assertThat(descriptor.readOnly()).isTrue();
            assertThat(descriptor.destructive()).isFalse();
            assertThat(names.add(descriptor.name())).isTrue();
        }
    }

    @Test
    void overviewCombinesThreePanels() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-08T00:00:00Z");
        when(runs.overview(from, to, null, null, null, null)).thenReturn(new AiRunAdminService.Overview(
                from, to, false, 120L,
                new AiRunAdminService.StatusCounts(110L, 8L, 2L, 0L, 0L, 0L),
                0.9166, 120, 900, 80, 300, 5000, 2000, 1000, 900, 800, 0.5, 1.0,
                List.of(), List.of(), List.of(), List.of()));
        when(runs.health()).thenReturn(new AiRunAdminService.Health(
                true, 1.0, 2, 100, 0, 0, to));
        when(runs.reconcile(from, to)).thenReturn(new AiRunAdminService.Reconcile(
                from, to, 50, List.of(new AiRunAdminService.ReconcileDay("2026-10-07", 100, 90, 10, false))));

        ToolResult result = new AiAdminTools.Overview(runs).call(ADMIN,
                Map.of("from", "2026-10-01T00:00:00Z", "to", "2026-10-08T00:00:00Z"));

        assertThat(result.error()).isFalse();
        assertThat(result.text())
                .contains("调用=120")
                .contains("成功率=91.7%")
                .contains("【采集健康】")
                .contains("【积分对账】")
                .contains("2026-10-07");
    }

    @Test
    void badInstantIsReportedNotThrown() {
        ToolResult result = new AiAdminTools.ListRuns(runs).call(ADMIN, Map.of("from", "上周"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("ISO-8601");
    }
}
