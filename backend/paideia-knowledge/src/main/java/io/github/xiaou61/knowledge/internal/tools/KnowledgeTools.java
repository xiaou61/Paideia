package io.github.xiaou61.knowledge.internal.tools;

import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 知识库的**只读**工具组（WORK-019 TASK-002）。
 *
 * <p>四个工具放在一个文件里：它们共享同一份 schema 辅助函数与同一个 {@link KnowledgeApi} 契约，
 * 拆四个文件只会让"这一组工具长什么样"更难一眼看全。
 *
 * <p><b>归属只来自 {@link ToolContext}</b>：`knowledge_my_marks` 永远读调用者自己的标记，
 * 参数里没有"谁的"这个字段。检索与分类是公开内容（只返回已发布），不需要归属。
 */
public final class KnowledgeTools {

    private KnowledgeTools() {
    }

    /** 按关键词检索已发布条目。 */
    @Component
    public static class Search implements PaideiaTool {

        private final KnowledgeApi knowledge;

        Search(KnowledgeApi knowledge) {
            this.knowledge = knowledge;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "knowledge_search", "knowledge", "检索知识条目",
                    "按关键词检索平台上**已发布**的知识条目，返回条目 id 与标题。"
                            + "用户问「有没有讲过 X」「哪些内容涉及 X」时用它。关键词留空则返回最近的内容。"
                            + "拿到 id 后如果需要正文，用 knowledge_get_entry。",
                    objectSchema(Map.of(
                            "keyword", Map.of("type", "string", "description", "检索关键词，可为空"),
                            "limit", Map.of("type", "integer", "description", "最多返回几条，默认 5，上限 20"))),
                    null, true, false, true, ToolScope.READ, true);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                String keyword = stringOf(arguments, "keyword");
                List<KnowledgeApi.EntryBrief> hits = knowledge.searchPublished(keyword, limitOf(arguments));
                if (hits.isEmpty()) {
                    // 明确说"没找到"而不是空串：空字符串会让模型以为工具坏了。
                    // 但只说"没找到"就停了，模型下一步只能换个词瞎试——所以给两条具体出路
                    return ToolResult.ok(keyword == null
                            ? "平台上还没有已发布的内容。"
                            : "没有检索到与「" + keyword + "」直接相关的内容。可以换个更短的关键词再试，"
                                    + "或用 knowledge_list_categories 看看平台有哪些分类。");
                }
                return ToolResult.ok(join(hits.stream()
                        .map(hit -> "id=" + hit.id() + " 标题=" + hit.title())
                        .toList()));
            } catch (RuntimeException failure) {
                return ToolResult.fail("检索失败：" + messageOf(failure));
            }
        }
    }

    /** 按 id 读正文：外部助手的模型看不到我们的界面，只能靠它拿内容。 */
    @Component
    public static class GetEntry implements PaideiaTool {

        private final KnowledgeApi knowledge;

        GetEntry(KnowledgeApi knowledge) {
            this.knowledge = knowledge;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "knowledge_get_entry", "knowledge", "读取知识条目正文",
                    "按 id 读取一篇**已发布**知识条目的标题与正文。id 一般来自 knowledge_search 的结果。"
                            + "条目不存在或尚未发布时返回「没有找到」——两种情况刻意不区分。",
                    objectSchema(Map.of(
                            "entryId", Map.of("type", "integer", "description", "条目 id"))),
                    null, true, false, true, ToolScope.READ, true);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long id = longOf(arguments, "entryId");
            if (id == null) {
                return ToolResult.fail("缺少 entryId");
            }
            return knowledge.findPublishedContent(id)
                    .map(entry -> ToolResult.ok("标题：" + entry.title() + "\n\n" + entry.body()))
                    .orElseGet(() -> ToolResult.fail("没有找到 id=" + id + " 的已发布条目"));
        }
    }

    /** 分类树（扁平 + parentId）。 */
    @Component
    public static class ListCategories implements PaideiaTool {

        private final KnowledgeApi knowledge;

        ListCategories(KnowledgeApi knowledge) {
            this.knowledge = knowledge;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "knowledge_list_categories", "knowledge", "列出知识分类",
                    "列出平台上全部知识分类（含上级分类 id，层级由 parentId 表示）。"
                            + "用户问「都有哪些分类」「内容是怎么组织的」时用它。",
                    objectSchema(Map.of()), null, true, false, true, ToolScope.READ, true);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            List<KnowledgeApi.CategoryNode> nodes = knowledge.listCategories();
            if (nodes.isEmpty()) {
                return ToolResult.ok("平台还没有建立分类。");
            }
            return ToolResult.ok(join(nodes.stream()
                    .map(node -> "id=" + node.id() + " 名称=" + node.name()
                            + " 上级=" + (node.parentId() == null ? "无（顶级）" : node.parentId()))
                    .toList()));
        }
    }

    /** 调用者自己的自评标记。 */
    @Component
    public static class MyMarks implements PaideiaTool {

        private final KnowledgeApi knowledge;

        MyMarks(KnowledgeApi knowledge) {
            this.knowledge = knowledge;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "knowledge_my_marks", "knowledge", "列出我标记过的条目",
                    "列出**当前调用者自己**标记过「懂了／还不懂」的条目。"
                            + "用户问「我哪些还没搞明白」「帮我复习标记过的内容」时用它。"
                            + "只能读当前调用者自己的标记，无法查询他人。",
                    objectSchema(Map.of(
                            "limit", Map.of("type", "integer", "description", "最多返回几条，默认 5，上限 20"))),
                    null, true, false, true, ToolScope.READ, true);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            // 归属是 context 给的，参数里根本没有 userId 这个口子
            List<KnowledgeApi.MarkedEntry> marks = knowledge.findMarks(context.userId(), limitOf(arguments));
            if (marks.isEmpty()) {
                return ToolResult.ok("当前用户还没有标记过任何条目。");
            }
            return ToolResult.ok(join(marks.stream()
                    .map(mark -> "id=" + mark.entryId() + " 标题=" + mark.title() + " 标记=" + mark.level())
                    .toList()));
        }
    }

    // --------------------------------------------------------------- helpers

    static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.of());
    }

    /**
     * 模型给的条数**不可信**：可能缺、可能是字符串、可能是 0 或 1000。
     * 统一夹到 [1, 20]，0/负数退回默认——一次要一千条会把上下文塞爆。
     */
    static int limitOf(Map<String, Object> arguments) {
        Long raw = longOf(arguments, "limit");
        if (raw == null || raw <= 0) {
            return 5;
        }
        return (int) Math.min(raw, 20);
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

    static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
