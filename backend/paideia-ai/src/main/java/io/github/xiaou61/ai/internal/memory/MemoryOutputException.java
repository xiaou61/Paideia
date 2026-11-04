package io.github.xiaou61.ai.internal.memory;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;

/**
 * 记忆整理模型输出不合规（WORK-069）。
 *
 * <p>单独一个类型是为了让 {@link MemoryFailureClassifier} 不必靠异常消息猜处置：
 * 空内容、坏 JSON、字段缺失都属于「同配置再来一次也不会变好」，应直接失败而不是继续退避。
 * 对外错误码仍是 {@link ErrorCode#INTERNAL}，与改造前一致。
 */
final class MemoryOutputException extends BizException {

    MemoryOutputException(String message) {
        super(ErrorCode.INTERNAL, message);
    }
}
