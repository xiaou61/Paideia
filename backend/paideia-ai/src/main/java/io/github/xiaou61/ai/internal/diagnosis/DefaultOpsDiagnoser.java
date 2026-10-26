package io.github.xiaou61.ai.internal.diagnosis;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.OpsDiagnoser;
import io.github.xiaou61.ai.RunKind;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.provider.OpsDiagnosisModelCaller;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 运维诊断的编排（WORK-042 REQ-011/REQ-012）。
 *
 * <p>职责只有四件：拿凭据、预留积分、调一次模型、结算并写遥测。证据的采集不在这里。
 *
 * <p><b>失败一定退款</b>：上游失败、空内容、结算抛异常都走同一条 catch；否则"诊断失败还扣钱"
 * 会比诊断本身更难解释。余额不足由 `reserve` 在**发起任何上游请求之前**抛出。
 */
@Service
class DefaultOpsDiagnoser implements OpsDiagnoser {

    private static final Logger log = LoggerFactory.getLogger(DefaultOpsDiagnoser.class);

    private final CredentialService credentials;
    private final CreditService credits;
    private final RunRecorder recorder;
    private final OpsDiagnosisModelCaller caller;

    DefaultOpsDiagnoser(CredentialService credentials,
                        CreditService credits,
                        RunRecorder recorder,
                        OpsDiagnosisModelCaller caller) {
        this.credentials = credentials;
        this.credits = credits;
        this.recorder = recorder;
        this.caller = caller;
    }

    @Override
    public Diagnosis diagnose(long userId, Evidence evidence) {
        ModelAccess access = credentials.access(userId).orElseThrow(
                () -> new BizException(ErrorCode.INTERNAL, "平台尚未配置 AI 服务，请联系管理员"));

        long reserved = credits.reserve(userId, credits.estimate());
        RunRecorder.RunScope run = recorder.begin(RunSpec.of(RunKind.OPS_DIAGNOSIS, userId)
                .withModel(access.model())
                .withPromptVersion(OpsDiagnosisPrompt.VERSION));
        try {
            OpsDiagnosisModelCaller.Reply reply = caller.diagnose(
                    access, OpsDiagnosisPrompt.system(), OpsDiagnosisPrompt.user(evidence));
            TokenUsage usage = reply.usage();
            int charged = credits.settleOpsDiagnosis(userId, reserved, access.model(),
                    usage == null ? null : usage.inputTokens(),
                    usage == null ? null : usage.outputTokens());
            if (usage != null) {
                run.tokens(usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens());
            }
            run.addCredits(charged);
            run.finish(RunOutcome.OK, null);
            return new Diagnosis(reply.text(), usage, access.model());
        } catch (RuntimeException failure) {
            credits.refund(userId, reserved, null, "运维诊断失败");
            run.finish(RunOutcome.FAILED, errorType(failure));
            log.warn("ops.diagnosis.failed userId={} trigger={} error={}",
                    userId, evidence.trigger(), errorType(failure));
            throw failure;
        } finally {
            run.close();
        }
    }

    private static String errorType(RuntimeException failure) {
        return failure instanceof BizException biz
                ? String.valueOf(biz.errorCode().code())
                : "internal";
    }
}
