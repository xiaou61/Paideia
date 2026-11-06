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
import io.github.xiaou61.ai.internal.decision.ContentClassifier;
import io.github.xiaou61.ai.internal.decision.TechEssayFilter;
import io.github.xiaou61.knowledge.EntryContent;
import io.github.xiaou61.knowledge.KnowledgeApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 按判定主题分流（WORK-070 REQ-001）与过滤计数（REQ-002）。
 *
 * <p>用桩替掉抓取、抽题与凭据：本类要验的是「入库时选了哪个分类」和「几个计数对不对」，
 * 不是外部站点与模型能不能跑。
 */
class EssayCollectionRoutingTest {

    private static final String DEFAULT_SLUG = "essay-java";
    private static final String DATABASE_SLUG = "essay-database";

    /**
     * 命中 6 种**英文**技术词，规则自己就判成技术帖。
     *
     * <p>这里仍用英文词构造：本类验的是分流与计数，不验词表。中文词的匹配口径见
     * {@code TechEssayFilterTest}——2026-11-06 之前中文分支带 `\b` 而中文不是 `\w`，
     * 词表里的中文词实际全部失效；那次一并修了。
     */
    private static final String CLEARLY_TECHNICAL = "面经：HashMap JVM MySQL Spring Redis Kafka 都在问";

    /** 只命中 1 种技术词，落在边界地带。 */
    private static final String BORDERLINE_POST = "我是java开发，聊聊求职";

    @Test
    void routesToMappedCategoryWhenItExists() {
        RecordingKnowledge knowledge = new RecordingKnowledge(
                List.of(node(1, DEFAULT_SLUG), node(2, DATABASE_SLUG)));

        EssayCollectionService.RunResult result = run(
                Map.of("database", DATABASE_SLUG), knowledge,
                classifierReturning("database"), CLEARLY_TECHNICAL);

        assertThat(knowledge.categoryIds).containsExactly(2L);
        assertThat(result.filter().routed()).isEqualTo(1);
    }

    @Test
    void fallsBackWhenMappedCategoryIsMissing() {
        RecordingKnowledge knowledge = new RecordingKnowledge(List.of(node(1, DEFAULT_SLUG)));

        EssayCollectionService.RunResult result = run(
                Map.of("database", DATABASE_SLUG), knowledge,
                classifierReturning("database"), CLEARLY_TECHNICAL);

        // 分类没建就回退，且不假装分流成功
        assertThat(knowledge.categoryIds).containsExactly(1L);
        assertThat(result.filter().routed()).isZero();
    }

    @Test
    void noMappingKeepsPreviousBehaviourAndNeverAsksForTopic() {
        RecordingKnowledge knowledge = new RecordingKnowledge(
                List.of(node(1, DEFAULT_SLUG), node(2, DATABASE_SLUG)));

        EssayCollectionService.RunResult result = run(
                Map.of(), knowledge, classifierReturning("database"), CLEARLY_TECHNICAL);

        assertThat(knowledge.categoryIds).containsExactly(1L);
        assertThat(result.filter().askedModel()).isZero();
        assertThat(result.filter().borderline()).isZero();
    }

    @Test
    void countsBorderlineModelCallsAndOverrides() {
        RecordingKnowledge knowledge = new RecordingKnowledge(List.of(node(1, DEFAULT_SLUG)));

        // 边界帖 + 模型判成技术 → 改判 1 次
        EssayCollectionService.RunResult result = run(
                Map.of(), knowledge,
                text -> Optional.of(new ContentClassification(true, 0.9, "java")),
                BORDERLINE_POST);

        assertThat(result.filter().borderline()).isEqualTo(1);
        assertThat(result.filter().askedModel()).isEqualTo(1);
        assertThat(result.filter().changedByModel()).isEqualTo(1);
        assertThat(result.skippedNonTechnical()).isZero();
    }

    @Test
    void modelUnavailableFallsBackToRuleWithoutCountingAnOverride() {
        RecordingKnowledge knowledge = new RecordingKnowledge(List.of(node(1, DEFAULT_SLUG)));

        EssayCollectionService.RunResult result = run(
                Map.of(), knowledge, text -> Optional.empty(), BORDERLINE_POST);

        assertThat(result.filter().askedModel()).isEqualTo(1);
        assertThat(result.filter().changedByModel()).isZero();
        assertThat(result.skippedNonTechnical()).isEqualTo(1);
        assertThat(knowledge.categoryIds).isEmpty();
    }

    private static EssayCollectionService.RunResult run(Map<String, String> categorySlugs,
                                                        KnowledgeApi knowledge,
                                                        ContentClassifier classifier,
                                                        String body) {
        EssayProperties properties = new EssayProperties();
        properties.setEnabled(true);
        properties.setCategorySlug(DEFAULT_SLUG);
        properties.setCategorySlugs(categorySlugs);

        EssaySource source = (jobId, sinceMillis, maxPages) -> List.of(
                new FetchedPost("post-1", "面经", body, "https://example.invalid/1", 0L));

        EssayExtractor extractor = mock(EssayExtractor.class);
        when(extractor.extract(any(), any(), anyInt())).thenReturn(
                new EssayExtractionPrompt.Extraction(
                        List.of(new EssayExtractionPrompt.ExtractedQuestion("HashMap 线程安全吗", "不安全"))));

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.access(0)).thenReturn(
                Optional.of(new ModelAccess("http://127.0.0.1:1", "test-model", "k")));

        RunRecorder recorder = mock(RunRecorder.class);
        RunRecorder.RunScope scope = mock(RunRecorder.RunScope.class);
        when(recorder.begin(any())).thenReturn(scope);

        return new EssayCollectionService(properties, source, new TechEssayFilter(classifier),
                extractor, knowledge, credentials, recorder).runOnce();
    }

    private static ContentClassifier classifierReturning(String topic) {
        return text -> Optional.of(new ContentClassification(true, 0.9, topic));
    }

    private static KnowledgeApi.CategoryNode node(long id, String slug) {
        return new KnowledgeApi.CategoryNode(id, slug, slug, null);
    }

    /** 只实现本用例走到的两个方法：列分类、写入时记下选了哪个分类。 */
    private static final class RecordingKnowledge implements KnowledgeApi {

        private final List<CategoryNode> categories;
        private final List<Long> categoryIds = new ArrayList<>();

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
            return new CreateOutcome(1L, true);
        }
    }
}
