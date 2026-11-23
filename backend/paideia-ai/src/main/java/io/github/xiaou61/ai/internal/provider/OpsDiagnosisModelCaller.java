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

    /**
     * 4096 曾经够用，但 WORK-086 把证据撑长之后不够了：生产实测（2026-11-19 16:57）
     * 注入 `<knowledge>` 的两次诊断连续以 {@code 模型输出被长度上限截断} 失败，
     * 而同一窗口、同一模型的前一次成功诊断已经用掉 3748 个输出 token——离上限只剩 350。
     * 截断是整份报告作废，代价远大于多给几千 token，所以翻倍到 8192。
     */
    private static final int MAX_TOKENS = 8192;

    /**
     * 与 {@link #MAX_TOKENS} 一起抬到 300 秒：8192 个输出 token 在实测速率下要 100 秒以上
     * （2026-11-19 那次成功诊断 3748 token），120 秒的窗口会把"输出更长"变成超时失败——
     * 失败形态从「被截断」换成「超时」，报告照样作废。
     */
    private static final int TIMEOUT_SECONDS = 300;

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
