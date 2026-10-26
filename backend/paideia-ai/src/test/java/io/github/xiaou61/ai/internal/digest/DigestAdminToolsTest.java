package io.github.xiaou61.ai.internal.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 速递工具（WORK-038 TASK-006）：默认日期、坏日期、硬删。 */
class DigestAdminToolsTest {

    private static final ToolContext ADMIN =
            new ToolContext(1L, "admin", Set.of(ToolScope.ADMIN), ToolContext.Origin.EXTERNAL);

    private final DigestMapper mapper = mock(DigestMapper.class);
    private final DigestProperties properties = mock(DigestProperties.class);

    @Test
    void descriptorsMatchTheGroupContract() throws Exception {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (Class<?> type : DigestAdminTools.class.getDeclaredClasses()) {
            if (!PaideiaTool.class.isAssignableFrom(type)) {
                continue;
            }
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object[] args = new Object[constructor.getParameterCount()];
            descriptors.add(((PaideiaTool) constructor.newInstance(args)).descriptor());
        }

        assertThat(descriptors).hasSize(2);
        Set<String> names = new HashSet<>();
        for (ToolDescriptor descriptor : descriptors) {
            assertThat(descriptor.name()).startsWith("admin_digest_");
            assertThat(descriptor.toolset()).isEqualTo("admin_digest");
            assertThat(descriptor.scope()).isEqualTo(ToolScope.ADMIN);
            assertThat(names.add(descriptor.name())).isTrue();
        }
        assertThat(descriptors).filteredOn(ToolDescriptor::destructive).hasSize(1);
    }

    @Test
    void missingDateUsesBusinessToday() {
        when(properties.today()).thenReturn(LocalDate.of(2026, 10, 26));
        when(mapper.findByDate(LocalDate.of(2026, 10, 26))).thenReturn(List.of());

        ToolResult result = new DigestAdminTools.ListItems(mapper, properties).call(ADMIN, Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("2026-10-26");
    }

    @Test
    void badDateIsReportedNotThrown() {
        ToolResult result = new DigestAdminTools.ListItems(mapper, properties).call(ADMIN,
                Map.of("date", "今天"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("YYYY-MM-DD");
    }

    @Test
    void deleteMissingItemFails() {
        when(mapper.deleteById(9L)).thenReturn(0);

        ToolResult result = new DigestAdminTools.DeleteItem(mapper).call(ADMIN, Map.of("id", 9));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("不存在");
        verify(mapper).deleteById(9L);
    }
}
