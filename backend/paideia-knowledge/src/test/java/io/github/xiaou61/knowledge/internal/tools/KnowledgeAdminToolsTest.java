package io.github.xiaou61.knowledge.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.knowledge.internal.admin.EntryAdminService;
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

/**
 * 管理内容工具的不变量与最关键的行为（WORK-039 TASK-002）。
 *
 * <p>重点是"先读后写"：底层服务把 null 当清空，工具不补原值就会让模型少写一个字段时
 * 静默抹掉正文。
 */
class KnowledgeAdminToolsTest {

    private static final ToolContext ADMIN =
            new ToolContext(1L, "admin", Set.of(ToolScope.ADMIN), ToolContext.Origin.EXTERNAL);

    private final EntryAdminService entries = mock(EntryAdminService.class);

    @Test
    void allContentToolsAreAdminScopedAndPrefixed() throws Exception {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (Class<?> type : KnowledgeAdminTools.class.getDeclaredClasses()) {
            if (!PaideiaTool.class.isAssignableFrom(type)) {
                continue;
            }
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object[] args = new Object[constructor.getParameterCount()];
            descriptors.add(((PaideiaTool) constructor.newInstance(args)).descriptor());
        }

        assertThat(descriptors).hasSize(15);
        Set<String> names = new HashSet<>();
        for (ToolDescriptor descriptor : descriptors) {
            assertThat(descriptor.name()).startsWith(descriptor.toolset() + "_");
            assertThat(descriptor.toolset()).isEqualTo("admin_content");
            assertThat(descriptor.scope()).isEqualTo(ToolScope.ADMIN);
            assertThat(descriptor.readOnly() && descriptor.destructive()).isFalse();
            assertThat(names.add(descriptor.name())).as("重名：" + descriptor.name()).isTrue();
        }
        assertThat(descriptors).filteredOn(ToolDescriptor::destructive).hasSize(6);
        assertThat(descriptors).filteredOn(ToolDescriptor::readOnly).hasSize(3);
    }

    @Test
    void updateEntryKeepsUnspecifiedFields() {
        when(entries.detail(5L)).thenReturn(new EntryAdminService.AdminEntryDetail(
                5L, "旧标题", "原正文", 2L, "分类", "draft", 3, null, List.of()));

        ToolResult result = new KnowledgeAdminTools.SaveEntry(entries).call(ADMIN,
                Map.of("id", 5, "title", "新标题", "status", "published"));

        assertThat(result.error()).isFalse();
        verify(entries).update(5L, "新标题", "原正文", 2L, "published", 3);
    }

    @Test
    void updateEntryCanClearBodyWhenExplicitlyEmpty() {
        when(entries.detail(5L)).thenReturn(new EntryAdminService.AdminEntryDetail(
                5L, "旧标题", "原正文", 2L, "分类", "draft", null, null, List.of()));

        new KnowledgeAdminTools.SaveEntry(entries).call(ADMIN,
                Map.of("id", 5, "title", "标题", "status", "draft", "body", ""));

        verify(entries).update(5L, "标题", "", 2L, "draft", null);
    }

    @Test
    void createEntryRequiresTitleAndStatus() {
        ToolResult result = new KnowledgeAdminTools.SaveEntry(entries).call(ADMIN,
                Map.of("title", "标题"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("status");
    }

    @Test
    void serviceFailureBecomesErrorTextNotException() {
        when(entries.detail(9L)).thenThrow(new IllegalStateException("条目不存在"));

        ToolResult result = new KnowledgeAdminTools.SaveEntry(entries).call(ADMIN,
                Map.of("id", 9, "title", "标题", "status", "draft"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("条目不存在");
    }
}
