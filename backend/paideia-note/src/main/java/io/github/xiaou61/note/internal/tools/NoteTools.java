package io.github.xiaou61.note.internal.tools;

import io.github.xiaou61.note.NoteApi;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import io.github.xiaou61.platform.ToolUri;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 笔记工具组（WORK-019 TASK-004）：两个读、两个写。
 *
 * <p>**写工具是刻意只在 `write` 视图出现的**：`note_create` 与 `note_append` 都会改变用户数据，
 * 目录把它们标成 `readOnly=false`，只读视图因此拿不到它们。
 *
 * <p><b>不做整篇覆盖</b>：追加已经覆盖"让助手帮我记一条"的真实需求，而整篇替换最容易让用户
 * 手写的内容被模型冲掉。
 */
public final class NoteTools {

    private NoteTools() {
    }

    /** 工具参数里的条数上限。 */
    private static final int DEFAULT_LIST = 10;
    private static final int MAX_LIST = 50;

    /** 列表：不返回正文，只给摘要，避免一次把上下文塞满。 */
    @Component
    public static class ListNotes implements PaideiaTool {

        private final NoteApi notes;

        ListNotes(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_list", "note", "列出我的笔记",
                    "列出**当前调用者自己**最近的笔记（标题 + 一段摘要，不含全文）。"
                            + "用户问「我记过什么」「帮我找找之前那条笔记」时先用它。",
                    objectSchema(Map.of(
                            "limit", Map.of("type", "integer", "description", "最多几条，默认 10，上限 50"))),
                    null, true, false, true, ToolScope.READ, true);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            List<NoteApi.NoteBrief> items = notes.list(context.userId(), listLimit(arguments));
            if (items.isEmpty()) {
                return ToolResult.ok("当前用户还没有任何笔记。");
            }
            return ToolResult.ok(String.join("\n", items.stream()
                    .map(item -> "id=" + item.id() + " 标题=" + item.title()
                            + " 摘要=" + (item.excerpt() == null ? "" : item.excerpt()))
                    .toList()));
        }
    }

    /** 读一篇的全文。 */
    @Component
    public static class GetNote implements PaideiaTool {

        private final NoteApi notes;

        GetNote(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_get", "note", "读取笔记正文",
                    "按 id 读取**当前调用者自己**的一篇笔记全文。id 一般来自 note_list，"
                            + "或用户递过来的对象引用（uri，如 note:45）。"
                            + "不存在或不是本人的都返回「没有找到」——两种情况刻意不区分。",
                    objectSchema(Map.of(
                            "noteId", Map.of("type", "integer", "description", "笔记 id"),
                            "uri", Map.of("type", "string",
                                    "description", "对象引用，如 note:45；传了就优先于 noteId"))),
                    null, true, false, true, ToolScope.READ, true);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long fromArgs = longOf(arguments, "noteId");
            if (fromArgs == null) {
                fromArgs = ToolUri.numericId(stringOf(arguments, "uri"), "note").orElse(null);
            }
            if (fromArgs == null) {
                return ToolResult.fail("缺少 noteId 或 uri（如 note:45）");
            }
            long id = fromArgs;
            return notes.find(context.userId(), id)
                    .map(note -> ToolResult.ok("标题：" + note.title() + "\n\n" + note.body()))
                    .orElseGet(() -> ToolResult.fail("没有找到 id=" + id + " 的笔记"));
        }
    }

    /** 新建（写）。 */
    @Component
    public static class CreateNote implements PaideiaTool {

        private final NoteApi notes;

        CreateNote(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_create", "note", "新建笔记",
                    "为**当前调用者**新建一条笔记。这是一次写入：内容会立即保存到他的笔记列表里。"
                            + "模型自己判断要不要写之前，应当先确认用户确实表达了「记下来」的意图。",
                    objectSchema(Map.of(
                            "title", Map.of("type", "string", "description", "标题，一句话（不超过 200 字）"),
                            "body", Map.of("type", "string", "description", "正文，可多行；可以为空"))),
                    Map.of("type", "object", "properties", Map.of(
                            "noteId", Map.of("type", "integer", "description", "新笔记 id"))),
                    false, false, false, ToolScope.WRITE, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            String title = stringOf(arguments, "title");
            if (title == null) {
                return ToolResult.fail("标题不能为空");
            }
            try {
                long id = notes.create(context.userId(), title, stringOf(arguments, "body"));
                return ToolResult.ok("已新建笔记 id=" + id + "（" + title + "）。", Map.of("noteId", id));
            } catch (RuntimeException failure) {
                return ToolResult.fail("新建失败：" + messageOf(failure));
            }
        }
    }

    /** 追加（写）。 */
    @Component
    public static class AppendNote implements PaideiaTool {

        private final NoteApi notes;

        AppendNote(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_append", "note", "追加到笔记末尾",
                    "在**当前调用者自己**的一篇笔记末尾追加一段文字（换两行后接上），"
                            + "不覆盖原有内容。这是一次写入。笔记不存在或不是本人的返回「没有找到」。",
                    objectSchema(Map.of(
                            "noteId", Map.of("type", "integer", "description", "笔记 id"),
                            "text", Map.of("type", "string", "description", "要追加的正文，可多行"))),
                    null, false, false, false, ToolScope.WRITE, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long id = longOf(arguments, "noteId");
            String text = stringOf(arguments, "text");
            if (id == null || text == null) {
                return ToolResult.fail("需要 noteId 与 text");
            }
            try {
                return notes.append(context.userId(), id, text)
                        .map(note -> ToolResult.ok("已追加到笔记 id=" + note.id() + "（标题：" + note.title() + "）。"))
                        .orElseGet(() -> ToolResult.fail("没有找到 id=" + id + " 的笔记"));
            } catch (RuntimeException failure) {
                return ToolResult.fail("追加失败：" + messageOf(failure));
            }
        }
    }

    // --------------------------------------------------------------- helpers

    static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.of());
    }

    static int listLimit(Map<String, Object> arguments) {
        Long raw = longOf(arguments, "limit");
        if (raw == null || raw <= 0) {
            return DEFAULT_LIST;
        }
        return (int) Math.min(raw, MAX_LIST);
    }

    static String stringOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    static Long longOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
