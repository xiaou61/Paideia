package io.github.xiaou61.ai.internal.interview;

import io.github.xiaou61.ai.InterviewEvaluation;
import io.github.xiaou61.ai.InterviewReviewer;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.conversation.ConversationService;
import io.github.xiaou61.ai.internal.conversation.ReviewPrompt;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.platform.BizException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 面试点评的编排（WORK-010 T-10/T-11）。
 *
 * <p>与"{@code harness=review} 的对话"是两件事，这里选了**独立端点**而不是复用 SSE：
 * <ul>
 *   <li>点评是**一次性结果**，没有逐步显示的需求；用 SSE 只会多一套"流式接收一个 JSON"的壳。</li>
 *   <li>它要**同步返回**评分给界面展示并落库；SSE 的异步回调反而让"落库时机"变成第二种时序。</li>
 * </ul>
 * 所以 `harness=review` 存在的意义在**行为层**（提示词与空工具集），不在传输层：
 * 这次调用与通用对话共用同一套"无工具 + 结构化输出"的纪律，但走普通的请求/响应。
 *
 * <p><b>计费走同一套预扣/结算</b>：点评是一次真实的上游调用，花的是用户的积分。
 * 余额不足时**不发起上游请求**，直接报 {@code INSUFFICIENT_CREDITS}（AC-010 的语义）。
 */
@Service
public class InterviewReviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewReviewService.class);

    /**
     * 送进点评的最大字符数。
     *
     * <p>一场不限时长的面试可能聊很久。**这里会截断**——但与摘要那边不同的是：截断的是
     * **最早的对话**（保留最近的部分），因为"后来答得怎么样"更能代表当前水平。
     *
     * <p>ponytail: 固定 12000 字。天花板是"聊得特别久的面试，前半场不参与评分"；升级路径是
     * "分段点评再汇总"，但那需要先有真实的长面试样本证明这不够用。
     */
    private static final int MAX_TRANSCRIPT_CHARS = 12_000;

    private final InterviewReviewer reviewer;
    private final CredentialService credentials;
    private final CreditService credits;
    private final ConversationService conversations;
    private final InterviewEvaluationMapper evaluations;
    private final io.github.xiaou61.ai.RunRecorder recorder;

    InterviewReviewService(InterviewReviewer reviewer,
                          CredentialService credentials,
                          CreditService credits,
                          ConversationService conversations,
                          InterviewEvaluationMapper evaluations,
                          io.github.xiaou61.ai.RunRecorder recorder) {
        this.reviewer = reviewer;
        this.credentials = credentials;
        this.credits = credits;
        this.conversations = conversations;
        this.evaluations = evaluations;
        this.recorder = recorder;
    }

    /** 界面用的评估视图。 */
    public record EvaluationView(long id, long conversationId, InterviewEvaluation evaluation,
                                 java.time.Instant createdAt) {
    }

    /**
     * 给某段会话生成评估并落库。
     *
     * <p>三步刻意分开：先把对话读出来（**归属校验在服务层按 SQL 做**），再预扣、再调用、
     * 最后结算并落库。任何一步失败都不会留下半条评估。
     *
     * @throws BizException 会话不存在/不是本人的（404）、余额不足（402）、上游不可用或结果不可解析（500）
     */
    public EvaluationView review(long userId, long conversationId) {
        // 归属校验：不存在 / 不是本人的 / 已过期都是同一条 404（与会话其它端点一致）
        List<ConversationService.MessageView> messages = conversations.readMessages(userId, conversationId);
        String transcript = render(messages);
        if (transcript.isBlank()) {
            throw new BizException(io.github.xiaou61.platform.ErrorCode.INVALID_ARGUMENT,
                    "这段对话还没有内容，无法生成点评");
        }

        Optional<ModelAccess> access = credentials.access(userId);
        if (access.isEmpty()) {
            throw new BizException(io.github.xiaou61.platform.ErrorCode.INVALID_ARGUMENT,
                    "平台尚未配置 AI 服务，请联系管理员");
        }

        // 预扣在调用之前：余额不足时一个上游请求都不会发出去（AC-010）
        long reserved = credits.reserve(userId, credits.estimate());
        TokenUsage usage = null;
        var run = recorder.begin(io.github.xiaou61.ai.RunSpec
                .of(io.github.xiaou61.ai.RunKind.INTERVIEW_REVIEW, userId)
                .withConversation(conversationId)
                .withModel(access.get().model())
                .withPromptVersion(ReviewPrompt.VERSION));
        try {
            run.attribute("reqChars", transcript.length());
            InterviewReviewer.GeneratedReview result = reviewer.review(transcript, access.get());
            usage = result.usage();
            long id = evaluations.insert(userId, conversationId, result.evaluation());
            int charged = credits.settleInterviewReview(userId, reserved, access.get().model(),
                    usage == null ? null : usage.inputTokens(),
                    usage == null ? null : usage.outputTokens(), id);
            run.tokens(usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(),
                    usage == null ? 0 : usage.cacheReadTokens());
            run.addCredits(charged);
            run.finish(io.github.xiaou61.ai.RunOutcome.OK, null);
            log.info("ai.interview.reviewed userId={} conversationId={} overall={}",
                    userId, conversationId, result.evaluation().overall());
            return new EvaluationView(id, conversationId, result.evaluation(), java.time.Instant.now());
        } catch (RuntimeException failure) {
            // 失败也要过账：拿到了用量就按用量扣，没拿到就全额退回（与对话/记忆那两条同一纪律）
            if (usage == null) {
                credits.refund(userId, reserved, null, "面试点评失败");
            } else {
                credits.settleInterviewReview(userId, reserved, access.get().model(),
                        usage.inputTokens(), usage.outputTokens(), null);
            }
            run.finish(io.github.xiaou61.ai.RunOutcome.FAILED,
                    failure instanceof io.github.xiaou61.platform.BizException biz && biz.errorCode() != null
                            ? biz.errorCode().name() : "unknown");
            throw failure;
        } finally {
            run.close();
        }
    }

    /** 本人的历次评估（新 → 旧）。"进度与弱项"要能查，所以有这个列表。 */
    public List<EvaluationView> listMine(long userId) {
        return evaluations.listRowsByUser(userId, 50).stream()
                .map(row -> new EvaluationView(row.getId(), row.getConversationId(), evaluations.toEvaluation(row), row.getCreatedAt()))
                .toList();
    }

    /**
     * 把消息渲染成"面试官：… / 候选人：…"的整段对话。
     *
     * <p>角色名用**这对词**而不是 user/assistant：点评提示词里说的是"面试官"与"候选人"，
     * 对齐之后模型更容易分清谁在问、谁在答。
     *
     * <p>超长时**保留最近的部分**（见 {@link #MAX_TRANSCRIPT_CHARS}）：截掉最早的那段，
     * 并明确在开头说一句"前文已省略"——否则模型会以为面试是从中间开始的。
     */
    private static String render(List<ConversationService.MessageView> messages) {
        StringBuilder builder = new StringBuilder();
        for (ConversationService.MessageView message : messages) {
            String speaker = "assistant".equals(message.role()) ? "面试官" : "候选人";
            builder.append(speaker).append("：").append(message.content()).append("\n\n");
        }
        String transcript = builder.toString().trim();
        if (transcript.length() <= MAX_TRANSCRIPT_CHARS) {
            return transcript;
        }
        String tail = transcript.substring(transcript.length() - MAX_TRANSCRIPT_CHARS);
        // 从下一个换行处开始，避免把一句话切成两半
        int newline = tail.indexOf('\n');
        if (newline >= 0) {
            tail = tail.substring(newline + 1);
        }
        return "（面试开头已省略，以下是最近的部分）\n\n" + tail;
    }
}
