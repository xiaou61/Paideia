package io.github.xiaou61.ai.internal.essay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.decision.ContentClassification;
import io.github.xiaou61.ai.internal.decision.TechEssayFilter;
import io.github.xiaou61.knowledge.EntryContent;
import io.github.xiaou61.knowledge.KnowledgeApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 多方向抓取（2026-11-06）：一个源一个方向，落到各自的分类。
 *
 * <p>抓取、抽题、凭据全部用桩替掉——本类验的是「哪个源的内容进了哪个分类」「一个源坏了会不会
 * 拖垮别的源」「配额是每源还是全局」，不是外部站点与模型能不能跑。
 */
class EssaySourceFanOutTest {

    private static final long JAVA_JOB = 639;
    private static final long FRONTEND_JOB = 644;

    /** 规则能自己判定的技术帖正文（英文词，词表口径与本类无关）。 */
    private static final String TECH_BODY = "面经：HashMap JVM MySQL Spring Redis Kafka 都在问";

    @Test
    @DisplayName("每个源落到自己的分类，互不串台")
    void eachSourceWritesToItsOwnCategory() {
        RecordingKnowledge knowledge = new RecordingKnowledge(
                List.of(node(1, "essay-java"), node(2, "essay-frontend")));

        EssayCollectionService.RunResult result = run(
                properties(List.of(new EssayProperties.SourceSpec(JAVA_JOB, "essay-java"),
                        new EssayProperties.SourceSpec(FRONTEND_JOB, "essay-frontend"))),
                knowledge, postingSource(TECH_BODY));

        assertThat(knowledge.categoryIds).containsExactly(1L, 2L);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipReason()).isNull();
    }

    /**
     * 一帖多题（2026-11-10）。
     *
     * <p>抽题提示词原来是"只挑 1 道"，于是帖子里其余技术问题永远进不来；现在一帖最多 5 道，
     * 编排层要**逐题**调用入库，而不是只取第一道。
     */
    @Test
    @DisplayName("一帖抽出多道题时每道都入库，不只第一道")
    void everyExtractedQuestionIsStored() {
        RecordingKnowledge knowledge = new RecordingKnowledge(List.of(node(1, "essay-java")));

        EssayCollectionService.RunResult result = run(
                properties(List.of(new EssayProperties.SourceSpec(JAVA_JOB, "essay-java"))),
                knowledge,
                postingSource(TECH_BODY),
                List.of(
                        new EssayExtractionPrompt.ExtractedQuestion("HashMap 为什么线程不安全", "并发扩容会成环"),
                        new EssayExtractionPrompt.ExtractedQuestion("ConcurrentHashMap 分段锁去哪儿了", "改成了 CAS 加桶头锁"),
                        new EssayExtractionPrompt.ExtractedQuestion("为什么扩容是两倍", "让哈希位运算等价于取模")));

        assertThat(result.created()).isEqualTo(3);
        assertThat(knowledge.titles).containsExactly(
                "HashMap 为什么线程不安全",
                "ConcurrentHashMap 分段锁去哪儿了",
                "为什么扩容是两倍");
    }

    @Test
    @DisplayName("分类不存在只跳过该源，其它源照跑")
    void missingCategorySkipsOnlyThatSource() {
        RecordingKnowledge knowledge = new RecordingKnowledge(
                List.of(node(2, "essay-frontend")));

        EssayCollectionService.RunResult result = run(
                properties(List.of(new EssayProperties.SourceSpec(JAVA_JOB, "essay-not-created"),
                        new EssayProperties.SourceSpec(FRONTEND_JOB, "essay-frontend"))),
                knowledge, postingSource(TECH_BODY));

        assertThat(knowledge.categoryIds).containsExactly(2L);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipReason()).isNull();
    }

    @Test
    @DisplayName("一个源抓取失败不影响另一个，全部失败才算整次失败")
    void oneFailingSourceDoesNotAbortTheOthers() {
        RecordingKnowledge knowledge = new RecordingKnowledge(
                List.of(node(1, "essay-java"), node(2, "essay-frontend")));
        EssayProperties properties = properties(List.of(
                new EssayProperties.SourceSpec(JAVA_JOB, "essay-java"),
                new EssayProperties.SourceSpec(FRONTEND_JOB, "essay-frontend")));

        EssaySource flaky = (jobId, since, pages) -> {
            if (jobId == JAVA_JOB) {
                throw new IllegalStateException("上游 502");
            }
            return List.of(post(FRONTEND_JOB, TECH_BODY));
        };

        EssayCollectionService.RunResult partial = run(properties, knowledge, flaky);
        assertThat(partial.skipReason()).isNull();
        assertThat(partial.created()).isEqualTo(1);

        EssaySource broken = (jobId, since, pages) -> {
            throw new IllegalStateException("上游 502");
        };
        assertThat(run(properties, knowledge, broken).skipReason()).isEqualTo("抓取失败");
    }

    @Test
    @DisplayName("帖子上限是每源各算，不是全局共用")
    void postQuotaIsPerSource() {
        RecordingKnowledge knowledge = new RecordingKnowledge(
                List.of(node(1, "essay-java"), node(2, "essay-frontend")));
        EssayProperties properties = properties(List.of(
                new EssayProperties.SourceSpec(JAVA_JOB, "essay-java"),
                new EssayProperties.SourceSpec(FRONTEND_JOB, "essay-frontend")));
        properties.setMaxPostsPerRun(1);

        // 每个源都给两篇：配额是 1 的话，两个源各处理 1 篇 → 共 2 条。
        EssaySource twoEach = (jobId, since, pages) -> List.of(
                post(jobId, TECH_BODY), post(jobId + 1000, TECH_BODY));

        EssayCollectionService.RunResult result = run(properties, knowledge, twoEach);

        assertThat(result.created()).isEqualTo(2);
        assertThat(knowledge.categoryIds).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("题数上限跨源合计：到顶就停，后面的源不再抽题")
    void questionQuotaIsGlobalAcrossSources() {
        RecordingKnowledge knowledge = new RecordingKnowledge(
                List.of(node(1, "essay-java"), node(2, "essay-frontend")));
        EssayProperties properties = properties(List.of(
                new EssayProperties.SourceSpec(JAVA_JOB, "essay-java"),
                new EssayProperties.SourceSpec(FRONTEND_JOB, "essay-frontend")));
        properties.setMaxQuestionsPerRun(1);

        EssayCollectionService.RunResult result = run(properties, knowledge, postingSource(TECH_BODY));

        assertThat(result.created()).isEqualTo(1);
        assertThat(knowledge.categoryIds).containsExactly(1L);
    }

    @Test
    @DisplayName("扩了方向也要能按方向过滤：前端/测试帖不再被判成非技术")
    void directionSpecificPostsPassTheRuleFilter() {
        RecordingKnowledge knowledge = new RecordingKnowledge(
                List.of(node(1, "essay-java"), node(3, "essay-frontend"), node(4, "essay-testing")));
        EssayProperties properties = properties(List.of(
                new EssayProperties.SourceSpec(JAVA_JOB, "essay-java"),
                new EssayProperties.SourceSpec(FRONTEND_JOB, "essay-frontend"),
                new EssayProperties.SourceSpec(680, "essay-testing")));

        // 三篇分别是纯中文的前端 / 测试描述，没有英文技术词——靠词表的中文分支判定。
        EssaySource byDirection = (jobId, since, pages) -> {
            String body = switch ((int) jobId) {
                case 639 -> TECH_BODY;
                case 644 -> "前端一面：浏览器的跨域是怎么处理的，事件循环里微任务和宏任务的顺序，"
                        + "闭包为什么会造成内存泄漏，防抖节流写过吗";
                default -> "测试一面：测试用例怎么设计，等价类和边界值各举一个，接口自动化的断言怎么写，"
                        + "压测用什么工具，缺陷的生命周期是什么";
            };
            return List.of(post(jobId, body));
        };

        EssayCollectionService.RunResult result = run(properties, knowledge, byDirection);

        assertThat(result.created()).isEqualTo(3);
        assertThat(knowledge.categoryIds).containsExactly(1L, 3L, 4L);
    }

    private static EssayProperties properties(List<EssayProperties.SourceSpec> sources) {
        EssayProperties properties = new EssayProperties();
        properties.setEnabled(true);
        properties.setCategorySlug("essay-java");
        properties.setSources(sources);
        return properties;
    }

    /** 每个源返回同一篇技术帖（用 jobId 造 id，避免两个源撞成同一篇）。 */
    private static EssaySource postingSource(String body) {
        return (jobId, since, pages) -> List.of(post(jobId, body));
    }

    private static FetchedPost post(long jobId, String body) {
        return new FetchedPost("post-" + jobId, "面经", body,
                "https://example.invalid/" + jobId, 0L);
    }

    private static EssayCollectionService.RunResult run(EssayProperties properties,
                                                        KnowledgeApi knowledge,
                                                        EssaySource source) {
        return run(properties, knowledge, source, List.of(
                new EssayExtractionPrompt.ExtractedQuestion("为什么这么设计", "因为这样更稳")));
    }

    private static EssayCollectionService.RunResult run(EssayProperties properties,
                                                        KnowledgeApi knowledge,
                                                        EssaySource source,
                                                        List<EssayExtractionPrompt.ExtractedQuestion> questions) {
        EssayExtractor extractor = mock(EssayExtractor.class);
        when(extractor.extract(any(), any(), anyInt())).thenReturn(
                new EssayExtractionPrompt.Extraction(questions));

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.access(0)).thenReturn(
                Optional.of(new ModelAccess("http://127.0.0.1:1", "test-model", "k")));

        RunRecorder recorder = mock(RunRecorder.class);
        RunRecorder.RunScope scope = mock(RunRecorder.RunScope.class);
        when(recorder.begin(any())).thenReturn(scope);

        // 判成技术帖的桩分类器：本类只验编排，不验规则阈值。
        TechEssayFilter filter = new TechEssayFilter(
                text -> Optional.of(new ContentClassification(true, 0.9, "java")));

        return new EssayCollectionService(properties, source, filter, extractor, knowledge,
                credentials, recorder).runOnce();
    }

    private static KnowledgeApi.CategoryNode node(long id, String slug) {
        return new KnowledgeApi.CategoryNode(id, slug, slug, null);
    }

    /** 只实现本类走到的两个方法：列分类、写入时记下选了哪个分类。 */
    private static final class RecordingKnowledge implements KnowledgeApi {

        private final List<CategoryNode> categories;
        private final List<Long> categoryIds = new ArrayList<>();
        private final List<String> titles = new ArrayList<>();

        private RecordingKnowledge(List<CategoryNode> categories) {
            this.categories = categories;
        }

        @Override
        public Optional<EntryContent> findPublishedContent(long entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EntryBrief> searchPublished(String keyword, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CategoryNode> listCategories() {
            return categories;
        }

        @Override
        public List<MarkedEntry> findMarks(long userId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CreateOutcome createCollected(CollectedEntry input) {
            categoryIds.add(input.categoryId());
            titles.add(input.title());
            return new CreateOutcome(1L, true);
        }
    }
}
