package io.github.xiaou61.ai.internal.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.model.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FinishReasonsTest {

    private static ChatResponse withReason(String reason) {
        return ChatResponse.builder().finishReason(reason).build();
    }

    @Test
    @DisplayName("finish_reason=length 认定截断")
    void lengthIsTruncated() {
        assertThat(FinishReasons.truncated(withReason("length"))).isTrue();
    }

    @Test
    @DisplayName("兼容层把上限写成 MAX_TOKENS 也认")
    void maxTokensSpellingIsTruncated() {
        assertThat(FinishReasons.truncated(withReason("MAX_TOKENS"))).isTrue();
        assertThat(FinishReasons.truncated(withReason("max_tokens"))).isTrue();
    }

    @Test
    @DisplayName("stop 不算截断")
    void stopIsNotTruncated() {
        assertThat(FinishReasons.truncated(withReason("stop"))).isFalse();
        assertThat(FinishReasons.truncated(withReason("tool_calls"))).isFalse();
    }

    @Test
    @DisplayName("上游没给 finish_reason 时按未截断处理：不能把正常回复当失败")
    void missingReasonIsNotTruncated() {
        assertThat(FinishReasons.truncated(withReason(null))).isFalse();
        assertThat(FinishReasons.truncated(null)).isFalse();
    }
}
