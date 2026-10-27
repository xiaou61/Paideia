package io.github.xiaou61.ai.internal.tools;

import io.github.xiaou61.ai.internal.memory.MemoryStore;
import io.github.xiaou61.ai.internal.settings.AiSettingsStore;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 记忆域的外部工具（WORK-044 TASK-008）。
 *
 * <p>这是四个域里**唯一有写操作**的：外部助手说"帮我记住这个"是最自然的使用场景。
 *
 * <p>写入用 `rememberText` 而不是 `rememberStructured`：后者要 `kind` 枚举（事实/偏好/目标…），
 * 让外部模型猜我们的内部枚举，猜错的后果是一条归错类的记忆——那比不记更糟。
 * `rememberText` 是"他说过的话"那一路，语义上正好对应"帮我记住这个"。
 *
 * <p>**尊重记忆总开关**：用户在界面上关掉个性化记忆之后，外部助手也不该偷偷写。
 * 这条与 `AiMemoryController` 是同一道闸，不能只在前端做。
 */
public final class MemoryTools {

    private static final String GROUP = "memory";
    private static final int MAX_CONTENT_CHARS = 2_000;
    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 50;

    private MemoryTools() {
    }

    /** 列出本人记忆。 */
    @Component
    public static class ListMemories implements PaideiaTool {

        private final MemoryStore memories;

        ListMemories(MemoryStore memories) {
            this.memories = memories;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "memory_list", GROUP, "列出我的记忆",
                    "列出**当前调用者自己**保存的记忆（助手记住的关于他的事实与他说过的话）。"
                            + "用户问「你记得我什么」时用它。",
                    ResumeTools.objectSchema(Map.of(
                            "limit", Map.of("type", "integer",
                                    "description", "最多几条，默认 20，上限 50"))),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            int limit = clampLimit(ResumeTools.longOf(arguments, "limit"));
            List<MemoryStore.MemoryItem> items = memories.list(context.userId(), limit, 0);
            if (items.isEmpty()) {
                return ToolResult.ok("当前用户还没有任何记忆。");
            }
            List<String> lines = new ArrayList<>();
            for (MemoryStore.MemoryItem item : items) {
                lines.add("[" + (item.source() == MemoryStore.MemoryItem.Source.STRUCTURED ? "事实" : "原话")
                        + "] " + item.text() + "  （" + item.createdAt() + "）");
            }
            return ToolResult.ok(String.join("\n", lines));
        }

        private static int clampLimit(Long raw) {
            if (raw == null || raw <= 0) {
                return DEFAULT_LIST_LIMIT;
            }
            return (int) Math.min(raw, MAX_LIST_LIMIT);
        }
    }

    /** 写一条记忆。 */
    @Component
    public static class Remember implements PaideiaTool {

        private final MemoryStore memories;
        private final AiSettingsStore settings;

        Remember(MemoryStore memories, AiSettingsStore settings) {
            this.memories = memories;
            this.settings = settings;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "memory_remember", GROUP, "记住一件事",
                    "把一句话存进**当前调用者**的长期记忆。用户说「记住这个」「帮我记一下」时用它。"
                            + "这是一次写入：内容会立刻出现在他的记忆列表里。"
                            + "如果用户关闭了个性化记忆，这次写入会被拒绝。",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "content", Map.of("type", "string",
                                            "description", "要记住的内容，一句话说清，不要超过 2000 字符")),
                            "required", List.of("content")),
                    Map.of("type", "object",
                            "properties", Map.of("memoryId", Map.of("type", "integer"))),
                    false, false, false, ToolScope.WRITE, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Object raw = arguments == null ? null : arguments.get("content");
            String content = raw == null ? "" : raw.toString().trim();
            if (content.isEmpty()) {
                return ToolResult.fail("缺少 content");
            }
            if (content.length() > MAX_CONTENT_CHARS) {
                return ToolResult.fail("内容不能超过 " + MAX_CONTENT_CHARS + " 个字符");
            }
            // 总开关关着时拒绝并说清原因：静默写进去等于"看起来关了其实还在记"
            if (!settings.get(context.userId()).memoryEnabled()) {
                return ToolResult.fail("个性化记忆已关闭，这条不会被记住。可以在「记忆」里打开。");
            }
            long id = memories.rememberText(context.userId(), content);
            return ToolResult.ok("已记住。", Map.of("memoryId", id));
        }
    }
}
