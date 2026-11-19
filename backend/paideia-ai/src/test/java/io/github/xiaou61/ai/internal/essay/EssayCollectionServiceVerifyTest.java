package io.github.xiaou61.ai.internal.essay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.Test;

/** 验证关闭时入库正文逐字保持原样，验证开启时存疑条目不入库（WORK-084 TASK-004）。 */
class EssayCollectionServiceVerifyTest {

    private static final String LEGACY_BODY =
            "## HashMap 线程安全吗\n\n不安全\n\n---\n\n> 题目来源：[面经](https://example.invalid/1)\n";

    @Test
    void verifyDisabledKeepsLegacyBodyAndNeverCallsVerifier() {
        RecordingKnowledge knowledge = new RecordingKnowledge();
        EssayVerificationService verification = mock(EssayVerificationService.class);

        EssayCollectionService.RunResult result = run(knowledge, verification, false);

        assertThat(knowledge.bodies).containsExactly(LEGACY_BODY);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.pendingReview()).isZero();
        verify(verification, never()).judge(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void verifyPendingDoesNotPersistEntry() {
        RecordingKnowledge knowledge = new RecordingKnowledge();
        EssayVerificationService verification = mock(EssayVerificationService.class);
        when(verification.judge(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new VerificationOutcome(EssayVerificationService.PENDING_REVIEW,
                        null, 0.4, List.of("可能有错"), List.of(), "essay-verify-v1", "test-model"));

        EssayCollectionService.RunResult result = run(knowledge, verification, true);

        assertThat(knowledge.bodies).isEmpty();
        assertThat(result.created()).isZero();
        assertThat(result.pendingReview()).isEqualTo(1);
    }

    @Test
    void verifyRevisedPersistsFinalAnswer() {
        RecordingKnowledge knowledge = new RecordingKnowledge();
        EssayVerificationService verification = mock(EssayVerificationService.class);
        when(verification.judge(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new VerificationOutcome(EssayVerificationService.REVISED,
                        "不安全，HashMap 非线程安全", 0.9, List.of(), List.of(), "essay-revise-v1", "test-model"));

        EssayCollectionService.RunResult result = run(knowledge, verification, true);

        assertThat(knowledge.bodies).containsExactly(
                "## HashMap 线程安全吗\n\n不安全，HashMap 非线程安全\n\n---\n\n"
                        + "> 题目来源：[面经](https://example.invalid/1)\n");
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.pendingReview()).isZero();
    }

    private static EssayCollectionService.RunResult run(KnowledgeApi knowledge,
                                                        EssayVerificationService verification,
                                                        boolean verifyEnabled) {
        EssayProperties properties = new EssayProperties();
        properties.setEnabled(true);
        properties.setCategorySlug("essay-java");
        properties.getVerify().setEnabled(verifyEnabled);

        EssaySource source = (jobId, sinceMillis, maxPages) -> List.of(
                new FetchedPost("post-1", "面经", "面经：HashMap JVM MySQL Spring Redis Kafka 都在问",
                        "https://example.invalid/1", 0L));

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

        return new EssayCollectionService(properties, source,
                new TechEssayFilter(text -> Optional.of(new ContentClassification(true, 0.9, "java"))),
                extractor, verification, knowledge, credentials, recorder).runOnce();
    }

    /** 只实现本用例走到的两个方法：列分类、记下写入的正文。 */
    private static final class RecordingKnowledge implements KnowledgeApi {

        private final List<String> bodies = new ArrayList<>();

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
            return List.of(new CategoryNode(1L, "essay-java", "essay-java", null));
        }

        @Override
        public List<MarkedEntry> findMarks(long userId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CreateOutcome createCollected(CollectedEntry input) {
            bodies.add(input.body());
            return new CreateOutcome(1L, true);
        }
    }
}
