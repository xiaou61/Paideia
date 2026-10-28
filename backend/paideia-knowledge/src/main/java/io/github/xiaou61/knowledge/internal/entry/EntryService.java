package io.github.xiaou61.knowledge.internal.entry;

import io.github.xiaou61.knowledge.internal.assessment.SelfAssessmentService;
import io.github.xiaou61.knowledge.internal.category.Category;
import io.github.xiaou61.knowledge.internal.category.CategoryMapper;
import io.github.xiaou61.knowledge.internal.category.CategoryTree;
import io.github.xiaou61.knowledge.internal.relation.RelationMapper;
import io.github.xiaou61.platform.SearchQuery;
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

/**
 * 学习者侧的条目读取用例。
 *
 * <p>三条纪律：
 *
 * <ul>
 *   <li><b>只看已发布</b>——过滤写在 SQL 里（见 {@link EntryMapper}），这里不做二次判断。</li>
 *   <li><b>分类浏览包含子分类</b>——点"后端基础"应该同时看到"Java 并发"下的条目。</li>
 *   <li><b>分类不存在时返回空页而不是报错</b>——不泄露"某个分类存不存在"。</li>
 * </ul>
 */
@Service
public class EntryService {

    private final EntryMapper entryMapper;
    private final CategoryMapper categoryMapper;
    private final RelationMapper relationMapper;
    private final SelfAssessmentService selfAssessmentService;

    EntryService(EntryMapper entryMapper,
                 CategoryMapper categoryMapper,
                 RelationMapper relationMapper,
                 SelfAssessmentService selfAssessmentService) {
        this.entryMapper = entryMapper;
        this.categoryMapper = categoryMapper;
        this.relationMapper = relationMapper;
        this.selfAssessmentService = selfAssessmentService;
    }

    /**
     * 列表项：**不含正文**（正文可能有几十 KB），但带一句**截断的摘要**——只给标题的列表
     * 卡片会显得空荡（用户反馈"页面过于单调"）。
     *
     * <p>`sortOrder` 是作者声明的站序（WORK-046）。列表响应本身按发布时间倒序，
     * 需要"按书里的顺序读"的地方（书籍章节页）靠这个字段在客户端重排。
     */
    public record EntrySummary(Long id, String title, Long categoryId, String categoryName,
                               String excerpt, Integer sortOrder, Instant updatedAt) {
    }

    /** 关系与相邻条目用的最小引用。 */
    public record EntryRef(Long id, String title) {
    }

    public record EntryDetail(
            Long id,
            String title,
            String body,
            Long categoryId,
            String categoryName,
            /**
             * 原始来源链接（WORK-025）。自动采集的「面经真题」非空，平台自产内容与存量条目为 null。
             */
            String sourceUrl,
            Instant publishedAt,
            /** 同分类内的上一篇（更早发布）。没有分类或没有相邻条目时为 null。 */
            EntryRef previous,
            EntryRef next,
            /** 本条目依赖的前置。 */
            List<EntryRef> prerequisites,
            /** 与本条目相关的内容（无向关系，两端的边都算）。 */
            List<EntryRef> related,
            /** 反向链接：把本条目当作**前置**的条目。 */
            List<EntryRef> dependents,
            /**
             * **当前请求者自己**的标记；没有标记或读不出身份时为 null。
             * 只可能是自己的——别人的标记永不返回。
             */
            String selfAssessment) {
    }

    public PageResult<EntrySummary> listPublished(Long categoryId, String rawQuery, PageQuery page) {
        String keyword = SearchQuery.normalize(rawQuery);

        List<Long> categoryIds = null;
        if (categoryId != null) {
            categoryIds = CategoryTree.subtreeIds(categoryMapper.findAll(), categoryId);
            if (categoryIds.isEmpty()) {
                // 分类不存在：空结果，不报错、也不提示"没这个分类"
                return PageResult.of(0L, page, List.of());
            }
        }

        boolean fulltext = SearchQuery.usesFulltext(keyword);
        String likePattern = keyword == null ? null : SearchQuery.likePattern(keyword);

        long total = entryMapper.countPublished(categoryIds, keyword, fulltext, likePattern);
        if (total == 0L) {
            return PageResult.of(0L, page, List.of());
        }
        List<Entry> rows = entryMapper.pagePublished(
                categoryIds, keyword, fulltext, likePattern, page.limit(), page.offset());

        Map<Long, Category> categoriesById = categoriesById();
        List<EntrySummary> items = rows.stream()
                .map(row -> new EntrySummary(
                        row.getId(),
                        row.getTitle(),
                        row.getCategoryId(),
                        nameOf(categoriesById, row.getCategoryId()),
                        row.getExcerpt(),
                        row.getSortOrder(),
                        row.getUpdatedAt()))
                .toList();
        return PageResult.of(total, page, items);
    }

    /**
     * 条目详情。
     *
     * @param userId 当前请求者，**只用于读他自己的标记**；为 null 时该字段返回空
     */
    public EntryDetail detail(long id, Long userId) {
        Entry entry = entryMapper.findPublishedById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "条目不存在"));

        Map<Long, Category> categoriesById = categoriesById();
        EntryRef previous = null;
        EntryRef next = null;
        // 上一篇／下一篇是"同分类内"的概念；条目未分类时没有这个顺序
        if (entry.getCategoryId() != null && entry.getPublishedAt() != null) {
            previous = entryMapper
                    .findPreviousInCategory(entry.getCategoryId(), entry.getPublishedAt(), entry.getId())
                    .map(EntryService::toRef)
                    .orElse(null);
            next = entryMapper
                    .findNextInCategory(entry.getCategoryId(), entry.getPublishedAt(), entry.getId())
                    .map(EntryService::toRef)
                    .orElse(null);
        }

        List<EntryRef> dependents = relationMapper.findDependents(id).stream()
                .map(EntryService::toRef)
                .toList();
        List<EntryRef> prerequisites = relationMapper.findPrerequisites(id).stream()
                .map(EntryService::toRef)
                .toList();
        List<EntryRef> related = relationMapper.findRelated(id).stream()
                .map(EntryService::toRef)
                .toList();

        return new EntryDetail(
                entry.getId(),
                entry.getTitle(),
                entry.getBody(),
                entry.getCategoryId(),
                nameOf(categoriesById, entry.getCategoryId()),
                entry.getSourceUrl(),
                entry.getPublishedAt(),
                previous,
                next,
                prerequisites,
                related,
                dependents,
                selfAssessmentService.levelOf(userId, id).orElse(null));
    }

    private Map<Long, Category> categoriesById() {
        return categoryMapper.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
    }

    private static String nameOf(Map<Long, Category> categoriesById, Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoriesById.get(categoryId);
        return category == null ? null : category.getName();
    }

    private static EntryRef toRef(Entry row) {
        return new EntryRef(row.getId(), row.getTitle());
    }
}
