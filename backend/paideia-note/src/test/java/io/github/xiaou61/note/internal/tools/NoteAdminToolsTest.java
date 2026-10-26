package io.github.xiaou61.note.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.xiaou61.note.internal.share.ShareService;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 笔记举报工具（WORK-038 TASK-005）。 */
class NoteAdminToolsTest {

    private static final ToolContext ADMIN =
            new ToolContext(1L, "admin", Set.of(ToolScope.ADMIN), ToolContext.Origin.EXTERNAL);

    private final ShareService shares = mock(ShareService.class);

    @Test
    void descriptorsMatchTheGroupContract() throws Exception {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (Class<?> type : NoteAdminTools.class.getDeclaredClasses()) {
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
            assertThat(descriptor.name()).startsWith("admin_review_");
            assertThat(descriptor.toolset()).isEqualTo("admin_review");
            assertThat(descriptor.scope()).isEqualTo(ToolScope.ADMIN);
            assertThat(names.add(descriptor.name())).isTrue();
        }
        assertThat(descriptors).filteredOn(ToolDescriptor::destructive).hasSize(1);
    }

    @Test
    void takedownUsesReportId() {
        ToolResult result = new NoteAdminTools.Takedown(shares).call(ADMIN, Map.of("reportId", 42));

        assertThat(result.error()).isFalse();
        verify(shares).takedown(42L);
    }
}
