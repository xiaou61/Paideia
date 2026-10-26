package io.github.xiaou61.ai.internal.provider;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.credential.AiProperties;
import org.springframework.stereotype.Component;

/**
 * 运维诊断的一次性调用（WORK-042）。
 *
 * <p>继承 {@link SingleShotModelCaller}：不挂工具、关闭原生结构化输出、空内容按失败。
 * 超时给 120 秒——诊断输入比摘要长，且 DeepSeek 的思考 token 也计入耗时；
 * 30 秒的默认值会把正常的长报告判成"上游不可用"。
 */
@Component
public class OpsDiagnosisModelCaller extends SingleShotModelCaller {

    private static final int MAX_TOKENS = 4096;
    private static final int TIMEOUT_SECONDS = 120;

    OpsDiagnosisModelCaller(AiProperties properties, GuardedHttpTransport transport) {
        super(properties, transport);
    }

    /** 供 {@code DefaultOpsDiagnoser} 使用的公开结果；基类的 {@code ModelReply} 是受保护类型。 */
    public record Reply(String text, TokenUsage usage) {
    }

    public Reply diagnose(ModelAccess access, String system, String user) {
        ModelReply reply = call(system, user, access, MAX_TOKENS, TIMEOUT_SECONDS);
        return new Reply(reply.text(), reply.usage());
    }
}
