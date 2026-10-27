package io.github.xiaou61.ai.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.internal.memory.MemoryStore;
import io.github.xiaou61.ai.internal.settings.AiSettingsStore;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 记忆工具（WORK-044 TASK-008）。
 *
 * <p>最要紧的一条是**尊重总开关**：用户在界面上关掉个性化记忆之后，
 * 外部助手不该还能偷偷写——这条不测就只会在"我明明关了它还在记"里被发现。
 */
class MemoryToolsTest {

    private static final ToolContext READER =
            new ToolContext(7L, "learner", Set.of(ToolScope.READ), ToolContext.Origin.EXTERNAL);
    private static final ToolContext WRITER =
            new ToolContext(7L, "learner", Set.of(ToolScope.READ, ToolScope.WRITE), ToolContext.Origin.EXTERNAL);

    private final MemoryStore memories = mock(MemoryStore.class);
    private final AiSettingsStore settings = mock(AiSettingsStore.class);

    private static AiSettingsStore.Settings enabled() {
        return new AiSettingsStore.Settings(true, true, true);
    }

    @Test
    void listDescriptorIsReadOnly() {
        ToolDescriptor descriptor = new MemoryTools.ListMemories(memories).descriptor();

        assertThat(descriptor.name()).isEqualTo("memory_list");
        assertThat(descriptor.toolset()).isEqualTo("memory");
        assertThat(descriptor.readOnly()).isTrue();
        assertThat(descriptor.scope()).isEqualTo(ToolScope.READ);
        assertThat(descriptor.defaultGroup()).as("不进默认视图").isFalse();
    }

    @Test
    void rememberDescriptorIsWrite() {
        ToolDescriptor descriptor = new MemoryTools.Remember(memories, settings).descriptor();

        assertThat(descriptor.name()).isEqualTo("memory_remember");
        assertThat(descriptor.toolset()).isEqualTo("memory");
        assertThat(descriptor.readOnly()).isFalse();
        assertThat(descriptor.scope()).isEqualTo(ToolScope.WRITE);
        assertThat(descriptor.defaultGroup()).as("不进默认视图").isFalse();
    }

    @Test
    void rememberWritesAndReturnsId() {
        when(settings.get(7L)).thenReturn(enabled());
        when(memories.rememberText(7L, "我在准备秋招")).thenReturn(42L);

        ToolResult result = new MemoryTools.Remember(memories, settings)
                .call(WRITER, Map.of("content", "我在准备秋招"));

        assertThat(result.error()).isFalse();
        assertThat(result.structured()).containsEntry("memoryId", 42L);
        verify(memories).rememberText(7L, "我在准备秋招");
    }

    /** 关掉总开关后写入必须被拒，且说清原因。 */
    @Test
    void rememberRefusedWhenMemoryDisabled() {
        when(settings.get(7L)).thenReturn(new AiSettingsStore.Settings(false, false, false));

        ToolResult result = new MemoryTools.Remember(memories, settings)
                .call(WRITER, Map.of("content", "不该被记住"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("已关闭");
        verify(memories, never()).rememberText(anyLong(), anyString());
    }

    @Test
    void rememberRejectsTooLongContent() {
        when(settings.get(7L)).thenReturn(enabled());

        ToolResult result = new MemoryTools.Remember(memories, settings)
                .call(WRITER, Map.of("content", "字".repeat(2001)));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("2000");
        verify(memories, never()).rememberText(anyLong(), anyString());
    }

    @Test
    void rememberRejectsBlankContent() {
        assertThat(new MemoryTools.Remember(memories, settings).call(WRITER, Map.of("content", "   ")).text())
                .contains("缺少");
    }

    /** 传 999 被夹到上限 50——不夹的话外部客户端可以一次拉走全部记忆。 */
    @Test
    void listClampsLimitToMaximum() {
        when(memories.list(7L, 50, 0)).thenReturn(List.of());

        new MemoryTools.ListMemories(memories).call(READER, Map.of("limit", 999));

        verify(memories).list(eq(7L), eq(50), eq(0));
    }

    @Test
    void listDefaultsToTwenty() {
        when(memories.list(7L, 20, 0)).thenReturn(List.of());

        new MemoryTools.ListMemories(memories).call(READER, Map.of());

        verify(memories).list(eq(7L), eq(20), eq(0));
    }

    @Test
    void listRendersSourceAndText() {
        when(memories.list(7L, 20, 0)).thenReturn(List.of(
                new MemoryStore.MemoryItem(MemoryStore.MemoryItem.Source.TEXT, 1L, "我在准备秋招",
                        Instant.parse("2026-10-01T00:00:00Z"), "manual", null, null),
                new MemoryStore.MemoryItem(MemoryStore.MemoryItem.Source.STRUCTURED, 2L, "目标是后端",
                        Instant.parse("2026-10-02T00:00:00Z"), "auto", "用户提到目标岗位", 9L)));

        String text = new MemoryTools.ListMemories(memories).call(READER, Map.of()).text();

        assertThat(text).contains("[原话]").contains("我在准备秋招")
                .contains("[事实]").contains("目标是后端");
    }

    @Test
    void listSaysSoWhenEmpty() {
        when(memories.list(anyLong(), anyInt(), anyInt())).thenReturn(List.of());

        assertThat(new MemoryTools.ListMemories(memories).call(READER, Map.of()).text())
                .contains("还没有任何记忆");
    }
}
