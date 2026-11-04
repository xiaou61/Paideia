package io.github.xiaou61.ai.internal.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.FailureKind;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 记忆失败处置分类（WORK-069 AC-002）。 */
class MemoryFailureClassifierTest {

    @Test
    @DisplayName("模型输出不合规是 bad_output")
    void outputFailureIsBadOutput() {
        assertThat(MemoryFailureClassifier.classify(new MemoryOutputException("坏 JSON")))
                .isEqualTo(FailureKind.BAD_OUTPUT);
    }

    @Test
    @DisplayName("上游拒绝凭据是 needs_human")
    void invalidCredentialIsNeedsHuman() {
        assertThat(MemoryFailureClassifier.classify(
                new BizException(ErrorCode.INVALID_ARGUMENT, "上游拒绝了这组凭据")))
                .isEqualTo(FailureKind.NEEDS_HUMAN);
    }

    @Test
    @DisplayName("其它异常按 retryable：上游不可用、超时、线程池/数据库瞬时错误")
    void transientFailureIsRetryable() {
        assertThat(MemoryFailureClassifier.classify(new RuntimeException("timeout")))
                .isEqualTo(FailureKind.RETRYABLE);
        assertThat(MemoryFailureClassifier.classify(
                new BizException(ErrorCode.INTERNAL, "上游不可用")))
                .isEqualTo(FailureKind.RETRYABLE);
    }

    @Test
    @DisplayName("只有 retryable 可以自动重来")
    void onlyRetryableIsRetryable() {
        assertThat(FailureKind.RETRYABLE.isRetryable()).isTrue();
        assertThat(FailureKind.BAD_OUTPUT.isRetryable()).isFalse();
        assertThat(FailureKind.NEEDS_HUMAN.isRetryable()).isFalse();
    }
}
