package io.github.xiaou61.knowledge.internal;

import io.github.xiaou61.knowledge.EntryContent;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.knowledge.internal.assessment.SelfAssessmentService;
import io.github.xiaou61.knowledge.internal.cache.KnowledgeRedisCache;
import io.github.xiaou61.knowledge.internal.category.Category;
import io.github.xiaou61.knowledge.internal.category.CategoryService;
import io.github.xiaou61.knowledge.internal.concept.ConceptLinkMapper;
import io.github.xiaou61.knowledge.internal.concept.ConceptLinks;
import io.github.xiaou61.knowledge.internal.concept.ConceptMapper;
import io.github.xiaou61.knowledge.internal.entry.Entry;
import io.github.xiaou61.knowledge.internal.entry.EntryMapper;
import io.github.xiaou61.knowledge.internal.entry.EntryService;
import io.github.xiaou61.knowledge.internal.learning.LearningProgressService;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * 对外契约的实现。
 *
 * <p>放在 {@code internal} 而不是包根：实现属于内部细节，别的模块只该看到接口。
 *
 * <p>每个方法都**直接复用已有的内部服务／查询**，不另写一套 SQL：ngram 与 LIKE 的判定、
 * 「只给已发布」的过滤、自评的归属，那边都已经有测试盯着；在这里重写一遍等于开出第二个
 * 会漂移的地方。
 */
@Component
class KnowledgeApiImplementation implements KnowledgeApi {

    /** 助手问"有哪些内容"时一次给多少条：够它接着追问，又不至于把上下文塞满。 */
    private static final int DEFAULT_LIMIT = 10;

    private final EntryMapper entryMapper;
    private final ConceptMapper conceptMapper;
    private final ConceptLinkMapper conceptLinkMapper;
    private final EntryService entryService;
    private final CategoryService categoryService;
    private final SelfAssessmentService selfAssessments;
    private final LearningProgressService learningProgress;
    private final KnowledgeRedisCache cache;

    KnowledgeApiImplementation(EntryMapper entryMapper,
                               ConceptMapper conceptMapper,
                               ConceptLinkMapper conceptLinkMapper,
                               EntryService entryService,
                               CategoryService categoryService,
                               SelfAssessmentService selfAssessments,
                               LearningProgressService learningProgress,
                               KnowledgeRedisCache cache) {
        this.entryMapper = entryMapper;
        this.conceptMapper = conceptMapper;
        this.conceptLinkMapper = conceptLinkMapper;
        this.entryService = entryService;
        this.categoryService = categoryService;
        this.selfAssessments = selfAssessments;
        this.learningProgress = learningProgress;
        this.cache = cache;
    }

    @Override
    public Optional<EntryContent> findPublishedContent(long entryId) {
        Optional<Entry> entry = entryMapper.findPublishedById(entryId);
        // body 可能为 null（别的查询按需只填部分字段），这里取的是带正文的查询；
        // 仍按空串处理，免得把 null 传到提示词拼装里
        return entry.map(it -> new EntryContent(
                it.getId(),
                it.getTitle(),
                it.getBody() == null ? "" : it.getBody(),
                it.getUpdatedAt()));
    }

    @Override
    public List<EntryBrief> searchPublished(String keyword, int limit) {
        int sane = saneLimit(limit);
        String key = "knowledge:entry-briefs:v1:" + Integer.toHexString((keyword + ":" + sane).hashCode());
        List<EntryBrief> cached = cache.get(key, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        // 走既有的分页查询：它内部已经按 SearchQuery 判定全文还是 LIKE，且只返回已发布
        PageResult<EntryService.EntrySummary> page =
                entryService.listPublished(null, keyword, PageQuery.of(1, sane, null));
        List<EntryBrief> result = page.items().stream()
                .map(item -> new EntryBrief(item.id(), item.title()))
                .toList();
        cache.put(key, result, Duration.ofMinutes(5));
        return result;
    }

    @Override
    public List<EntryContent> publishedContents() {
        return entryMapper.findAllPublishedWithBody().stream()
                .map(it -> new EntryContent(it.getId(), it.getTitle(),
                        it.getBody() == null ? "" : it.getBody(), it.getUpdatedAt()))
                .toList();
    }

    @Override
    public List<CategoryNode> listCategories() {
        String key = "knowledge:categories:v1";
        List<CategoryNode> cached = cache.get(key, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        List<CategoryNode> result = categoryService.all().stream()
                .map(KnowledgeApiImplementation::toNode)
                .toList();
        cache.put(key, result, Duration.ofMinutes(10));
        return result;
    }

    @Override
    public CreateOutcome createCollected(CollectedEntry input) {
        // 先查后插：同一道题会在不同面经里反复出现，重复插入等于每天制造重复内容。
        // 并发下理论上仍可能双写（两个任务同时抓到同一题），但抓取是单实例定时任务，
        // 不为此加唯一约束——那会让"指纹为空"的存量条目行为变得微妙。
        Long existing = entryMapper.findIdByFingerprint(input.categoryId(), input.fingerprint());
        if (existing != null) {
            return new CreateOutcome(existing, false);
        }
        Entry entry = new Entry();
        entry.setTitle(input.title());
        entry.setBody(input.body() == null ? "" : input.body());
        entry.setCategoryId(input.categoryId());
        entry.setStatus(input.status());
        entry.setSourceUrl(input.sourceUrl());
        entry.setFingerprint(input.fingerprint());
        entry.setPublishedAt("published".equals(input.status()) ? Instant.now() : null);
        entryMapper.insert(entry);
        cache.evictEntryBriefs();
        return new CreateOutcome(entry.getId(), true);
    }

    @Override
    public List<MarkedEntry> findMarks(long userId, int limit) {
        // level 传 null = 懂／还不懂两类都要；条数截断在这一层（内部服务按 level 过滤，不按条数）
        return selfAssessments.listMine(userId, null).stream()
                .limit(saneLimit(limit))
                .map(item -> new MarkedEntry(item.entryId(), item.title(), item.level()))
                .toList();
    }

    @Override
    public String learningSummary(long userId, long entryId) {
        // 未登录/条目不存在都按"没有记录"处理：提示词注入不该是失败点
        if (userId <= 0) {
            return "";
        }
        try {
            LearningProgressService.View view = learningProgress.get(userId, entryId);
            StringBuilder summary = new StringBuilder();
            summary.append("状态：").append("passed".equals(view.status()) ? "已通过" : "学习中");
            summary.append("；最远步骤：").append(view.tourStep());
            if (!view.challenges().isEmpty()) {
                summary.append("；练习得分：").append(scores(view.challenges()));
            }
            if (!view.quizzes().isEmpty()) {
                summary.append("；小测得分：").append(scores(view.quizzes()));
            }
            if (view.reviewDueAt() != null) {
                summary.append("；复习到期：").append(view.reviewDueAt());
            }
            return summary.toString();
        } catch (RuntimeException failure) {
            // 条目被下架/删掉时 get 会抛 404：学情缺失不是错误
            return "";
        }
    }

    private static String scores(java.util.Map<String, io.github.xiaou61.knowledge.internal.learning.LearningProgressRules.BlockScore> scores) {
        int got = 0;
        int total = 0;
        for (var score : scores.values()) {
            got += score.score();
            total += score.total();
        }
        return got + "/" + total;
    }

    /**
     * 上限的兜底。
     *
     * <p>调用方来自模型（工具参数），所以这里的正数校验是**信任边界**：模型可能给 0 或负数，
     * 直接透给分页组件会得到奇怪结果。上限 50 也是防它一次要一千条把上下文塞爆。
     */
    private static int saneLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, 50);
    }

    // ── 训练营（WORK-029） ────────────────────────────────────────────

    @Override
    public List<ConceptRef> concepts() {
        return conceptMapper.findAll().stream()
                .map(concept -> new ConceptRef(concept.getId(), concept.getName()))
                .toList();
    }

    @Override
    public Map<Long, List<EntryCarrier>> conceptCarriers(Collection<Long> conceptIds) {
        // 空集合不查库：没有弱项时调用方会传空，没必要为它发一次 IN ()
        if (conceptIds == null || conceptIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = conceptIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        // SQL 里就带 status = 'published'：下架条目不该出现在计划里，
        // 靠调用方过滤等于给每个调用方留一个会漏的口子
        Map<Long, List<EntryCarrier>> carriers = new LinkedHashMap<>();
        for (EntryMapper.CarrierRow row : entryMapper.findPublishedCarriersByConceptIds(ids)) {
            carriers.computeIfAbsent(row.conceptId(), key -> new ArrayList<>())
                    .add(new EntryCarrier(row.entryId(), row.title(), row.bodyChars(), row.hasBlocks()));
        }
        return carriers;
    }

    @Override
    public List<DueEntry> dueReviews(long userId, int limit) {
        if (userId <= 0) {
            return List.of();
        }
        return entryMapper.findDueReviews(userId, saneLimit(limit)).stream()
                .map(row -> new DueEntry(row.conceptId(), row.entryId(), row.title()))
                .toList();
    }

    @Override
    public Map<Long, List<Long>> conceptPrerequisites() {
        Map<Long, List<Long>> prerequisites = new LinkedHashMap<>();
        for (ConceptLinks.Edge edge : conceptLinkMapper.findAllEdges()) {
            // 只认 prerequisite：related 是无向的，不构成先后
            if (!"prerequisite".equals(edge.relationType())) {
                continue;
            }
            prerequisites.computeIfAbsent(edge.toConceptId(), key -> new ArrayList<>())
                    .add(edge.fromConceptId());
        }
        return prerequisites;
    }

    private static CategoryNode toNode(Category category) {
        return new CategoryNode(category.getId(), category.getName(), category.getSlug(), category.getParentId());
    }
}
