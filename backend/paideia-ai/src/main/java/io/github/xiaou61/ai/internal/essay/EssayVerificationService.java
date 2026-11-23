package io.github.xiaou61.ai.internal.essay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.FailureKind;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.RunKind;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 面经答案的「内部自检 → 分流 → 落记录」（WORK-084 TASK-004）。
 *
 * <p>状态机（本期只做内部自检，不接外部检索）：
 * <ul>
 *   <li>自检 ok 且置信 ≥ accept → {@code auto_passed}（直接入库）。</li>
 *   <li>其余（含判定存疑、异常、调用预算用尽）→ {@code pending_review}（不入库，留档待人工）。</li>
 * </ul>
 *
 * <p><b>异常一律不冒泡到抓取任务</b>：校验挂了不能把整批入库拖垮，转人工即可。
 */
@Service
public class EssayVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EssayVerificationService.class);

    static final String AUTO_PASSED = "auto_passed";
    static final String REVISED = "revised";
    static final String PENDING_REVIEW = "pending_review";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EssayProperties properties;
    private final EssayVerifier verifier;
    private final EssayVerificationMapper mapper;
    private final RunRecorder recorder;

    /** 本轮已用的模型调用数；每次抓取开始时 {@link #beginRun()} 清零。 */
    private int callsThisRun;

    EssayVerificationService(EssayProperties properties,
                             EssayVerifier verifier,
                             EssayVerificationMapper mapper,
                             RunRecorder recorder) {
        this.properties = properties;
        this.verifier = verifier;
        this.mapper = mapper;
        this.recorder = recorder;
    }

    /** 每次 runOnce 开始时调用，重置本轮调用预算。 */
    public void beginRun() {
        callsThisRun = 0;
    }

    public VerificationOutcome judge(String title, String answer, ModelAccess access, Long categoryId,
                                     String fingerprint, String sourceUrl, String sourceTitle) {
        EssayProperties.Verify verify = properties.getVerify();
        String status;
        String finalAnswer = null;
        double confidence = 0.0;
        List<String> issues = List.of();
        String promptVersion = EssayVerificationPrompt.VERIFY_VERSION;
        try {
            if (callsThisRun >= verify.getMaxCallsPerRun()) {
                status = PENDING_REVIEW;
                issues = List.of("budget_exhausted");
            } else {
                FactCheck check = runVerify(title, answer, access);
                confidence = check.confidence();
                issues = check.issues();
                if ("ok".equals(check.verdict()) && confidence >= verify.getAcceptConfidence()) {
                    status = AUTO_PASSED;
                } else {
                    // 本期只做内部自检：不在无外部依据时改写答案，存疑一律隔离待人工。
                    status = PENDING_REVIEW;
                }
            }
        } catch (RuntimeException exception) {
            status = PENDING_REVIEW;
            issues = List.of("verify_error: " + (exception.getMessage() == null ? "" : exception.getMessage()));
        }
        VerificationOutcome outcome = new VerificationOutcome(
                status, finalAnswer, confidence, issues, List.of(), promptVersion, access.model());
        persist(outcome, title, answer, categoryId, fingerprint, sourceUrl, sourceTitle);
        return outcome;
    }

    private FactCheck runVerify(String title, String answer, ModelAccess access) {
        callsThisRun++;
        var scope = recorder.begin(RunSpec.of(RunKind.ESSAY_VERIFY, 0L)
                .withModel(access.model())
                .withPromptVersion(EssayVerificationPrompt.VERIFY_VERSION));
        try {
            FactCheck check = verifier.checkFactual(title, answer, access);
            scope.finish(RunOutcome.OK, null);
            return check;
        } catch (RuntimeException exception) {
            scope.finish(RunOutcome.FAILED, "essay_verify_failed", FailureKind.BAD_OUTPUT);
            throw exception;
        } finally {
            scope.close();
        }
    }

    private Revision runRevise(String title, String draft, List<String> issues, ModelAccess access) {
        callsThisRun++;
        var scope = recorder.begin(RunSpec.of(RunKind.ESSAY_REVISE, 0L)
                .withModel(access.model())
                .withPromptVersion(EssayVerificationPrompt.REVISE_VERSION));
        try {
            Revision revision = verifier.revise(title, draft, issues, List.of(), access);
            scope.finish(RunOutcome.OK, null);
            return revision;
        } catch (RuntimeException exception) {
            scope.finish(RunOutcome.FAILED, "essay_revise_failed", FailureKind.BAD_OUTPUT);
            throw exception;
        } finally {
            scope.close();
        }
    }

    private void persist(VerificationOutcome outcome, String title, String answer, Long categoryId,
                         String fingerprint, String sourceUrl, String sourceTitle) {
        EssayVerificationRow row = new EssayVerificationRow();
        row.setStatus(outcome.status());
        row.setQuestionTitle(title);
        row.setDraftAnswer(answer);
        row.setFinalAnswer(outcome.finalAnswer());
        row.setConfidence(BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, outcome.confidence()))));
        row.setIssuesJson(toJson(outcome.issues()));
        row.setCategoryId(categoryId);
        row.setFingerprint(fingerprint);
        row.setSourceUrl(sourceUrl);
        row.setSourceTitle(sourceTitle);
        row.setPromptVersion(outcome.promptVersion());
        row.setModel(outcome.model());
        try {
            mapper.insert(row);
        } catch (RuntimeException exception) {
            // 记录写失败不该影响主流程：最坏是这条没留下复核痕迹，正文仍按判定结果处理。
            // **必须留日志**：被拦下的题本来就靠这张表等人工，落库失败等于永久丢失，
            // 静默吞掉会让「今天一条待复核都没有」看起来像正常结果。
            log.error("面经验证记录落库失败（status={} title={}）：{}",
                    row.getStatus(), row.getQuestionTitle(), exception.getMessage());
        }
    }

    private static List<String> merge(List<String> issues, String note) {
        if (note == null || note.isBlank()) {
            return issues;
        }
        List<String> merged = new java.util.ArrayList<>(issues);
        merged.add("revise_notes: " + note);
        return merged;
    }

    private static String toJson(List<String> values) {
        try {
            return JSON.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception exception) {
            return "[]";
        }
    }
}
