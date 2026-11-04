package io.github.xiaou61.ai.internal.web;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.provider.QuickInsightCaller;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 五个"点一下、给一小段"的 AI 入口（WORK-067）。
 *
 * <p>共同纪律：
 * <ul>
 *   <li><b>都要钱</b>：每次先 reserve 再按真实用量 settle，失败全额退——与对话、出题同一条口径；</li>
 *   <li><b>都不落库</b>：结果只回给这一次点击，不给页面加"历史"（那会变成第二套内容系统）；</li>
 *   <li><b>都只收页面已有的文本</b>：不按 id 读库，避免为这五个动作再开一套跨模块查询。</li>
 * </ul>
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/ai")
class QuickInsightController {

    private static final int MAX_STEM = 1_000;
    private static final int MAX_BODY = 8_000;
    private static final int MAX_SECTION = 2_000;

    record ExplainRequest(String stem, List<String> options, Integer choice, Integer answer, String explain) {
    }

    record NextStepRequest(String lines, String failing, String dueReviews, String weakConcepts) {
    }

    record ConceptRequest(String name, String summary) {
    }

    record NoteRequest(String title, String body) {
    }

    record QuizRequest(String title, String body) {
    }

    record TextReply(String text) {
    }

    record StepsReply(List<String> steps) {
    }

    record PointsReply(List<String> points) {
    }

    record QuizReply(String question, List<String> options, int answer, String explain) {
    }

    private final QuickInsightCaller caller;
    private final CredentialService credentials;
    private final CreditService credits;

    QuickInsightController(QuickInsightCaller caller, CredentialService credentials, CreditService credits) {
        this.caller = caller;
        this.credentials = credentials;
        this.credits = credits;
    }

    /** 讲解一道做错的题：客户端把题面与选项一起带来，服务端不查题库。 */
    @PostMapping("/explain/review-item")
    ApiResponse<TextReply> explainReviewItem(@RequestBody ExplainRequest body) {
        String stem = text(body.stem(), MAX_STEM, "题干");
        List<String> options = body.options();
        if (options == null || options.size() < 2 || options.size() > 6) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "选项数量必须是 2–6 个");
        }
        List<String> cleaned = options.stream().map(option -> text(option, 200, "选项")).toList();
        int choice = index(body.choice(), cleaned.size(), "choice");
        int answer = index(body.answer(), cleaned.size(), "answer");
        if (choice == answer) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "选的已经是正确答案，不需要讲解");
        }
        String explain = body.explain() == null ? null : text(body.explain(), MAX_STEM, "解析");
        return ApiResponse.ok(new TextReply(billed(access -> caller.explainReviewItem(
                stem, cleaned, choice, answer, explain, access))));
    }

    /** 「下一步做什么」：输入是页面已经算好的四块摘要，服务端只改写成人话。 */
    @PostMapping("/next-step")
    ApiResponse<StepsReply> nextStep(@RequestBody NextStepRequest body) {
        String lines = text(body.lines(), MAX_SECTION, "各线进度");
        String failing = optional(body.failing(), MAX_SECTION, "卡住的关卡");
        String dueReviews = optional(body.dueReviews(), MAX_SECTION, "到期的复习");
        String weakConcepts = optional(body.weakConcepts(), MAX_SECTION, "最薄弱的概念");
        return ApiResponse.ok(new StepsReply(billed(access -> caller.nextSteps(
                lines, failing, dueReviews, weakConcepts, access))));
    }

    /** 一个概念的人话解释 + 最小例子。 */
    @PostMapping("/concepts/explain")
    ApiResponse<TextReply> explainConcept(@RequestBody ConceptRequest body) {
        String name = text(body.name(), 100, "概念名");
        String summary = optional(body.summary(), MAX_STEM, "摘要");
        return ApiResponse.ok(new TextReply(billed(access -> caller.explainConcept(name, summary, access))));
    }

    /** 笔记要点：只提炼，不改原文。 */
    @PostMapping("/notes/points")
    ApiResponse<PointsReply> notePoints(@RequestBody NoteRequest body) {
        String title = optional(body.title(), 200, "标题");
        String content = text(body.body(), MAX_BODY, "笔记正文");
        return ApiResponse.ok(new PointsReply(billed(access -> caller.notePoints(title, content, access))));
    }

    /** 按条目正文出一道单选题：题面与选项都来自这一条内容。 */
    @PostMapping("/quiz")
    ApiResponse<QuizReply> quiz(@RequestBody QuizRequest body) {
        String title = optional(body.title(), 200, "标题");
        String content = text(body.body(), MAX_BODY, "正文");
        QuickInsightCaller.QuizView quiz = billed(access -> caller.quiz(title, content, access));
        return ApiResponse.ok(new QuizReply(quiz.question(), quiz.options(), quiz.answer(), quiz.explain()));
    }

    /**
     * 一次计费调用的共同外壳。
     *
     * <p>失败时**先退款再抛**：模型超时、格式不对都会走到这里，用户不该为一次没拿到结果
     * 的调用付钱（与对话、出题同一口径）。
     */
    private <T> T billed(Function<ModelAccess, QuickInsightCaller.Result<T>> work) {
        Optional<ModelAccess> access = credentials.access(0);
        if (access.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "平台尚未配置 AI 服务，请联系管理员");
        }
        long userId = CurrentUser.userId();
        long reserved = credits.reserve(userId, credits.estimate());
        try {
            QuickInsightCaller.Result<T> result = work.apply(access.get());
            credits.settle(userId, reserved, access.get().model(),
                    result.usage().inputTokens(), result.usage().outputTokens(), null);
            return result.value();
        } catch (RuntimeException failure) {
            credits.refund(userId, reserved, null, "AI 调用失败");
            throw failure;
        }
    }

    private static String text(String value, int max, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, field + "不能为空");
        }
        if (trimmed.length() > max) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, field + "超过 " + max + " 字");
        }
        return trimmed;
    }

    private static String optional(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return text(value, max, field);
    }

    private static int index(Integer value, int size, String field) {
        if (value == null || value < 0 || value >= size) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, field + " 必须是 0–" + (size - 1) + " 的下标");
        }
        return value;
    }
}
