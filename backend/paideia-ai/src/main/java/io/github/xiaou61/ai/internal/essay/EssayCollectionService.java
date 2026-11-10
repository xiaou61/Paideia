package io.github.xiaou61.ai.internal.essay;

import io.github.xiaou61.ai.FailureKind;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.RunKind;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.decision.TechEssayFilter;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.Hashes;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 每日抓取面经 → 过滤 → 抽题 → 入库的编排（WORK-025）。
 *
 * <p>四个阶段，每一步都有明确的"跳过"语义：
 * <ol>
 *   <li><b>抓</b>：按 {@link EssayProperties#effectiveSources()} 逐个源抓最近的帖子。单源失败只跳过
 *       该源，其它源照跑；全部失败才算整次抓取失败。</li>
 *   <li><b>过滤</b>：{@link TechEssayFilter} 判断是不是技术帖。**非技术帖不消耗任何 AI 额度**——
 *       实测同一天 14 条里只有 5 条真在讲题，不过滤就是为每条都花一次解析。</li>
 *   <li><b>抽题</b>：{@link EssayExtractor} 让模型从正文里挑出技术题并写解析。空结果是合法的。</li>
 *   <li><b>入库</b>：按帖子指纹去重后写进知识库。重复的跳过，不报错。</li>
 * </ol>
 *
 * <p><b>多方向（2026-11-06）</b>：一个源对应一个方向（Java / 前端 / 测试 / 数据），落到各自的分类。
 * 方向来自**源**而不是模型——决策模型的主题标签只有四个，判不出这三个方向。
 * 每源各有 {@code maxPostsPerRun} 篇的配额，总量由 {@code maxQuestionsPerRun} 兜住。
 *
 * <p><b>不扣用户积分</b>：抓取解析是平台行为，没有用户归属。{@code CreditService} 按 userId 记账，
 * 这里没有可用的 userId——强行塞一个系统账号会让积分账目出现"凭空扣费"。代价是这部分成本
 * 只能从部署侧的用量统计看，不在产品内的积分面板里。这是已知取舍。
 */
@Service
public class EssayCollectionService {

    private static final Logger log = LoggerFactory.getLogger(EssayCollectionService.class);

    private final EssayProperties properties;
    private final EssaySource source;
    private final TechEssayFilter filter;
    private final EssayExtractor extractor;
    private final KnowledgeApi knowledge;
    private final CredentialService credentials;
    private final RunRecorder recorder;

    EssayCollectionService(EssayProperties properties,
                           EssaySource source,
                           TechEssayFilter filter,
                           EssayExtractor extractor,
                           KnowledgeApi knowledge,
                           CredentialService credentials,
                           RunRecorder recorder) {
        this.properties = properties;
        this.source = source;
        this.filter = filter;
        this.extractor = extractor;
        this.knowledge = knowledge;
        this.credentials = credentials;
        this.recorder = recorder;
    }

    /**
     * 过滤阶段的可观测数字（WORK-070）。模型只能把 false 翻成 true——它只在"技术且概率够"时
     * 返回结论，所以 {@code changedByModel} 反映的是"规则判否、模型救回来"的帖子数。
     */
    public record FilterStats(int borderline, int askedModel, int changedByModel, int routed) {
    }

    /** 一次运行的统计，供日志与测试断言。 */
    public record RunResult(int fetched, int skippedNonTechnical, FilterStats filter, int extracted,
                            int created, int duplicated, int failed, String skipReason) {

        static RunResult skipped(String reason) {
            return new RunResult(0, 0, new FilterStats(0, 0, 0, 0), 0, 0, 0, 0, reason);
        }
    }

    /**
     * 跑一次完整流程。
     *
     * <p>**任何阶段失败都不抛给调用方**：定时任务里抛异常只会让下一轮更难排查。
     * 失败写进返回值与日志。
     */
    public RunResult runOnce() {
        if (!properties.isEnabled()) {
            return RunResult.skipped("未启用");
        }
        Optional<ModelAccess> access = credentials.access(0);
        if (access.isEmpty()) {
            return RunResult.skipped("平台未配置 AI 凭据");
        }

        long since = Instant.now().minus(Duration.ofDays(properties.getLookbackDays())).toEpochMilli();

        // 配了分流才问主题：默认空 map 时行为与改动前逐条一致。
        boolean needTopic = !properties.getCategorySlugs().isEmpty();
        int fetched = 0;
        int skipped = 0;
        int extracted = 0;
        int created = 0;
        int duplicated = 0;
        int failed = 0;
        int borderline = 0;
        int askedModel = 0;
        int changedByModel = 0;
        int routed = 0;
        int sourcesRun = 0;
        int sourcesFailed = 0;
        int sourcesMissingCategory = 0;
        int alreadyCollected = 0;

        for (EssayProperties.SourceSpec spec : properties.effectiveSources()) {
            Long defaultCategoryId = categoryIdOf(spec.getCategorySlug());
            if (defaultCategoryId == null) {
                // 不自动建分类：建分类是人的决定，空分类会直接出现在学习者导航里。
                log.warn("面经源跳过：分类不存在 slug={}（jobId={}）", spec.getCategorySlug(), spec.getJobId());
                sourcesMissingCategory++;
                continue;
            }

            List<FetchedPost> posts;
            var fetchRun = recorder.begin(RunSpec.of(RunKind.ESSAY_COLLECT, 0L));
            try {
                posts = source.fetchSince(spec.getJobId(), since, properties.getMaxPages());
                fetchRun.finish(RunOutcome.OK, null);
            } catch (RuntimeException exception) {
                // 一个源挂了不该让其它方向一起停：记一条，继续下一个源。
                fetchRun.finish(RunOutcome.FAILED, "essay_fetch_failed", FailureKind.RETRYABLE);
                log.warn("面经抓取失败，跳过该源（jobId={}）：{}", spec.getJobId(), exception.getMessage());
                sourcesFailed++;
                continue;
            } finally {
                fetchRun.close();
            }
            sourcesRun++;
            fetched += posts.size();

            int handled = 0;
            for (FetchedPost post : posts) {
                if (handled >= properties.getMaxPostsPerRun() || created >= properties.getMaxQuestionsPerRun()) {
                    break;
                }
                handled++;

                TechEssayFilter.Verdict verdict = filter.judge(post.body(), needTopic);
                if (verdict.borderline()) {
                    borderline++;
                }
                if (verdict.askedModel()) {
                    askedModel++;
                }
                if (verdict.changedByModel()) {
                    changedByModel++;
                }
                if (!verdict.technical()) {
                    skipped++;
                    continue;
                }
                String fingerprint = fingerprintOfPost(post);
                // 整帖已入库就跳过：回溯窗口里的老帖每天都会被重新抓到，重抽一次不但多花模型钱，
                // 还会因为模型措辞变了（"MySQL 的 Doublewrite Buffer" → "MySQL InnoDB 的 Doublewrite Buffer"）
                // 把同一道题再存一遍。抽题之前判，才是真的省下这次调用。
                if (knowledge.hasCollected(fingerprint)) {
                    alreadyCollected++;
                    continue;
                }
                Long categoryId = categoryIdFor(verdict.topic(), defaultCategoryId);
                if (!categoryId.equals(defaultCategoryId)) {
                    routed++;
                }
                var extractRun = recorder.begin(RunSpec.of(RunKind.ESSAY_EXTRACT, 0L)
                        .withModel(access.get().model())
                        .withPromptVersion(EssayExtractionPrompt.VERSION));
                try {
                    EssayExtractionPrompt.Extraction result =
                            extractor.extract(post, access.get(), properties.getTimeoutSeconds());
                    for (EssayExtractionPrompt.ExtractedQuestion question : result.questions()) {
                        if (created >= properties.getMaxQuestionsPerRun()) {
                            break;
                        }
                        extracted++;
                        KnowledgeApi.CreateOutcome outcome = knowledge.createCollected(
                                new KnowledgeApi.CollectedEntry(
                                        question.title(),
                                        render(question, post),
                                        categoryId,
                                        properties.isPublishDirectly() ? "published" : "draft",
                                        fingerprint,
                                        post.url()));
                        if (outcome.created()) {
                            created++;
                        } else {
                            duplicated++;
                        }
                    }
                    extractRun.finish(RunOutcome.OK, null);
                } catch (RuntimeException exception) {
                    // 单篇失败不影响其它帖子：一天十篇里坏一篇，其余照收。
                    // WORK-069：抽题失败按 bad_output 记录；这不改变“不重试”的现有行为。
                    extractRun.finish(RunOutcome.FAILED, "essay_extract_failed", FailureKind.BAD_OUTPUT);
                    failed++;
                    log.warn("面经抽题失败（{}）：{}", post.externalId(), exception.getMessage());
                } finally {
                    extractRun.close();
                }
            }
        }

        if (sourcesRun == 0) {
            if (sourcesFailed > 0) {
                return RunResult.skipped("抓取失败");
            }
            return RunResult.skipped("分类不存在：" + categorySlugs());
        }
        if (fetched == 0) {
            return new RunResult(0, 0, new FilterStats(0, 0, 0, 0), 0, 0, 0, 0, "本次没有新帖");
        }

        log.info("八股文抓取完成：源 {} 个（失败 {} / 缺分类 {}），抓 {} 篇，跳过非技术 {} 篇"
                        + "（边界 {} / 问模型 {} / 改判 {} / 分流 {}），抽出 {} 题，新建 {} 条，"
                + "重复 {} 条，已入库跳过 {} 篇，失败 {} 篇",
                sourcesRun, sourcesFailed, sourcesMissingCategory, fetched, skipped,
                borderline, askedModel, changedByModel, routed,
                extracted, created, duplicated, alreadyCollected, failed);
        return new RunResult(fetched, skipped,
                new FilterStats(borderline, askedModel, changedByModel, routed),
                extracted, created, duplicated, failed, null);
    }

    private String categorySlugs() {
        return properties.effectiveSources().stream()
                .map(EssayProperties.SourceSpec::getCategorySlug)
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    /**
     * 组装入库正文：题目 + 我们的解析 + 来源链接。
     *
     * <p>**不放帖子原文**：面经正文里有公司名、薪资、个人经历，逐字入库既是转载也不适合做学习内容。
     */
    private static String render(EssayExtractionPrompt.ExtractedQuestion question, FetchedPost post) {
        return "## " + question.title() + "\n\n"
                + question.answer() + "\n\n"
                + "---\n\n"
                + "> 题目来源：[" + safeTitle(post.title()) + "](" + post.url() + ")\n";
    }

    private static String safeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "原帖";
        }
        // 标题里若有方括号会把 markdown 链接语法弄坏
        return title.replace("[", "(").replace("]", ")");
    }

    /**
     * 去重指纹：**按来源帖子**的稳定 id，不是按 AI 生成出来的题目标题。
     *
     * <p><b>这条是 2026-10-20 在线上改的，原设计是错的。</b>原本按题目文本算指纹，
     * 理由是"同一道题会在不同面经里反复出现"。但实测第二次运行（重启触发）时，
     * 同一篇帖子抽出来的题目标题措辞变了：
     * <ul>
     *   <li>第一次：线程池中如何可靠地传递 ThreadLocal 上下文？</li>
     *   <li>第二次：在线程池中如何可靠地把父线程的 ThreadLocal 上下文传给子线程？</li>
     * </ul>
     * 意思相同、文本不同 ⇒ 指纹不同 ⇒ **每次运行都新增一条**。日志显示"重复 0 条"、
     * 库里 4 条变 8 条，就是这么来的。
     *
     * <p>模型每次复述的措辞本来就不该被当成身份标识。改成按 `externalId`（帖子 id）去重：
     * 那一列由上游给定，不会漂移。
     *
     * <p>2026-11-10 起一帖最多抽 5 道题，同一篇帖子的几道题**共享这一个指纹**：判重因此不能
     * 只看指纹（见 `KnowledgeApiImplementation.createCollected` 的「指纹 + 归一化标题」），
     * 抓取任务也要在抽题前用 {@link KnowledgeApi#hasCollected} 整帖跳过，免得每轮重抽、
     * 再因措辞漂移把同一道题存两遍。代价仍是"同一道题出现在两篇不同面经里"会存两份——
     * 那是可以接受的，而"每天都重复入库"不可接受。
     */
    static String fingerprintOfPost(FetchedPost post) {
        // 前缀区分来源：将来接第二个站时，不同站的同名 id 不会撞。
        return sha256("nowcoder:" + post.externalId());
    }

    private static String sha256(String value) {
        try {
            return Hashes.sha256Hex(value);
        } catch (Exception exception) {
            // SHA-256 必然存在；走到这里说明运行环境坏了，退回原值保证不丢内容
            // （与共享件的抛异常口径刻意不同——保留）
            return value;
        }
    }

    /**
     * 按判定主题选分类（WORK-070）：没配这个 topic、或目标分类不存在，都回退默认分类。
     * **不自动建分类**——建分类是人的决定。
     */
    Long categoryIdFor(String topic, long fallbackId) {
        String slug = topic == null || topic.isBlank() ? null : properties.getCategorySlugs().get(topic);
        if (slug == null) {
            return fallbackId;
        }
        Long id = categoryIdOf(slug);
        return id == null ? fallbackId : id;
    }

    /** 按 slug 找分类 id；找不到返回 null。 */
    private Long categoryIdOf(String slug) {
        return knowledge.listCategories().stream()
                .filter(node -> slug.equals(node.slug()))
                .map(KnowledgeApi.CategoryNode::id)
                .findFirst()
                .orElse(null);
    }
}
