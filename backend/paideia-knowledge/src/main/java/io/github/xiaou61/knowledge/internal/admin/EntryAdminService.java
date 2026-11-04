package io.github.xiaou61.knowledge.internal.admin;

import io.github.xiaou61.knowledge.internal.cache.KnowledgeRedisCache;
import io.github.xiaou61.knowledge.internal.category.Category;
import io.github.xiaou61.knowledge.internal.category.CategoryMapper;
import io.github.xiaou61.knowledge.internal.entry.Entry;
import io.github.xiaou61.knowledge.internal.entry.EntryMapper;
import io.github.xiaou61.knowledge.internal.relation.RelationMapper;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 条目的管理端写入用例。
 *
 * <p>与学习者侧的区别只有一个：**这里看得见草稿**，因此所有查询都不过滤 {@code status}，
 * 过滤与否只在只读那侧（那里写在 SQL 里）。
 *
 * <p>{@code published_at} 的维护规则：由非发布变为发布时记当前时间（列表排序与
 * 上一篇／下一篇都按它排），退回草稿时清空——不清的话，重新发布不会刷新顺序，
 * 管理员会看到"刚发布的排在很久以前"。
 */
@Service
public class EntryAdminService {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";

    private final EntryMapper entryMapper;
    private final CategoryMapper categoryMapper;
    private final RelationMapper relationMapper;
    private final KnowledgeRedisCache cache;

    EntryAdminService(EntryMapper entryMapper, CategoryMapper categoryMapper, RelationMapper relationMapper,
                      KnowledgeRedisCache cache) {
        this.entryMapper = entryMapper;
        this.categoryMapper = categoryMapper;
        this.relationMapper = relationMapper;
        this.cache = cache;
    }

    public record AdminEntrySummary(
            Long id, String title, String status, Long categoryId, String categoryName, Instant updatedAt) {
    }

    /** 管理端详情：**不过滤状态**，草稿预览靠它。 */
    public record AdminEntryDetail(
            Long id,
            String title,
            String body,
            Long categoryId,
            String categoryName,
            String status,
            /** 学习线站序；null = 未声明（WORK-020）。 */
            Integer sortOrder,
            Instant publishedAt,
            /** 与本条目有关的全部边（含 id），供管理端删除关系。 */
            List<AdminRelation> relations) {
    }

    /** 管理端看到的关系：带 id，学习者侧那三个视角不带。 */
    public record AdminRelation(Long id, Long fromEntryId, Long toEntryId, String relationType) {
    }

    @Transactional
    public Long create(String title, String body, Long categoryId, String status, Integer sortOrder) {
        String normalized = normalizeStatus(status);
        requireCategory(categoryId);

        Entry entry = new Entry();
        entry.setTitle(requireTitle(title));
        entry.setBody(body == null ? "" : body);
        entry.setCategoryId(categoryId);
        entry.setSortOrder(normalizeSortOrder(sortOrder));
        entry.setStatus(normalized);
        entry.setPublishedAt(STATUS_PUBLISHED.equals(normalized) ? Instant.now() : null);
        entryMapper.insert(entry);
        cache.evictEntryBriefs();
        return entry.getId();
    }

    @Transactional
    public void update(Long id, String title, String body, Long categoryId, String status, Integer sortOrder) {
        Entry existing = entryMapper.findByIdIncludingDraft(id)
                .orElseThrow(() -> notFound("条目不存在"));
        String normalized = normalizeStatus(status);
        requireCategory(categoryId);

        boolean wasPublished = STATUS_PUBLISHED.equals(existing.getStatus());
        boolean willBePublished = STATUS_PUBLISHED.equals(normalized);

        existing.setTitle(requireTitle(title));
        existing.setBody(body == null ? "" : body);
        existing.setCategoryId(categoryId);
        // sortOrder 为 null 表示"保持原值"：管理端的编辑表单根本不带这个字段，
        // 无条件写回会把学习线里排好的站序清空（退回按 published_at/id 排序）。
        if (sortOrder != null) {
            existing.setSortOrder(normalizeSortOrder(sortOrder));
        }
        existing.setStatus(normalized);
        if (willBePublished && !wasPublished) {
            existing.setPublishedAt(Instant.now());
        } else if (!willBePublished) {
            existing.setPublishedAt(null);
        }

        entryMapper.update(existing);
        cache.evictEntryBriefs();
    }

    /** 删条目：关系由外键 {@code ON DELETE CASCADE} 一起清掉（悬空的关系没有意义）。 */
    @Transactional
    public void delete(Long id) {
        if (entryMapper.deleteById(id) == 0) {
            throw notFound("条目不存在");
        }
        cache.evictEntryBriefs();
    }

    public PageResult<AdminEntrySummary> page(String status, PageQuery page) {
        String normalized = status == null || status.isBlank() ? null : normalizeStatus(status);
        long total = entryMapper.countAdmin(normalized);
        if (total == 0L) {
            return PageResult.of(0L, page, List.of());
        }
        Map<Long, Category> categoriesById = categoryMapper.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        List<AdminEntrySummary> items = entryMapper
                .pageAdmin(normalized, page.limit(), page.offset())
                .stream()
                .map(row -> new AdminEntrySummary(
                        row.getId(),
                        row.getTitle(),
                        row.getStatus(),
                        row.getCategoryId(),
                        row.getCategoryId() == null
                                ? null
                                : categoriesById.containsKey(row.getCategoryId())
                                        ? categoriesById.get(row.getCategoryId()).getName()
                                        : null,
                        row.getUpdatedAt()))
                .toList();
        return PageResult.of(total, page, items);
    }

    public AdminEntryDetail detail(Long id) {
        Entry entry = entryMapper.findByIdIncludingDraft(id)
                .orElseThrow(() -> notFound("条目不存在"));
        List<AdminRelation> relations = relationMapper.findTouching(id).stream()
                .map(relation -> new AdminRelation(
                        relation.getId(),
                        relation.getFromEntryId(),
                        relation.getToEntryId(),
                        relation.getRelationType()))
                .toList();
        return new AdminEntryDetail(
                entry.getId(),
                entry.getTitle(),
                entry.getBody(),
                entry.getCategoryId(),
                nameOf(entry.getCategoryId()),
                entry.getStatus(),
                entry.getSortOrder(),
                entry.getPublishedAt(),
                relations);
    }

    private String nameOf(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryMapper.findById(categoryId);
        return category == null ? null : category.getName();
    }

    private void requireCategory(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        if (categoryMapper.findById(categoryId) == null) {
            throw invalid("分类不存在");
        }
    }

    private static String requireTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw invalid("标题不能为空");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 200) {
            throw invalid("标题不能超过 200 个字符");
        }
        return trimmed;
    }

    /**
     * 站序（WORK-020）：非负，或留空。
     *
     * <p>{@code 0} 是合法的——课程总览就是 0 号站（它排在最前，正文里带 learning-path）。
     * 负数没有意义，直接拒。
     *
     * <p>**不做唯一性校验**：同一条线里两个站拿到同一个号时，排序会退到
     * {@code (published_at, id)} 兜底，界面仍然是确定的顺序，而不是报错挡住发布。
     * 真正需要严格唯一的是导入脚本（它自己保证按 0..n 编号）。
     */
    private static Integer normalizeSortOrder(Integer sortOrder) {
        if (sortOrder == null) {
            return null;
        }
        if (sortOrder < 0) {
            throw invalid("站序不能为负");
        }
        return sortOrder;
    }

    private static String normalizeStatus(String status) {
        if (STATUS_DRAFT.equals(status) || STATUS_PUBLISHED.equals(status)) {
            return status;
        }
        throw invalid("状态只能是 draft 或 published");
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }
}
