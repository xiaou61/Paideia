package io.github.xiaou61.ai.internal.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.OpsDiagnoser;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.provider.OpsDiagnosisModelCaller;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 诊断编排的积分与失败语义（WORK-042 AC-010 / REQ-011）。
 *
 * <p>模型全程打桩，不出网。三个必测的失败模式：
 * <ol>
 *   <li>平台未配置或余额不足时**仍然发起了上游请求**；</li>
 *   <li>上游失败后预留没有退回；</li>
 *   <li>结算成功后遥测没有记 token / 实扣积分。</li>
 * </ol>
 */
class DefaultOpsDiagnoserTest {

    private static final long USER = 7L;
    private static final String MODEL = "deepseek-flash";

    private CredentialService credentials;
    private CreditService credits;
    private RunRecorder recorder;
    private RunRecorder.RunScope scope;
    private OpsDiagnosisModelCaller caller;
    private DefaultOpsDiagnoser diagnoser;

    @BeforeEach
    void setUp() {
        credentials = mock(CredentialService.class);
        credits = mock(CreditService.class);
        recorder = mock(RunRecorder.class);
        scope = mock(RunRecorder.RunScope.class);
        caller = mock(OpsDiagnosisModelCaller.class);
        diagnoser = new DefaultOpsDiagnoser(credentials, credits, recorder, caller);

        when(credentials.access(USER)).thenReturn(Optional.of(
                new ModelAccess("https://api.example.com", MODEL, "test-key")));
        when(credits.estimate()).thenReturn(100L);
        when(credits.reserve(USER, 100L)).thenReturn(100L);
        when(recorder.begin(any())).thenReturn(scope);
    }

    @Test
    @DisplayName("平台未配置时直接失败，不预留也不调用")
    void missingCredentialMakesNoCall() {
        when(credentials.access(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diagnoser.diagnose(USER, evidence()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("平台尚未配置");

        verify(credits, never()).reserve(anyLong(), anyLong());
        verify(caller, never()).diagnose(any(), anyString(), anyString());
        verify(recorder, never()).begin(any());
    }

    @Test
    @DisplayName("余额不足时在发起上游请求之前失败")
    void insufficientCreditsMakesNoCall() {
        when(credits.reserve(USER, 100L))
                .thenThrow(new BizException(ErrorCode.INSUFFICIENT_CREDITS));

        assertThatThrownBy(() -> diagnoser.diagnose(USER, evidence()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_CREDITS);

        verify(caller, never()).diagnose(any(), anyString(), anyString());
        verify(recorder, never()).begin(any());
        verify(credits, never()).refund(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("上游失败时全额退款并把遥测标成失败")
    void upstreamFailureRefunds() {
        when(caller.diagnose(any(), anyString(), anyString()))
                .thenThrow(new BizException(ErrorCode.INTERNAL, "上游不可用"));

        assertThatThrownBy(() -> diagnoser.diagnose(USER, evidence()))
                .isInstanceOf(BizException.class);

        verify(credits).refund(eq(USER), eq(100L), any(), eq("运维诊断失败"));
        verify(scope).finish(RunOutcome.FAILED, "50000");
        verify(scope).close();
    }

    @Test
    @DisplayName("成功时按实际用量结算，并记录 token 与实扣积分")
    void successSettlesAndRecordsUsage() {
        TokenUsage usage = new TokenUsage(10, 20, 30);
        when(caller.diagnose(any(), anyString(), anyString()))
                .thenReturn(new OpsDiagnosisModelCaller.Reply("# 报告", usage));
        when(credits.settleOpsDiagnosis(USER, 100L, MODEL, 10, 20)).thenReturn(5);

        OpsDiagnoser.Diagnosis diagnosis = diagnoser.diagnose(USER, evidence());

        assertThat(diagnosis.report()).isEqualTo("# 报告");
        assertThat(diagnosis.model()).isEqualTo(MODEL);
        verify(credits).settleOpsDiagnosis(USER, 100L, MODEL, 10, 20);
        verify(scope).tokens(10, 20, 0);
        verify(scope).addCredits(5);
        verify(scope).finish(RunOutcome.OK, null);
        verify(scope).close();
        verify(credits, never()).refund(anyLong(), anyLong(), any(), anyString());
    }

    private static OpsDiagnoser.Evidence evidence() {
        return new OpsDiagnoser.Evidence(
                "manual", null, "现在为什么慢",
                "服务状态：OK", "p95 = 1.2s", "p95 ratio=1.0x",
                "## 历史同类事件\n无历史同类事件", "无 firing 告警",
                "2026-10-26T10:00:00Z WARN slow", null, "deploy 10:00");
    }
}
