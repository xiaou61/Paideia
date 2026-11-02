package io.github.xiaou61.ai.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.internal.conversation.AssistantAnswerer;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolResult;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * `assistant_ask` 的计费纪律：**余额不足不发起上游**、成功才结算、失败全额退。
 */
class AskToolTest {

    private static final ToolContext CALLER =
            new ToolContext(7L, null, java.util.Set.of(io.github.xiaou61.platform.ToolScope.WRITE),
                    ToolContext.Origin.EXTERNAL);

    private final AssistantAnswerer answerer = mock(AssistantAnswerer.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final CreditService credits = mock(CreditService.class);

    private AskTool tool() {
        // ObjectProvider 只有一个方法被用到（getObject），用实现类包一层即可，不必拉 Spring 测试栈
        org.springframework.beans.factory.ObjectProvider<AssistantAnswerer> provider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public AssistantAnswerer getObject() {
                        return answerer;
                    }

                    @Override
                    public AssistantAnswerer getObject(Object... args) {
                        return answerer;
                    }

                    @Override
                    public AssistantAnswerer getIfAvailable() {
                        return answerer;
                    }

                    @Override
                    public AssistantAnswerer getIfUnique() {
                        return answerer;
                    }

                    @Override
                    public java.util.Iterator<AssistantAnswerer> iterator() {
                        return java.util.List.of(answerer).iterator();
                    }
                };
        return new AskTool(provider, credentials, credits, new io.github.xiaou61.ai.internal.telemetry.NoopRunRecorder());
    }

    @Test
    void zeroBalanceMakesNoUpstreamCall() {
        when(credentials.access(7L)).thenReturn(Optional.of(new ModelAccess("https://api", "deepseek-flash", "k")));
        when(credits.estimate()).thenReturn(10L);
        when(credits.reserve(7L, 10L)).thenThrow(new BizException(ErrorCode.INSUFFICIENT_CREDITS, "积分不足"));

        ToolResult result = tool().call(CALLER, Map.of("question", "什么是索引"));

        assertThat(result.error()).isTrue();
        verify(answerer, never()).answer(anyLong(), anyString(), any(), any());
    }

    @Test
    void successSettlesWithUsage() {
        when(credentials.access(7L)).thenReturn(Optional.of(new ModelAccess("https://api", "deepseek-flash", "k")));
        when(credits.estimate()).thenReturn(10L);
        when(credits.reserve(7L, 10L)).thenReturn(10L);
        when(answerer.answer(eq(7L), eq("什么是索引"), any(), any()))
                .thenReturn(new AssistantAnswerer.Answer("索引是……", new TokenUsage(100, 50, 150)));

        ToolResult result = tool().call(CALLER, Map.of("question", "什么是索引"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("索引是");
        verify(credits).settleExternalAsk(7L, 10L, "deepseek-flash", 100, 50);
    }

    @Test
    void failureRefundsReservation() {
        when(credentials.access(7L)).thenReturn(Optional.of(new ModelAccess("https://api", "deepseek-flash", "k")));
        when(credits.estimate()).thenReturn(10L);
        when(credits.reserve(7L, 10L)).thenReturn(10L);
        when(answerer.answer(anyLong(), anyString(), any(), any()))
                .thenThrow(new BizException(ErrorCode.INTERNAL, "上游超时"));

        ToolResult result = tool().call(CALLER, Map.of("question", "什么是索引"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("上游超时");
        verify(credits).refund(eq(7L), eq(10L), any(), anyString());
        verify(credits, never()).settleExternalAsk(anyLong(), anyLong(), anyString(), any(), any());
    }

    @Test
    void missingCredentialsDoesNotTouchCredits() {
        when(credentials.access(7L)).thenReturn(Optional.empty());

        ToolResult result = tool().call(CALLER, Map.of("question", "x"));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("尚未配置");
        verify(credits, never()).reserve(anyLong(), anyLong());
    }

    @Test
    void emptyQuestionFailsBeforeAnySideEffect() {
        ToolResult result = tool().call(CALLER, Map.of("question", "   "));

        assertThat(result.error()).isTrue();
        verify(credentials, never()).access(anyLong());
    }
}
