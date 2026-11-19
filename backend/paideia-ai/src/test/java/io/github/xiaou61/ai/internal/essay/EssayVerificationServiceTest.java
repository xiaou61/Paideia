package io.github.xiaou61.ai.internal.essay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.RunRecorder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 内部自检的分流状态机（WORK-084 TASK-004）。 */
class EssayVerificationServiceTest {

    private static final ModelAccess ACCESS = new ModelAccess("http://127.0.0.1:1", "test-model", "k");

    private EssayProperties properties;
    private EssayVerifier verifier;
    private EssayVerificationMapper mapper;
    private EssayVerificationService service;

    @BeforeEach
    void setUp() {
        properties = new EssayProperties();
        properties.getVerify().setEnabled(true);
        verifier = mock(EssayVerifier.class);
        mapper = mock(EssayVerificationMapper.class);
        RunRecorder recorder = mock(RunRecorder.class);
        RunRecorder.RunScope scope = mock(RunRecorder.RunScope.class);
        when(recorder.begin(any())).thenReturn(scope);
        service = new EssayVerificationService(properties, verifier, mapper, recorder);
        service.beginRun();
    }

    @Test
    void okHighConfidenceGoesAutoPassed() {
        when(verifier.checkFactual(anyString(), anyString(), any()))
                .thenReturn(new FactCheck("ok", 0.9, List.of()));

        VerificationOutcome outcome = judge();

        assertThat(outcome.status()).isEqualTo(EssayVerificationService.AUTO_PASSED);
        assertThat(outcome.finalAnswer()).isNull();
        verify(verifier, never()).revise(anyString(), anyString(), any(), any(), any());
        assertThat(insertedRow().getStatus()).isEqualTo("auto_passed");
    }

    @Test
    void okLowConfidenceEscalatesToRevision() {
        when(verifier.checkFactual(anyString(), anyString(), any()))
                .thenReturn(new FactCheck("unsure", 0.4, List.of("拿不准")));
        when(verifier.revise(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new Revision("revise", "改好的答案", 0.8, List.of(), "修了数值"));

        VerificationOutcome outcome = judge();

        assertThat(outcome.status()).isEqualTo(EssayVerificationService.REVISED);
        assertThat(outcome.finalAnswer()).isEqualTo("改好的答案");
    }

    @Test
    void issuesWithRejectedRevisionGoesPending() {
        when(verifier.checkFactual(anyString(), anyString(), any()))
                .thenReturn(new FactCheck("issues", 0.9, List.of("数值差 10 倍")));
        when(verifier.revise(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new Revision("reject", null, 0.2, List.of(), "无依据"));

        VerificationOutcome outcome = judge();

        assertThat(outcome.status()).isEqualTo(EssayVerificationService.PENDING_REVIEW);
        assertThat(outcome.finalAnswer()).isNull();
        assertThat(insertedRow().getStatus()).isEqualTo("pending_review");
    }

    @Test
    void verifyErrorGoesToPendingReview() {
        when(verifier.checkFactual(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("boom"));

        VerificationOutcome outcome = judge();

        assertThat(outcome.status()).isEqualTo(EssayVerificationService.PENDING_REVIEW);
        assertThat(outcome.issues()).anyMatch(issue -> issue.startsWith("verify_error"));
        assertThat(insertedRow().getStatus()).isEqualTo("pending_review");
    }

    @Test
    void budgetExhaustedGoesPending() {
        properties.getVerify().setMaxCallsPerRun(0);

        VerificationOutcome outcome = judge();

        assertThat(outcome.status()).isEqualTo(EssayVerificationService.PENDING_REVIEW);
        assertThat(outcome.issues()).contains("budget_exhausted");
        verify(verifier, never()).checkFactual(anyString(), anyString(), any());
    }

    @Test
    void lowConfidenceRevisionGoesPending() {
        when(verifier.checkFactual(anyString(), anyString(), any()))
                .thenReturn(new FactCheck("issues", 0.9, List.of("可能有错")));
        when(verifier.revise(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new Revision("revise", "半信半疑的稿", 0.5, List.of(), ""));

        VerificationOutcome outcome = judge();

        assertThat(outcome.status()).isEqualTo(EssayVerificationService.PENDING_REVIEW);
    }

    private VerificationOutcome judge() {
        return service.judge("题", "答案", ACCESS, 1L, "fp", "https://example.com/1", "某面经");
    }

    private EssayVerificationRow insertedRow() {
        ArgumentCaptor<EssayVerificationRow> captor = ArgumentCaptor.forClass(EssayVerificationRow.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
}
