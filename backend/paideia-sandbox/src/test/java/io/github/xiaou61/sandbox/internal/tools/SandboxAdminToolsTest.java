package io.github.xiaou61.sandbox.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 数据集治理工具（WORK-039 TASK-005）：只允许碰社区集，只允许两个状态。 */
class SandboxAdminToolsTest {

    private static final ToolContext ADMIN =
            new ToolContext(1L, "admin", Set.of(ToolScope.ADMIN), ToolContext.Origin.EXTERNAL);

    private final SqlDatasetMapper datasets = mock(SqlDatasetMapper.class);

    @Test
    void descriptorsMatchTheGroupContract() throws Exception {
        var descriptors = new java.util.ArrayList<ToolDescriptor>();
        for (Class<?> type : SandboxAdminTools.class.getDeclaredClasses()) {
            if (!PaideiaTool.class.isAssignableFrom(type)) {
                continue;
            }
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object[] args = new Object[constructor.getParameterCount()];
            descriptors.add(((PaideiaTool) constructor.newInstance(args)).descriptor());
        }

        assertThat(descriptors).hasSize(3);
        Set<String> names = new HashSet<>();
        for (ToolDescriptor descriptor : descriptors) {
            assertThat(descriptor.name()).startsWith("admin_review_");
            assertThat(descriptor.toolset()).isEqualTo("admin_review");
            assertThat(descriptor.scope()).isEqualTo(ToolScope.ADMIN);
            assertThat(names.add(descriptor.name())).isTrue();
        }
        assertThat(descriptors).filteredOn(ToolDescriptor::readOnly).hasSize(2);
    }

    @Test
    void privateDatasetCannotBeTakenDown() {
        when(datasets.findByKey("mine-private")).thenReturn(Optional.of(row("mine-private", "private")));

        ToolResult result = new SandboxAdminTools.SetDatasetStatus(datasets).call(ADMIN,
                Map.of("datasetKey", "mine-private", "status", "taken_down"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("只有社区数据集");
        verify(datasets, never()).updateStatus(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onlyTwoStatusesAreAccepted() {
        ToolResult result = new SandboxAdminTools.SetDatasetStatus(datasets).call(ADMIN,
                Map.of("datasetKey", "x", "status", "deleted"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("taken_down 或 published");
    }

    @Test
    void takedownAlsoMarksReportsHandled() {
        when(datasets.findByKey("community-1")).thenReturn(Optional.of(row("community-1", "community")));

        ToolResult result = new SandboxAdminTools.SetDatasetStatus(datasets).call(ADMIN,
                Map.of("datasetKey", "community-1", "status", "taken_down"));

        assertThat(result.error()).isFalse();
        verify(datasets).updateStatus(org.mockito.ArgumentMatchers.eq("community-1"),
                org.mockito.ArgumentMatchers.eq("taken_down"), org.mockito.ArgumentMatchers.any());
        verify(datasets).markReportsHandled(org.mockito.ArgumentMatchers.eq("community-1"),
                org.mockito.ArgumentMatchers.eq("taken_down"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void restoreDoesNotTouchReports() {
        when(datasets.findByKey("community-1")).thenReturn(Optional.of(row("community-1", "community")));

        new SandboxAdminTools.SetDatasetStatus(datasets).call(ADMIN,
                Map.of("datasetKey", "community-1", "status", "published"));

        verify(datasets, never()).markReportsHandled(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    private static SqlDatasetMapper.Row row(String key, String source) {
        return new SqlDatasetMapper.Row(1L, key, "名字", source, 9L, "storage", "{}",
                2, 30, 1024, "published", java.time.Instant.now(), java.time.Instant.now());
    }

}
