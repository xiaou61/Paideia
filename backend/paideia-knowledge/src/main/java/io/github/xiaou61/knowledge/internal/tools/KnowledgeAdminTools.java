package io.github.xiaou61.knowledge.internal.tools;

import io.github.xiaou61.knowledge.internal.admin.CategoryAdminService;
import io.github.xiaou61.knowledge.internal.admin.ConceptAdminService;
import io.github.xiaou61.knowledge.internal.admin.EntryAdminService;
import io.github.xiaou61.knowledge.internal.admin.RelationAdminService;
import io.github.xiaou61.knowledge.internal.category.CategoryMapper;
import io.github.xiaou61.knowledge.internal.category.CategoryService;
import io.github.xiaou61.knowledge.internal.category.CategoryTree;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 知识库的管理工具组（WORK-038 TASK-002，15 个）。
 *
 * <p>**为什么集中在一个文件**：它们共享同一份 schema/参数辅助函数，而且"这一组能做什么"应该
 * 一眼看全。与 `KnowledgeTools` 同一组织方式。
 *
 * <p><b>全部声明 {@link ToolScope#ADMIN}</b>，包括写工具：角色过滤只作用于 ADMIN scope，
 * 声明成 WRITE 会让非管理员仅凭「admin 工具组 + write」就看见并调用管理写工具。
 *
 * <p><b>更新是"先读后写"</b>：工具没传的字段沿用库里的值。底层服务把 null 当作"清空"
 * （例如 `EntryAdminService.update` 的 body），模型少写一个字段就静默抹掉正文——这不是
 * 模型该承担的精确性。
 */
public final class KnowledgeAdminTools {

    private static final String GROUP = "admin_content";
    private static final int MAX_PAGE_SIZE = 100;

    private KnowledgeAdminTools() {
    }

    // ------------------------------------------------------------------ 分类

    /** 管理端全部分类（含学习线下的分类）。 */
    @Component
    public static class ListCategories implements PaideiaTool {

        private final CategoryService categories;

        ListCategories(CategoryService categories) {
            this.categories = categories;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_list_categories", GROUP, "列出全部知识分类",
                    "列出**全部**知识分类（管理视角，含学习线下的分类），层级用缩进表示。"
                            + "返回每条的 id、名称、slug、学习线类型；建条目或改分类前用它取 id。",
                    schema(Map.of()), null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                List<String> lines = new java.util.ArrayList<>();
                flatten(categories.fullTree(), 0, lines);
                return ToolResult.ok(lines.isEmpty() ? "还没有任何分类。" : join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取分类失败：" + messageOf(failure));
            }
        }

        private static void flatten(List<CategoryTree.Node> nodes, int depth, List<String> lines) {
            for (CategoryTree.Node node : nodes) {
                lines.add("  ".repeat(depth) + "id=" + node.id() + " 名称=" + node.name()
                        + " slug=" + node.slug()
                        + (node.lineKind() == null || node.lineKind().isBlank()
                                ? "" : " 学习线=" + node.lineKind()));
                flatten(node.children(), depth + 1, lines);
            }
        }
    }

    /** 建或改分类。 */
    @Component
    public static class SaveCategory implements PaideiaTool {

        private final CategoryAdminService admin;
        private final CategoryMapper mapper;

        SaveCategory(CategoryAdminService admin, CategoryMapper mapper) {
            this.admin = admin;
            this.mapper = mapper;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_save_category", GROUP, "新建或修改分类",
                    "不传 id 为新建，传 id 为修改。name 与 slug 必填。修改时未传的 parentId / "
                            + "sortOrder / lineKind 沿用原值；要移到根分类请显式传 parentId=0。",
                    schema(Map.of(
                            "id", num("要修改的分类 id；新建时不传"),
                            "parentId", num("上级分类 id；0 或空表示根分类"),
                            "name", str("分类名称"),
                            "slug", str("分类标识，小写字母数字与连字符"),
                            "sortOrder", num("排序值，越小越靠前"),
                            "lineKind", str("学习线类型：course / reading；普通分类不传")),
                            "name", "slug"),
                    null, false, false, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "id");
                String name = stringOf(arguments, "name");
                String slug = stringOf(arguments, "slug");
                if (id == null) {
                    Long created = admin.create(zeroAsNull(arguments, "parentId"), name, slug,
                            intOf(arguments, "sortOrder"), stringOf(arguments, "lineKind")).getId();
                    return ToolResult.ok("已新建分类 id=" + created);
                }
                var existing = mapper.findById(id);
                if (existing == null) {
                    return ToolResult.fail("分类不存在：id=" + id);
                }
                Long parentId = has(arguments, "parentId")
                        ? zeroAsNull(arguments, "parentId")
                        : existing.getParentId();
                Integer sortOrder = has(arguments, "sortOrder")
                        ? intOf(arguments, "sortOrder")
                        : existing.getSortOrder();
                String lineKind = has(arguments, "lineKind")
                        ? stringOf(arguments, "lineKind")
                        : existing.getLineKind();
                admin.update(id, parentId, name, slug, sortOrder, lineKind);
                return ToolResult.ok("已更新分类 id=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("保存分类失败：" + messageOf(failure));
            }
        }
    }

    /** 删分类。 */
    @Component
    public static class DeleteCategory implements PaideiaTool {

        private final CategoryAdminService admin;

        DeleteCategory(CategoryAdminService admin) {
            this.admin = admin;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_delete_category", GROUP, "删除分类",
                    "**不可撤销**。分类下还有子分类或条目时会被拒绝——先移走它们。",
                    schema(Map.of("id", num("分类 id")), "id"),
                    null, false, true, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "id");
                if (id == null) {
                    return ToolResult.fail("缺少分类 id");
                }
                admin.delete(id);
                return ToolResult.ok("已删除分类 id=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("删除分类失败：" + messageOf(failure));
            }
        }
    }

    // ------------------------------------------------------------------ 条目

    /** 条目列表（含草稿）。 */
    @Component
    public static class ListEntries implements PaideiaTool {

        private final EntryAdminService entries;

        ListEntries(EntryAdminService entries) {
            this.entries = entries;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_list_entries", GROUP, "列出条目（含草稿）",
                    "列出条目，**包括草稿**。status 可传 draft 或 published 只看一种；不传看全部。"
                            + "返回 id、标题、状态、分类与更新时间。",
                    schema(Map.of(
                            "status", str("draft / published；不传看全部"),
                            "page", num("页码，从 1 开始"),
                            "size", num("每页条数，1-100，默认 20"))),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                PageResult<EntryAdminService.AdminEntrySummary> page = entries.page(
                        stringOf(arguments, "status"), pageOf(arguments));
                if (page.items().isEmpty()) {
                    return ToolResult.ok("第 " + page.page() + " 页没有条目（共 " + page.total() + " 条）。");
                }
                List<String> lines = page.items().stream()
                        .map(item -> "id=" + item.id() + " [" + item.status() + "] " + item.title()
                                + " 分类=" + (item.categoryName() == null ? "无" : item.categoryName())
                                + " 更新=" + item.updatedAt())
                        .toList();
                return ToolResult.ok("共 " + page.total() + " 条，第 " + page.page() + " 页：\n" + join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取条目失败：" + messageOf(failure));
            }
        }
    }

    /** 条目详情（含草稿与关系）。 */
    @Component
    public static class GetEntry implements PaideiaTool {

        private final EntryAdminService entries;

        GetEntry(EntryAdminService entries) {
            this.entries = entries;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_get_entry", GROUP, "读取条目详情（含草稿）",
                    "按 id 读条目全文，**草稿也能读**。返回状态、分类、站序、发布时间、正文，"
                            + "以及与该条目有关的全部关系（含关系 id，删关系要用）。",
                    schema(Map.of("id", num("条目 id")), "id"),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "id");
                if (id == null) {
                    return ToolResult.fail("缺少条目 id");
                }
                EntryAdminService.AdminEntryDetail detail = entries.detail(id);
                StringBuilder text = new StringBuilder()
                        .append("id=").append(detail.id()).append('\n')
                        .append("标题：").append(detail.title()).append('\n')
                        .append("状态：").append(detail.status()).append('\n')
                        .append("分类：").append(detail.categoryName() == null ? "无" : detail.categoryName()).append('\n')
                        .append("站序：").append(detail.sortOrder() == null ? "未声明" : detail.sortOrder()).append('\n')
                        .append("发布时间：").append(detail.publishedAt() == null ? "未发布" : detail.publishedAt()).append('\n');
                if (detail.relations().isEmpty()) {
                    text.append("关系：无\n");
                } else {
                    text.append("关系：\n");
                    for (EntryAdminService.AdminRelation relation : detail.relations()) {
                        text.append("  relationId=").append(relation.id())
                                .append(" ").append(relation.fromEntryId())
                                .append(" -[").append(relation.relationType()).append("]-> ")
                                .append(relation.toEntryId()).append('\n');
                    }
                }
                text.append("\n正文：\n").append(detail.body() == null ? "" : detail.body());
                return ToolResult.ok(text.toString());
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取条目失败：" + messageOf(failure));
            }
        }
    }

    /** 建或改条目。 */
    @Component
    public static class SaveEntry implements PaideiaTool {

        private final EntryAdminService entries;

        SaveEntry(EntryAdminService entries) {
            this.entries = entries;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_save_entry", GROUP, "新建或修改条目",
                    "不传 id 为新建，传 id 为修改。title 与 status 必填（发布就是把 status 设为 published）。"
                            + "修改时未传的 body / categoryId / sortOrder **沿用原值**；要清空正文请显式传空串。",
                    schema(Map.of(
                            "id", num("要修改的条目 id；新建时不传"),
                            "title", str("标题，最长 200 字"),
                            "status", str("draft 或 published"),
                            "body", str("正文（markdown）"),
                            "categoryId", num("分类 id；不传表示不改"),
                            "sortOrder", num("学习线站序；不传表示不改")),
                            "title", "status"),
                    null, false, false, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "id");
                String title = stringOf(arguments, "title");
                String status = stringOf(arguments, "status");
                if (title == null || status == null) {
                    return ToolResult.fail("title 与 status 必填");
                }
                if (id == null) {
                    Long created = entries.create(title, stringOf(arguments, "body"),
                            longOf(arguments, "categoryId"), status, intOf(arguments, "sortOrder"));
                    return ToolResult.ok("已新建条目 id=" + created);
                }
                EntryAdminService.AdminEntryDetail existing = entries.detail(id);
                String body = has(arguments, "body")
                        ? orEmpty(stringOf(arguments, "body"))
                        : existing.body();
                Long categoryId = has(arguments, "categoryId")
                        ? longOf(arguments, "categoryId")
                        : existing.categoryId();
                Integer sortOrder = has(arguments, "sortOrder")
                        ? intOf(arguments, "sortOrder")
                        : existing.sortOrder();
                entries.update(id, title, body, categoryId, status, sortOrder);
                return ToolResult.ok("已更新条目 id=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("保存条目失败：" + messageOf(failure));
            }
        }
    }

    /** 删条目。 */
    @Component
    public static class DeleteEntry implements PaideiaTool {

        private final EntryAdminService entries;

        DeleteEntry(EntryAdminService entries) {
            this.entries = entries;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_delete_entry", GROUP, "删除条目",
                    "**不可撤销**，相关关系一并删除。删之前建议先 admin_content_get_entry 确认内容。",
                    schema(Map.of("id", num("条目 id")), "id"),
                    null, false, true, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "id");
                if (id == null) {
                    return ToolResult.fail("缺少条目 id");
                }
                entries.delete(id);
                return ToolResult.ok("已删除条目 id=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("删除条目失败：" + messageOf(failure));
            }
        }
    }

    // ------------------------------------------------------------------ 概念

    /** 建或改概念。 */
    @Component
    public static class SaveConcept implements PaideiaTool {

        private final ConceptAdminService concepts;

        SaveConcept(ConceptAdminService concepts) {
            this.concepts = concepts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_save_concept", GROUP, "新建或修改概念",
                    "不传 id 为新建，传 id 为修改。name 必填且全局唯一。修改时未传的 categoryId / "
                            + "summary 沿用原值。概念与条目的挂载用 admin_content_link_concept_entry。",
                    schema(Map.of(
                            "id", num("要修改的概念 id；新建时不传"),
                            "name", str("概念名称，最长 64 字"),
                            "categoryId", num("所属分类 id；不传表示不改"),
                            "summary", str("一句话说明，最长 500 字")),
                            "name"),
                    null, false, false, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "id");
                String name = stringOf(arguments, "name");
                if (name == null) {
                    return ToolResult.fail("name 必填");
                }
                if (id == null) {
                    Long created = concepts.create(longOf(arguments, "categoryId"), name,
                            stringOf(arguments, "summary"));
                    return ToolResult.ok("已新建概念 id=" + created);
                }
                ConceptAdminService.ConceptSummary existing = concepts.list().stream()
                        .filter(item -> item.id() == id)
                        .findFirst()
                        .orElse(null);
                Long categoryId = has(arguments, "categoryId")
                        ? longOf(arguments, "categoryId")
                        : (existing == null ? null : existing.categoryId());
                String summary = has(arguments, "summary")
                        ? stringOf(arguments, "summary")
                        : (existing == null ? null : existing.summary());
                concepts.update(id, categoryId, name, summary);
                return ToolResult.ok("已更新概念 id=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("保存概念失败：" + messageOf(failure));
            }
        }
    }

    /** 删概念。 */
    @Component
    public static class DeleteConcept implements PaideiaTool {

        private final ConceptAdminService concepts;

        DeleteConcept(ConceptAdminService concepts) {
            this.concepts = concepts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_delete_concept", GROUP, "删除概念",
                    "**不可撤销**。概念与条目、概念之间的关联由外键级联删除。",
                    schema(Map.of("id", num("概念 id")), "id"),
                    null, false, true, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "id");
                if (id == null) {
                    return ToolResult.fail("缺少概念 id");
                }
                concepts.delete(id);
                return ToolResult.ok("已删除概念 id=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("删除概念失败：" + messageOf(failure));
            }
        }
    }

    /** 把条目挂到概念上。 */
    @Component
    public static class LinkConceptEntry implements PaideiaTool {

        private final ConceptAdminService concepts;

        LinkConceptEntry(ConceptAdminService concepts) {
            this.concepts = concepts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_link_concept_entry", GROUP, "把条目挂到概念上",
                    "建立「概念 → 条目」的挂载关系。重复挂载也算成功。",
                    schema(Map.of(
                            "conceptId", num("概念 id"),
                            "entryId", num("条目 id")),
                            "conceptId", "entryId"),
                    null, false, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long conceptId = longOf(arguments, "conceptId");
                Long entryId = longOf(arguments, "entryId");
                if (conceptId == null || entryId == null) {
                    return ToolResult.fail("conceptId 与 entryId 必填");
                }
                concepts.linkEntry(conceptId, entryId);
                return ToolResult.ok("已挂载：概念 " + conceptId + " ← 条目 " + entryId);
            } catch (RuntimeException failure) {
                return ToolResult.fail("挂载失败：" + messageOf(failure));
            }
        }
    }

    /** 把条目从概念上摘掉。 */
    @Component
    public static class UnlinkConceptEntry implements PaideiaTool {

        private final ConceptAdminService concepts;

        UnlinkConceptEntry(ConceptAdminService concepts) {
            this.concepts = concepts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_unlink_concept_entry", GROUP, "把条目从概念上摘掉",
                    "删除概念与条目的挂载关系（**不删条目本身**）。",
                    schema(Map.of(
                            "conceptId", num("概念 id"),
                            "entryId", num("条目 id")),
                            "conceptId", "entryId"),
                    null, false, true, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long conceptId = longOf(arguments, "conceptId");
                Long entryId = longOf(arguments, "entryId");
                if (conceptId == null || entryId == null) {
                    return ToolResult.fail("conceptId 与 entryId 必填");
                }
                concepts.unlinkEntry(conceptId, entryId);
                return ToolResult.ok("已摘除：概念 " + conceptId + " ← 条目 " + entryId);
            } catch (RuntimeException failure) {
                return ToolResult.fail("摘除失败：" + messageOf(failure));
            }
        }
    }

    // ------------------------------------------------------------------ 关系

    /** 建两条条目之间的关系。 */
    @Component
    public static class LinkEntries implements PaideiaTool {

        private final RelationAdminService relations;

        LinkEntries(RelationAdminService relations) {
            this.relations = relations;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_link_entries", GROUP, "建立条目之间的关系",
                    "建立 from → to 的关系。relationType 用短标识（如 related / prerequisite）。"
                            + "返回关系 id，删除时要用。",
                    schema(Map.of(
                            "fromEntryId", num("起点条目 id"),
                            "toEntryId", num("终点条目 id"),
                            "relationType", str("关系类型，最长 32 字")),
                            "fromEntryId", "toEntryId", "relationType"),
                    null, false, false, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long from = longOf(arguments, "fromEntryId");
                Long to = longOf(arguments, "toEntryId");
                String type = stringOf(arguments, "relationType");
                if (from == null || to == null || type == null) {
                    return ToolResult.fail("fromEntryId、toEntryId、relationType 必填");
                }
                Long id = relations.create(from, to, type);
                return ToolResult.ok("已建立关系 relationId=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("建立关系失败：" + messageOf(failure));
            }
        }
    }

    /** 删条目关系。 */
    @Component
    public static class UnlinkEntries implements PaideiaTool {

        private final RelationAdminService relations;

        UnlinkEntries(RelationAdminService relations) {
            this.relations = relations;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_unlink_entries", GROUP, "删除条目关系",
                    "按关系 id 删除（id 来自 admin_content_get_entry 的关系列表）。**不可撤销**。",
                    schema(Map.of("relationId", num("关系 id")), "relationId"),
                    null, false, true, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "relationId");
                if (id == null) {
                    return ToolResult.fail("缺少 relationId");
                }
                relations.delete(id);
                return ToolResult.ok("已删除关系 id=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("删除关系失败：" + messageOf(failure));
            }
        }
    }

    /** 建两条概念之间的关系（概念边）。 */
    @Component
    public static class LinkConcepts implements PaideiaTool {

        private final ConceptAdminService concepts;

        LinkConcepts(ConceptAdminService concepts) {
            this.concepts = concepts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_link_concepts", GROUP, "建立概念之间的关系",
                    "建立从 fromConceptId 到 toConceptId 的概念边。返回边 id，删除时要用。",
                    schema(Map.of(
                            "fromConceptId", num("起点概念 id"),
                            "toConceptId", num("终点概念 id"),
                            "relationType", str("关系类型，最长 32 字")),
                            "fromConceptId", "toConceptId", "relationType"),
                    null, false, false, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long from = longOf(arguments, "fromConceptId");
                Long to = longOf(arguments, "toConceptId");
                String type = stringOf(arguments, "relationType");
                if (from == null || to == null || type == null) {
                    return ToolResult.fail("fromConceptId、toConceptId、relationType 必填");
                }
                Long id = concepts.createEdge(from, to, type);
                return ToolResult.ok("已建立概念边 edgeId=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("建立概念边失败：" + messageOf(failure));
            }
        }
    }

    /** 删概念边。 */
    @Component
    public static class UnlinkConcepts implements PaideiaTool {

        private final ConceptAdminService concepts;

        UnlinkConcepts(ConceptAdminService concepts) {
            this.concepts = concepts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_content_unlink_concepts", GROUP, "删除概念边",
                    "按边 id 删除概念之间的关系。**不可撤销**。",
                    schema(Map.of("edgeId", num("概念边 id")), "edgeId"),
                    null, false, true, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "edgeId");
                if (id == null) {
                    return ToolResult.fail("缺少 edgeId");
                }
                concepts.deleteEdge(id);
                return ToolResult.ok("已删除概念边 id=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("删除概念边失败：" + messageOf(failure));
            }
        }
    }

    // --------------------------------------------------------------- helpers

    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> num(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static boolean has(Map<String, Object> arguments, String key) {
        return arguments != null && arguments.containsKey(key);
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

    static Integer intOf(Map<String, Object> arguments, String key) {
        Long value = longOf(arguments, key);
        return value == null ? null : value.intValue();
    }

    /** `0` 对分类树表示根：接口层用 null 表达，工具层接受 0 更符合直觉。 */
    static Long zeroAsNull(Map<String, Object> arguments, String key) {
        Long value = longOf(arguments, key);
        return value == null || value == 0L ? null : value;
    }

    static PageQuery pageOf(Map<String, Object> arguments) {
        Integer page = intOf(arguments, "page");
        Integer size = intOf(arguments, "size");
        int effectiveSize = size == null ? 20 : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageQuery.of(page == null ? 1 : page, effectiveSize, null);
    }

    static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
