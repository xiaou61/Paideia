package io.github.xiaou61.knowledge.internal.category;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把扁平的分类行装成层级树，以及取某分类的整棵子树。
 *
 * <p>纯函数、不碰数据库：分类树是知识库最容易出错的一块（顺序、环、孤儿节点），
 * 单独拎出来才能在没有数据库的情况下单测。
 */
public final class CategoryTree {

    private CategoryTree() {
    }

    /**
     * 树节点。只放导航需要的东西，不含条目数。
     *
     * <p>{@code lineKind} 非空表示这个分类是一条学习线（WORK-020）；管理端据此显示"这是条线"，
     * 学习者端的线清单另有 {@code /api/v1/learning/lines}，不靠这棵树。
     *
     * <p>{@code parentId} 与 {@code sortOrder} 是给**写路径**用的：改分类是一次整体替换
     * （{@code PUT /admin/categories/{id}} 会把没传的字段写成默认值），调用方必须能把原值原样带回去。
     * 以前树里没有这两项，`scripts/import-course.mjs` 改名父分类时只能写死 `parentId: null`、
     * `sortOrder: 0`，于是改名顺手把排序重置成 0、把手工挂到上层容器的分类挪回根。
     */
    public record Node(Long id, Long parentId, String name, String slug, int sortOrder, String lineKind,
                       List<Node> children) {
    }

    /**
     * 按 {@code rows} 的既有顺序装树（SQL 已按 {@code sort_order, id} 排好，这里保持原序）。
     *
     * <p><b>父节点不存在的行挂到根，而不是丢弃。</b>分类表里一条脏数据（父被直接删掉）如果
     * 导致整棵子树在界面上消失，管理员会以为内容丢了；挂到根至少看得见、能修。
     *
     * <p><b>成环不会死循环</b>：这里只做一次父指针索引，环上的节点既不可达根、也不会被
     * 遍历到，结果是"它们不出现"而不是"进程卡住"。写路径上另有拦截（换父时禁止把节点
     * 挂到自己的后代下），见分类写服务。
     */
    public static List<Node> assemble(List<Category> rows) {
        Map<Long, List<Category>> byParent = new LinkedHashMap<>();
        Set<Long> knownIds = new HashSet<>();
        for (Category row : rows) {
            knownIds.add(row.getId());
        }
        for (Category row : rows) {
            Long parent = row.getParentId() != null && knownIds.contains(row.getParentId())
                    ? row.getParentId()
                    : null;
            byParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(row);
        }
        return childrenOf(null, byParent, new HashSet<>());
    }

    private static List<Node> childrenOf(Long parentId,
                                         Map<Long, List<Category>> byParent,
                                         Set<Long> visiting) {
        List<Category> children = byParent.getOrDefault(parentId, List.of());
        List<Node> nodes = new ArrayList<>(children.size());
        for (Category child : children) {
            if (!visiting.add(child.getId())) {
                // 已经在当前路径上出现过，说明数据成环；跳过而不是继续下钻
                continue;
            }
            nodes.add(new Node(
                    child.getId(),
                    child.getParentId(),
                    child.getName(),
                    child.getSlug(),
                    child.getSortOrder(),
                    child.getLineKind(),
                    childrenOf(child.getId(), byParent, visiting)));
            visiting.remove(child.getId());
        }
        return List.copyOf(nodes);
    }

    /**
     * 某个分类连同它的全部后代的 id；分类不存在时返回空列表（调用方据此得到空结果）。
     *
     * <p>用于"按分类浏览时包含子分类"：点"后端基础"应该看到"Java 并发"下的条目。
     */
    public static List<Long> subtreeIds(List<Category> rows, Long rootId) {
        if (rootId == null) {
            return List.of();
        }
        Set<Long> knownIds = new HashSet<>();
        for (Category row : rows) {
            knownIds.add(row.getId());
        }
        if (!knownIds.contains(rootId)) {
            return List.of();
        }
        Map<Long, List<Long>> childrenByParent = new LinkedHashMap<>();
        for (Category row : rows) {
            if (row.getParentId() != null && knownIds.contains(row.getParentId())) {
                childrenByParent.computeIfAbsent(row.getParentId(), key -> new ArrayList<>())
                        .add(row.getId());
            }
        }
        List<Long> collected = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (!seen.add(current)) {
                // 成环时的兜底：保证每个 id 只进结果一次，也保证循环能终止
                continue;
            }
            collected.add(current);
            queue.addAll(childrenByParent.getOrDefault(current, List.of()));
        }
        return List.copyOf(collected);
    }

    /** 某个分类的全部祖先 id（不含自身），用于诊断与写路径校验。 */
    public static List<Long> ancestorIds(List<Category> rows, Long id) {
        Map<Long, Long> parentOf = new LinkedHashMap<>();
        for (Category row : rows) {
            if (row.getParentId() != null) {
                parentOf.put(row.getId(), row.getParentId());
            }
        }
        List<Long> ancestors = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Long current = parentOf.get(id);
        while (current != null && seen.add(current)) {
            ancestors.add(current);
            current = parentOf.get(current);
        }
        return List.copyOf(ancestors);
    }
}
