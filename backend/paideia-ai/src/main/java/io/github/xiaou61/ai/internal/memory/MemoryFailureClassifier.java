package io.github.xiaou61.ai.internal.memory;

import io.github.xiaou61.ai.FailureKind;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;

/**
 * 记忆整理失败的处置分类（WORK-069）。
 *
 * <p>三条口径：
 * <ul>
 *   <li>模型输出/解析不合规 → {@link FailureKind#BAD_OUTPUT}；</li>
 *   <li>凭据被上游拒绝（需要管理员改配置）→ {@link FailureKind#NEEDS_HUMAN}；</li>
 *   <li>其它临时异常（上游不可用、超时、线程池/数据库瞬时错误）→ {@link FailureKind#RETRYABLE}。</li>
 * </ul>
 *
 * <p>不要按异常消息做关键字匹配：消息是给人看的，随时会改；分类只依赖类型与稳定错误码。
 */
final class MemoryFailureClassifier {

    private MemoryFailureClassifier() {
    }

    static FailureKind classify(Throwable failure) {
        if (failure instanceof MemoryOutputException) {
            return FailureKind.BAD_OUTPUT;
        }
        if (failure instanceof BizException biz && biz.errorCode() == ErrorCode.INVALID_ARGUMENT) {
            return FailureKind.NEEDS_HUMAN;
        }
        return FailureKind.RETRYABLE;
    }
}
