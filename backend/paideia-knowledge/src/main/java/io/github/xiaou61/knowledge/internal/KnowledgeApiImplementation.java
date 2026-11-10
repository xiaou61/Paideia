package io.github.xiaou61.knowledge.internal;

import io.github.xiaou61.knowledge.EntryContent;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.knowledge.internal.assessment.SelfAssessmentService;
import io.github.xiaou61.knowledge.internal.cache.KnowledgeRedisCache;
import io.github.xiaou61.knowledge.internal.category.Category;
import io.github.xiaou61.knowledge.internal.category.CategoryMapper;
import io.github.xiaou61.knowledge.internal.category.CategoryService;
import io.github.xiaou61.knowledge.internal.concept.ConceptLinkMapper;
import io.github.xiaou61.knowledge.internal.concept.ConceptLinks;
import io.github.xiaou61.knowledge.internal.concept.ConceptMapper;
import io.github.xiaou61.knowledge.internal.entry.Entry;
import io.github.xiaou61.knowledge.internal.entry.EntryMapper;
import io.github.xiaou61.knowledge.internal.entry.EntryService;
import io.github.xiaou61.knowledge.internal.file.FileStorage;
import io.github.xiaou61.knowledge.internal.file.StoredFile;
import io.github.xiaou61.knowledge.internal.file.StoredFileMapper;
import io.github.xiaou61.knowledge.internal.learning.LearningProgressService;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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
    private final CategoryMapper categoryMapper;
    private final StoredFileMapper storedFiles;
    private final FileStorage fileStorage;
    private final SelfAssessmentService selfAssessments;
    private final LearningProgressService learningProgress;
    private final KnowledgeRedisCache cache;

    KnowledgeApiImplementation(EntryMapper entryMapper,
                               ConceptMapper conceptMapper,
                               ConceptLinkMapper conceptLinkMapper,
                               EntryService entryService,
                               CategoryService categoryService,
                               CategoryMapper categoryMapper,
                               StoredFileMapper storedFiles,
                               FileStorage fileStorage,
                               SelfAssessmentService selfAssessments,
                               LearningProgressService learningProgress,
                               KnowledgeRedisCache cache) {
        this.entryMapper = entryMapper;
        this.conceptMapper = conceptMapper;
        this.conceptLinkMapper = conceptLinkMapper;
        this.entryService = entryService;
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
        this.storedFiles = storedFiles;
        this.fileStorage = fileStorage;
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
        // v2：检索语义从自然语言模式改成 BOOLEAN 短语模式（2026-11-10），旧缓存不再复用
        String key = "knowledge:entry-briefs:v2:" + Integer.toHexString((keyword + ":" + sane).hashCode());
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
        // 先查后插：指纹是「来源站点 + 上游内容 id」，一篇帖子一个；**判重还得看题目**
        // （2026-11-10 一帖多题）：同一篇面经抽出的几道题共享指纹，只有标题也一样才是重复。
        // **跨分类查**（2026-11-06）：同一篇帖子会出现在多个牛客合集里，多方向抓取时
        // 限定分类判重会让它在库里存两份。并发下理论上仍可能双写（两个任务同时抓到同一帖），
        // 但抓取是单实例定时任务，不为此加唯一约束——那会让"指纹为空"的存量条目行为变得微妙。
        String title = normalizeTitle(input.title());
        for (Entry existing : entryMapper.findAllByFingerprint(input.fingerprint())) {
            if (title.equals(normalizeTitle(existing.getTitle()))) {
                return new CreateOutcome(existing.getId(), false);
            }
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

    /**
     * 标题归一化（判重用）：去掉空白与中英文标点、统一小写。
     *
     * <p>模型两轮抽同一道题，标点与空格常有出入（「HashMap 为什么线程不安全？」/
     * 「HashMap为什么线程不安全」）。不归一化的话，每轮抓取都会因为一个问号多出一条。
     */
    private static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.replaceAll("[\\s\\p{Punct}，。；：！？、“”‘’「」『』（）【】《》〈〉·…—～]+", "")
                .toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean hasCollected(String fingerprint) {
        // 复用同一个查询：一帖最多 5 道题，候选行就那么几条，没必要为它单开一个 COUNT。
        return !entryMapper.findAllByFingerprint(fingerprint).isEmpty();
    }

    // ── 文档入库（WORK-076） ───────────────────────────────────────────

    @Override
    public Optional<AttachmentContent> findAttachment(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        Optional<StoredFile> meta = storedFiles.findById(fileId);
        if (meta.isEmpty()) {
            return Optional.empty();
        }
        Optional<byte[]> bytes = fileStorage.read(fileId);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        StoredFile file = meta.get();
        return Optional.of(new AttachmentContent(
                file.getId(), file.getOriginalName(), file.getContentType(), file.getSizeBytes(), bytes.get()));
    }

    @Override
    @Transactional
    public Long ensureCategoryPath(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        Long parent = null;
        boolean created = false;
        for (String raw : segments) {
            String name = sanitizeSegment(raw);
            if (name == null) {
                continue;
            }
            Category existing = categoryMapper.findByParentAndName(parent, name);
            if (existing != null) {
                parent = existing.getId();
                continue;
            }
            Category category = new Category();
            category.setParentId(parent);
            category.setName(name);
            category.setSlug(uniqueImportSlug(parent, name));
            category.setSortOrder(0);
            categoryMapper.insert(category);
            parent = category.getId();
            created = true;
        }
        if (created) {
            cache.evictCategories();
        }
        return parent;
    }

    /** 只保留路径末段并清洗控制字符与穿越片段；返回 null 表示该段应跳过。 */
    private static String sanitizeSegment(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace('\\', '/').trim();
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[\\r\\n\\t]", " ").trim();
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            return null;
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    /**
     * 导入路径专用 slug：从「父 id + 名称」确定性生成 {@code imp-<8hex>}。
     *
     * <p>目录名是中文，无法映射成 latin slug，所以 slug 不承载语义、只满足唯一约束与
     * {@code [a-z0-9-]{1,64}} 的格式要求；同名同父命中时直接复用（不走这里），因此重复导入幂等。
     */
    private String uniqueImportSlug(Long parentId, String name) {
        String base = "imp-" + shortHash((parentId == null ? "root" : parentId.toString()) + "/" + name);
        String slug = base;
        int attempt = 1;
        while (categoryMapper.countBySlug(slug, null) > 0) {
            slug = base + "-" + attempt;
            attempt++;
        }
        return slug;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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
