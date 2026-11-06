package io.github.xiaou61.ai.internal.essay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.FailureKind;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.decision.ContentClassification;
import io.github.xiaou61.ai.internal.decision.TechEssayFilter;
import io.github.xiaou61.knowledge.KnowledgeApi;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 面经抓取/抽题失败写入 failure_kind（WORK-069 AC-004）。 */
class EssayCollectionFailureKindTest {

    private EssayProperties properties;
    private EssaySource source;
    private EssayExtractor extractor;
    private KnowledgeApi knowledge;
    private CredentialService credentials;
    private RunRecorder recorder;
    private RunRecorder.RunScope scope;
    private EssayCollectionService service;

    @BeforeEach
    void setUp() {
        properties = new EssayProperties();
        properties.setEnabled(true);
        properties.setCategorySlug("essay-java");
        source = mock(EssaySource.class);
        extractor = mock(EssayExtractor.class);
        knowledge = mock(KnowledgeApi.class);
        credentials = mock(CredentialService.class);
        recorder = mock(RunRecorder.class);
        scope = mock(RunRecorder.RunScope.class);

        when(credentials.access(0)).thenReturn(
                Optional.of(new ModelAccess("http://127.0.0.1:1", "test-model", "k")));
        when(knowledge.listCategories()).thenReturn(List.of(
                new KnowledgeApi.CategoryNode(1L, "面经", "essay-java", null)));
        when(knowledge.createCollected(any())).thenReturn(new KnowledgeApi.CreateOutcome(1L, true));
        when(recorder.begin(any())).thenReturn(scope);
        service = new EssayCollectionService(properties, source,
                new TechEssayFilter(text -> Optional.of(new ContentClassification(true, 0.9, "java"))),
                extractor, knowledge, credentials, recorder);
    }

    @Test
    @DisplayName("抓取失败是 retryable，整次跳过")
    void fetchFailureIsRetryable() {
        when(source.fetchSince(anyLong(), anyLong(), anyInt())).thenThrow(new IllegalStateException("网络断了"));

        EssayCollectionService.RunResult result = service.runOnce();

        assertThat(result.skipReason()).isEqualTo("抓取失败");
        verify(scope).finish(RunOutcome.FAILED, "essay_fetch_failed", FailureKind.RETRYABLE);
    }

    @Test
    @DisplayName("单篇抽题失败是 bad_output，且不影响其它帖子")
    void perPostFailureIsBadOutputAndDoesNotAbortRun() {
        when(source.fetchSince(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                new FetchedPost("post-1", "面经", "HashMap JVM MySQL Spring Redis Kafka", "https://example.invalid/1", 0L),
                new FetchedPost("post-2", "面经", "HashMap JVM MySQL Spring Redis Kafka", "https://example.invalid/2", 0L)));
        when(extractor.extract(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("模型输出不是 JSON"))
                .thenReturn(new EssayExtractionPrompt.Extraction(
                        List.of(new EssayExtractionPrompt.ExtractedQuestion("HashMap 线程安全吗", "不安全"))));

        EssayCollectionService.RunResult result = service.runOnce();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        verify(scope).finish(RunOutcome.FAILED, "essay_extract_failed", FailureKind.BAD_OUTPUT);
        verify(scope, atLeastOnce()).finish(RunOutcome.OK, null);
    }

    @Test
    @DisplayName("成功抽取写 OK，且不写 failure_kind")
    void successfulExtractionWritesOkRun() {
        when(source.fetchSince(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                new FetchedPost("post-1", "面经", "HashMap JVM MySQL Spring Redis Kafka", "https://example.invalid/1", 0L)));
        when(extractor.extract(any(), any(), anyInt())).thenReturn(new EssayExtractionPrompt.Extraction(
                List.of(new EssayExtractionPrompt.ExtractedQuestion("HashMap 线程安全吗", "不安全"))));

        service.runOnce();

        verify(scope, atLeastOnce()).finish(RunOutcome.OK, null);
    }
}
