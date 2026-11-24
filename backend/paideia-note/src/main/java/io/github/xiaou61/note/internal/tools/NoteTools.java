package io.github.xiaou61.note.internal.tools;

import io.github.xiaou61.note.NoteApi;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolArgs;
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

    /** 列出知识库（读）。 */
    @Component
    public static class ListBooks implements PaideiaTool {

        private final NoteApi notes;

        ListBooks(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_list_books", "note", "列出我的知识库",
                    "列出**当前调用者自己**的知识库（不含文档正文）。用户问「我有几个知识库」"
                            + "「我的笔记本在哪」时先用它，再用 note_tree 展开某个库。",
                    objectSchema(Map.of()),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            List<NoteApi.BookBrief> items = notes.listBooks(context.userId());
            if (items.isEmpty()) {
                return ToolResult.ok("当前用户还没有任何知识库。");
            }
            return ToolResult.ok(String.join("\n", items.stream()
                    .map(book -> "id=" + book.id() + " 名称=" + book.name())
                    .toList()));
        }
    }

    /** 展开一个知识库的节点树（读）。 */
    @Component
    public static class GetTree implements PaideiaTool {

        private final NoteApi notes;

        GetTree(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_tree", "note", "展开知识库目录树",
                    "按 bookId 列出**当前调用者自己**某个知识库里的全部目录与文档（扁平列表，"
                            + "父子关系由 parentId 表示）。bookId 一般来自 note_list_books。",
                    objectSchema(Map.of(
                            "bookId", Map.of("type", "integer", "description", "知识库 id"))),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long bookId = longOf(arguments, "bookId");
            if (bookId == null) {
                return ToolResult.fail("缺少 bookId");
            }
            List<NoteApi.NoteNode> nodes = notes.tree(context.userId(), bookId);
            if (nodes.isEmpty()) {
                return ToolResult.ok("该知识库还没有任何目录或文档。");
            }
            return ToolResult.ok(String.join("\n", nodes.stream()
                    .map(node -> "id=" + node.id() + " kind=" + node.kind()
                            + " parentId=" + (node.parentId() == null ? 0 : node.parentId())
                            + " 标题=" + node.title())
                    .toList()));
        }
    }

    /** 全文检索（读）。 */
    @Component
    public static class SearchNotes implements PaideiaTool {

        private final NoteApi notes;

        SearchNotes(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_search", "note", "检索我的笔记",
                    "在**当前调用者自己**的全部笔记里做全文检索（标题与正文），返回标题与摘要。"
                            + "用户问「我哪篇笔记提过 X」时用它。",
                    objectSchema(Map.of(
                            "keyword", Map.of("type", "string", "description", "检索关键词"),
                            "limit", Map.of("type", "integer", "description", "最多几条，默认 10，上限 50"))),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            String keyword = stringOf(arguments, "keyword");
            if (keyword == null || keyword.isBlank()) {
                return ToolResult.fail("需要 keyword");
            }
            List<NoteApi.NoteBrief> items = notes.search(context.userId(), keyword, listLimit(arguments));
            if (items.isEmpty()) {
                return ToolResult.ok("没有匹配「" + keyword + "」的笔记。");
            }
            return ToolResult.ok(String.join("\n", items.stream()
                    .map(item -> "id=" + item.id() + " 标题=" + item.title()
                            + " 摘要=" + (item.excerpt() == null ? "" : item.excerpt()))
                    .toList()));
        }
    }

    /** 出链/入链（读）。 */
    @Component
    public static class GetLinks implements PaideiaTool {

        private final NoteApi notes;

        GetLinks(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_links", "note", "查看笔记的双向链接",
                    "读取**当前调用者自己**某篇笔记的出链（它指向谁）与入链（谁指向它）。"
                            + "不存在或不是本人的返回「没有找到」。",
                    objectSchema(Map.of(
                            "noteId", Map.of("type", "integer", "description", "笔记 id"))),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long noteId = longOf(arguments, "noteId");
            if (noteId == null) {
                return ToolResult.fail("缺少 noteId");
            }
            NoteApi.Links links = notes.links(context.userId(), noteId);
            String outbound = links.outbound().isEmpty() ? "（无）"
                    : String.join(", ", links.outbound().stream().map(r -> "id=" + r.noteId() + " " + r.title()).toList());
            String inbound = links.inbound().isEmpty() ? "（无）"
                    : String.join(", ", links.inbound().stream().map(r -> "id=" + r.noteId() + " " + r.title()).toList());
            return ToolResult.ok("出链：" + outbound + "\n入链：" + inbound);
        }
    }

    /** 新建知识库（写）。 */
    @Component
    public static class CreateBook implements PaideiaTool {

        private final NoteApi notes;

        CreateBook(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_create_book", "note", "新建知识库",
                    "为**当前调用者**新建一个知识库。这是一次写入。",
                    objectSchema(Map.of(
                            "name", Map.of("type", "string", "description", "知识库名称，不超过 200 字"),
                            "description", Map.of("type", "string", "description", "简介，可为空"))),
                    Map.of("type", "object", "properties", Map.of(
                            "bookId", Map.of("type", "integer", "description", "新知识库 id"))),
                    false, false, false, ToolScope.WRITE, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            String name = stringOf(arguments, "name");
            if (name == null) {
                return ToolResult.fail("名称不能为空");
            }
            try {
                long id = notes.createBook(context.userId(), name, stringOf(arguments, "description"));
                return ToolResult.ok("已新建知识库 id=" + id + "（" + name + "）。", Map.of("bookId", id));
            } catch (RuntimeException failure) {
                return ToolResult.fail("新建失败：" + messageOf(failure));
            }
        }
    }

    /** 新建目录（写）。 */
    @Component
    public static class CreateFolder implements PaideiaTool {

        private final NoteApi notes;

        CreateFolder(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_create_folder", "note", "新建目录",
                    "在**当前调用者自己**的知识库里新建一个目录。parentId 留空表示放在根下。",
                    objectSchema(Map.of(
                            "bookId", Map.of("type", "integer", "description", "知识库 id"),
                            "parentId", Map.of("type", "integer", "description", "父目录 id，留空为根"),
                            "title", Map.of("type", "string", "description", "目录名"))),
                    Map.of("type", "object", "properties", Map.of(
                            "noteId", Map.of("type", "integer", "description", "新目录 id"))),
                    false, false, false, ToolScope.WRITE, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long bookId = longOf(arguments, "bookId");
            String title = stringOf(arguments, "title");
            if (bookId == null || title == null) {
                return ToolResult.fail("需要 bookId 与 title");
            }
            try {
                long id = notes.createFolder(context.userId(), bookId, longOf(arguments, "parentId"), title);
                return ToolResult.ok("已新建目录 id=" + id + "（" + title + "）。", Map.of("noteId", id));
            } catch (RuntimeException failure) {
                return ToolResult.fail("新建失败：" + messageOf(failure));
            }
        }
    }

    /** 新建文档（写）。 */
    @Component
    public static class CreateDoc implements PaideiaTool {

        private final NoteApi notes;

        CreateDoc(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_create_doc", "note", "新建文档",
                    "在**当前调用者自己**的知识库里新建一篇文档。parentId 留空表示放在根下。",
                    objectSchema(Map.of(
                            "bookId", Map.of("type", "integer", "description", "知识库 id"),
                            "parentId", Map.of("type", "integer", "description", "父目录 id，留空为根"),
                            "title", Map.of("type", "string", "description", "标题，不超过 200 字"),
                            "body", Map.of("type", "string", "description", "正文，可多行；可以为空"))),
                    Map.of("type", "object", "properties", Map.of(
                            "noteId", Map.of("type", "integer", "description", "新文档 id"))),
                    false, false, false, ToolScope.WRITE, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long bookId = longOf(arguments, "bookId");
            String title = stringOf(arguments, "title");
            if (bookId == null || title == null) {
                return ToolResult.fail("需要 bookId 与 title");
            }
            try {
                long id = notes.createDoc(context.userId(), bookId, longOf(arguments, "parentId"),
                        title, stringOf(arguments, "body"));
                return ToolResult.ok("已新建文档 id=" + id + "（" + title + "）。", Map.of("noteId", id));
            } catch (RuntimeException failure) {
                return ToolResult.fail("新建失败：" + messageOf(failure));
            }
        }
    }

    /** 移动节点（写）。 */
    @Component
    public static class MoveNode implements PaideiaTool {

        private final NoteApi notes;

        MoveNode(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_move", "note", "移动笔记或目录",
                    "把**当前调用者自己**的一个节点移到新的父下（parentId 留空=移到根），"
                            + "并给定排序号。跨库或移到自己的子孙下会被拒绝。",
                    objectSchema(Map.of(
                            "noteId", Map.of("type", "integer", "description", "要移动的节点 id"),
                            "parentId", Map.of("type", "integer", "description", "新父 id，留空为根"),
                            "sortOrder", Map.of("type", "integer", "description", "排序号，默认 0"))),
                    null, false, false, false, ToolScope.WRITE, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long noteId = longOf(arguments, "noteId");
            if (noteId == null) {
                return ToolResult.fail("缺少 noteId");
            }
            Long sortRaw = longOf(arguments, "sortOrder");
            int sortOrder = sortRaw == null ? 0 : sortRaw.intValue();
            try {
                notes.move(context.userId(), noteId, longOf(arguments, "parentId"), sortOrder);
                return ToolResult.ok("已移动节点 id=" + noteId + "。");
            } catch (RuntimeException failure) {
                return ToolResult.fail("移动失败：" + messageOf(failure));
            }
        }
    }

    /** 删除节点（写）。 */
    @Component
    public static class DeleteNode implements PaideiaTool {

        private final NoteApi notes;

        DeleteNode(NoteApi notes) {
            this.notes = notes;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "note_delete_node", "note", "删除笔记或目录",
                    "删除**当前调用者自己**的一个节点；若是目录，会连带删除其整棵子树（软删）。"
                            + "这是一次破坏性写入，删除后本人也不再能读到。",
                    objectSchema(Map.of(
                            "noteId", Map.of("type", "integer", "description", "要删除的节点 id"))),
                    null, false, true, false, ToolScope.WRITE, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long noteId = longOf(arguments, "noteId");
            if (noteId == null) {
                return ToolResult.fail("缺少 noteId");
            }
            try {
                notes.deleteNode(context.userId(), noteId);
                return ToolResult.ok("已删除节点 id=" + noteId + "。");
            } catch (RuntimeException failure) {
                return ToolResult.fail("删除失败：" + messageOf(failure));
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
        return ToolArgs.stringOf(arguments, key);
    }

    static Long longOf(Map<String, Object> arguments, String key) {
        return ToolArgs.longOf(arguments, key);
    }

    static String messageOf(RuntimeException failure) {
        return ToolArgs.messageOf(failure);
    }
}
