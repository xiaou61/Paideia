package io.github.xiaou61.ai.internal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.AssistantConversation;
import io.github.xiaou61.ai.RunKind;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.github.xiaou61.ai.Harness;
import io.github.xiaou61.ai.NoteDraft;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.conversation.AttachmentUris;
import io.github.xiaou61.ai.internal.conversation.ConversationService;
import io.github.xiaou61.ai.internal.conversation.HarnessPrompts;
import io.github.xiaou61.ai.internal.memory.MemoryExtractionService;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.github.xiaou61.platform.ApiAccess;

/**
 * 助手对话端点。
 *
 * <p><b>三个动作分开</b>：建会话、列会话是普通 JSON；只有"发一轮消息"是 SSE，
 * 因为只有它会持续产生增量。
 *
 * <p><b>所有校验都在开流之前同步做完</b>（归属、空消息、凭据缺失）。SSE 一旦开始就无法再改
 * HTTP 状态码——把 403/404 混进事件流里，调用方要解析事件才知道失败，那不叫 REST。
 * 开流之后只剩"模型侧失败"，那才是事件。
 *
 * <p><b>事件负载一律是 JSON</b>：SSE 的 {@code data:} 行不能含裸换行，正文里必然有。
 * 前端把每个事件 parse 一次即可，不必自己处理转义。
 *
 * <p>事件：{@code thinking}（思考增量）、{@code content}（正文增量）、
 * {@code done}（带用量，可能为 null）、{@code error}（带可见消息）。{@code done} 与 {@code error} 互斥且各一次。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/ai/conversations")
class AssistantConversationController {

    /** 建会话。`entryId` 可空——助手在任意页都能开，不非得绑在某个条目上。 */
    /**
     * @param harness 这段会话用哪套公共行为（WORK-010）。不传 = 通用助手；
     *                面试页传 `interview`。**会话建好之后行为就跟着会话走**（恢复时按它选）。
     */
    record CreateRequest(Long entryId, String harness) {
    }

    /**
     * @param pageContext 页面场景（WORK-035 T-15）：前端已截断的一段文本。
     *                    **只进提示词，不落库**；`null` 表示当前页面没有场景。
     */
    record MessageRequest(String message, String pageContext) {
    }

    /**
     * 只有思考、正文为空时的可见提示。
     *
     * <p>这是 DeepSeek 那个坑的界面侧兜底：思考 token 吃满 {@code maxTokens} 时上游**静默返回空正文**。
     * 显示一个空气泡会让人以为助手坏了，说清楚原因才有用。
     */
    static final String EMPTY_CONTENT_HINT =
            "这次只产生了思考过程，没有生成正文。可以重试，或在设置里换一个 max_tokens 更宽裕的模型。";

    private final ConversationService conversations;
    private final AssistantConversation assistant;
    private final TaskExecutor executor;
    private final CreditService credits;
    private final MemoryExtractionService memoryExtraction;
    private final RunRecorder recorder;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * `TraceIdFilter` 写进 MDC 的键（WORK-022）。这里只读不写：HTTP 侧的追踪标识由过滤器管，
     * 轮次标识是另一个东西，两者互不覆盖。
     */
    private static final String TRACE_MDC_KEY = "traceId";

    /**
     * 客户端断开（用户点停止 / 关页面）的专用异常。
     *
     * <p>**不能靠错误码或文案区分**：上游故障与它同为 {@code INTERNAL}，而错误消息里
     * 完全可能出现同样的字样（它会被包进 cause 链）。一个子类就是最省事的可靠判据。
     */
    static final class ClientGoneException extends BizException {
        ClientGoneException() {
            super(ErrorCode.INTERNAL, CLIENT_GONE);
        }
    }

    /** 客户端断开的可见文案（也是唯一会写进 SSE error 事件的这句话）。 */
    static final String CLIENT_GONE = "连接已断开";

    AssistantConversationController(ConversationService conversations,
                                    AssistantConversation assistant,
                                    @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                                    CreditService credits,
                                    MemoryExtractionService memoryExtraction,
                                    RunRecorder recorder) {
        this.conversations = conversations;
        this.assistant = assistant;
        this.executor = executor;
        this.credits = credits;
        this.memoryExtraction = memoryExtraction;
        this.recorder = recorder;
    }

    @PostMapping
    ApiResponse<ConversationService.ConversationView> create(@RequestBody(required = false) CreateRequest request) {
        long userId = AiRequester.require();
        Long entryId = request == null ? null : request.entryId();
        Harness harness = Harness.ofOrDefault(request == null ? null : request.harness());
        long id = conversations.create(userId, entryId, harness);
        return ApiResponse.ok(new ConversationService.ConversationView(id, entryId, null));
    }

    @GetMapping
    ApiResponse<List<ConversationService.ConversationView>> list() {
        return ApiResponse.ok(conversations.list(AiRequester.require()));
    }

    /**
     * 读回某会话的消息（旧 → 新），供界面恢复历史（WORK-011）。
     *
     * <p>归属校验在服务层按 SQL 做：不存在 / 不是本人的 / 已过期**都是同一条 404**——
     * 区分开就等于确认"某个会话存在"。条数受库里保留上限约束，不无限拉。
     */
    @GetMapping("/{id}/messages")
    ApiResponse<List<ConversationService.MessageView>> messages(@PathVariable("id") long conversationId) {
        return ApiResponse.ok(conversations.readMessages(AiRequester.require(), conversationId));
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter send(@PathVariable("id") long conversationId, @RequestBody MessageRequest request) {
        long userId = AiRequester.require();
        // 同步阶段：这里抛出的错误会走全局异常处理，得到正常的 400/404 状态码与 ApiResponse 体
        ConversationService.TurnPreparation turn =
                conversations.prepare(userId, conversationId,
                        request == null ? null : request.message(),
                        request == null ? null : request.pageContext());

        SseEmitter emitter = new SseEmitter((long) 100_000);
        // **begin 在提交线程池之前**：这样 started_at 就是"用户按下发送"的时刻，
        // 排队时间能从 runTurn 的首个时刻减出来（WORK-022 REQ-011）。
        RunRecorder.RunScope run = runFor(userId, conversationId, turn);
        try {
            executor.execute(() -> runTurn(userId, conversationId, turn, emitter, run));
        } catch (RejectedExecutionException rejected) {
            // 线程池的队列也满了（2026-10-19 起队列有界）：任务根本没跑起来，这笔预留到不了
            // settleOnce、也没有别的补偿出口，用户会被白扣一次分。全额退还。
            //
            // **必须在这里换成 429**：原来的 `throw cause` 会把 RejectedExecutionException
            // 兜成 500，界面上是一句"服务器内部错误"，而真实情况是"现在太忙，等一下再来"。
            settleOnce(new boolean[1], userId, turn, null, null);
            run.finish(RunOutcome.FAILED, "REJECTED");
            run.close();
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "当前对话请求较多，请稍后再试");
        } catch (RuntimeException cause) {
            settleOnce(new boolean[1], userId, turn, null, null);
            run.finish(RunOutcome.FAILED, "DISPATCH_FAILED");
            run.close();
            throw cause;
        }
        return emitter;
    }

    /**
     * 起一轮的观测作用域（WORK-022）。
     *
     * <p>上下文规模在这里就已知（`prepare` 已经把历史与记忆取出来了），所以一并带上——
     * "这条回答为什么不好"经常就是"上下文给少了"，而那是事后查不出来的。
     */
    private RunRecorder.RunScope runFor(long userId, long conversationId,
                                        ConversationService.TurnPreparation turn) {
        RunSpec spec = RunSpec.of(RunKind.CHAT, userId)
                .withConversation(conversationId)
                .withHarness(turn.harness().key())
                .withModel(turn.access().model())
                .withPromptVersion(HarnessPrompts.version(turn.harness()));
        RunRecorder.RunScope run = recorder.begin(spec);
        run.attribute("historyMsgs", turn.history().size());
        run.attribute("memoryHits", turn.memorySnippets().size());
        run.attribute("entryChars", turn.entryBody() == null ? 0 : turn.entryBody().length());
        run.attribute("learningSummaryChars", turn.learningSummary() == null ? 0 : turn.learningSummary().length());
        // 这一轮带了哪些对象（WORK-058）：排查"它为什么答错"时要能看到它当时手里有什么
        java.util.List<String> contextUris = AttachmentUris.of(turn.pageContext());
        if (!contextUris.isEmpty()) {
            run.attribute("contextUris", contextUris);
        }
        run.attribute("reqChars", turn.userMessage() == null ? 0 : turn.userMessage().length());
        run.attribute("httpTraceId", MDC.get(TRACE_MDC_KEY));
        return run;
    }

    /**
     * 在独立线程里消费上游流并推送事件。
     *
     * <p>助手消息**只在成功时落库**：失败轮次不写，也就不进后续上下文（失败的回答不该被记住）。
     * 用户那条消息在 {@code prepare} 里已经写了，所以失败不会丢掉用户说过的话。
     */
    private void runTurn(long userId, long conversationId,
                         ConversationService.TurnPreparation turn, SseEmitter emitter,
                         RunRecorder.RunScope run) {
        // 第一件事：记下"被线程池捡起来"的时刻。begin 到这里的差就是排队时间。
        run.markStarted();
        StringBuilder content = new StringBuilder();
        // 用量单独存一格：onUsage 先于结果回调，这样"失败也要为已烧掉的 token 负责"不必在每条错误路径上再判一次
        TokenUsage[] usageRef = new TokenUsage[1];
        // 结算只做一次的闸（理由见 settleOnce）
        boolean[] settled = {false};
        // 首字时刻只记一次：TTFT 是"用户等多久才看到第一个字"，记第二次就成了总耗时
        boolean[] ttftMarked = {false};
        try {
            assistant.stream(
                    new AssistantConversation.Request(userId, turn.history(), turn.userMessage(),
                            turn.entryTitle(), turn.entryBody(), turn.learningSummary(),
                            turn.memorySnippets(), turn.harness(), turn.pageContext()),
                    turn.access(),
                    run,
                    new AssistantConversation.Listener() {
                        @Override
                        public void onThinking(String delta) {
                            send(emitter, "thinking", Map.of("delta", delta));
                        }

                        @Override
                        public void onContent(String delta) {
                            if (!ttftMarked[0]) {
                                ttftMarked[0] = true;
                                run.markTtft();
                            }
                            content.append(delta);
                            send(emitter, "content", Map.of("delta", delta));
                        }

                        @Override
                        public void onDraft(NoteDraft draft) {
                            // 草稿走独立事件：前端据此显示"要不要存成笔记"的确认条，
                            // 而不是把它当正文的一部分。落库要等用户点击（另一个端点）。
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("title", draft.title());
                            payload.put("body", draft.body());
                            send(emitter, "draft", payload);
                        }

                        @Override
                        public void onUsage(TokenUsage usage) {
                            // 只赋值，不做任何可能抛异常的 I/O：它先于 onCompleted/onFailed 被调用，
                            // 这里抛出去会让用户既拿不到结果、也拿不到错误。
                            usageRef[0] = usage;
                        }

                        @Override
                        public void onCompleted() {
                            TokenUsage usage = usageRef[0];
                            boolean noContent = content.toString().isBlank();
                            String text = noContent ? EMPTY_CONTENT_HINT : content.toString();
                            if (noContent) {
                                // 提示也要作为正文增量发出去：只发 done 的话前端会留一个空气泡，
                                // 用户看到的是"助手什么都没说"，而不是"这次没产出正文"
                                send(emitter, "content", Map.of("delta", EMPTY_CONTENT_HINT));
                            }
                            // 先落消息（拿到 id）、再按这个 id 结算：流水的 ref_message_id 能对上那条消息，
                            // "逐条可对账"（AC-006）才有依据
                            long messageId = conversations.complete(userId, conversationId, text, usage);
                            int charged = settleOnce(settled, userId, turn, usage, messageId);
                            run.tokens(usage == null ? 0 : usage.inputTokens(),
                                    usage == null ? 0 : usage.outputTokens(),
                                    usage == null ? 0 : usage.cacheReadTokens());
                            run.addCredits(charged);
                            run.attribute("respChars", text.length());
                            run.finish(RunOutcome.OK, null);
                            // empty=true 告诉前端"这段是提示，不是模型生成的正文"，免得把它当成回答渲染。
                            // 用 HashMap 而不是 Map.of：用量允许为 null，而 Map.of 见到 null 直接抛。
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("usage", usagePayload(usage));
                            payload.put("empty", noContent);
                            // 这一轮的标识（WORK-036）：界面用它把"点赞/点踩"挂到具体某一轮上。
                            // 采集关闭时为 null——反馈仍可提交，只是挂不上去。
                            payload.put("traceId", run.traceId());
                            send(emitter, "done", payload);
                            emitter.complete();
                            // 自动整理记忆的**触发点**（WORK-013）：刻意放在 emitter.complete()
                            // **之后**——用户这时已经拿到完整回答，这一步慢或失败都与他的体验无关。
                            // 它自己只做一次极轻的判断；真干活的部分由它内部丢到另一个线程。
                            memoryExtraction.scheduleIfDue(userId, conversationId);
                        }

                        @Override
                        public void onFailed(BizException failure) {
                            // 失败**也要过账**：用量拿到了就按用量扣，没拿到就全额退回。
                            // "失败不落库"只管消息，钱那一步必须显式处理。
                            int charged = settleOnce(settled, userId, turn, usageRef[0], null);
                            run.tokens(usageRef[0] == null ? 0 : usageRef[0].inputTokens(),
                                    usageRef[0] == null ? 0 : usageRef[0].outputTokens(), 0);
                            run.addCredits(charged);
                            // 先落终态再往事件流写：写事件对已断开的客户端会抛
                            RunOutcome outcome = outcomeOf(failure);
                            run.finish(outcome, errorTypeOf(failure, outcome));
                            send(emitter, "error", Map.of("message", failure.getMessage() == null
                                    ? "生成失败，请稍后再试" : failure.getMessage()));
                            emitter.complete();
                        }
                    });
        } catch (RuntimeException unexpected) {
            // 端口约定失败走 onFailed；真出了别的运行时异常也不能让线程静默死掉（客户端会一直等）
            settleOnce(settled, userId, turn, null, null);
            run.finish(RunOutcome.FAILED, "unexpected");
            send(emitter, "error", Map.of("message", "生成失败，请稍后再试"));
            emitter.complete();
        } finally {
            // 幂等：finish 已经调过时这里是空操作；异常路径上它把这一轮兜成 interrupted
            run.close();
        }
    }

    /**
     * 结算或退还这一轮的预留，**且只做一次**。
     *
     * <p>为什么要有"只做一次"的闸：`onCompleted` 里先结算、再往 SSE 写 `done`，而写事件可能抛
     * （客户端断开）。那会落到外层的 `catch`，于是同一次调用被退两遍——那是白送钱。
     * 用一个布尔闸比"分析每条路径有没有重叠"可靠。
     *
     * <p><b>拿不到用量就全额退</b>：没有用量就没有扣费依据。宁可漏扣（审计页上"有用量但 0 扣减"
     * 一眼可见），也不要凭猜扣用户的分。
     */
    private int settleOnce(boolean[] settled, long userId, ConversationService.TurnPreparation turn,
                           TokenUsage usage, Long messageId) {
        if (settled[0]) {
            return 0;
        }
        settled[0] = true;
        if (usage == null) {
            // 全额退回：这一次实扣是 0，统计里记 0 而不是"预留值"——预留不是花费
            credits.refund(userId, turn.reservedCredits(), messageId, "上游未返回用量");
            return 0;
        }
        return credits.settle(userId, turn.reservedCredits(), turn.access().model(),
                usage.inputTokens(), usage.outputTokens(), messageId);
    }

    /** 失败原因归一成低基数标识。**不写异常消息**：它可能含上游地址或响应片段。 */
    /**
     * 这一轮怎么结束的：用户放弃、上游超时、还是真的失败。
     *
     * <p>三者在库里必须分开——「用户主动停止」是**最诚实的"这次回答不行"信号**，
     * 把它和"上游挂了"混成一个 failed，就再也分不出"我们的回答被嫌弃"和"服务出故障"。
     *
     * <p>沿 cause 链找，因为上游失败会把下层异常包一层再上报：
     * {@code send()} 抛的 {@link ClientGoneException} 会以
     * {@code upstreamUnavailable(...).initCause(...)} 的形状到这里。
     */
    static RunOutcome outcomeOf(BizException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClientGoneException) {
                return RunOutcome.CANCELLED;
            }
            if (cause instanceof TimeoutException) {
                return RunOutcome.TIMEOUT;
            }
        }
        return RunOutcome.FAILED;
    }

    /** 失败原因归一成低基数标识。**不写异常消息**：它可能含上游地址或响应片段。 */
    static String errorTypeOf(BizException failure, RunOutcome outcome) {
        return switch (outcome) {
            case CANCELLED -> "client_gone";
            case TIMEOUT -> "upstream_timeout";
            default -> failure.errorCode() == null ? "unknown" : failure.errorCode().name();
        };
    }

    private static Map<String, Object> usagePayload(TokenUsage usage) {
        return usage == null
                ? null
                : Map.of("input", usage.inputTokens(), "output", usage.outputTokens(), "total", usage.totalTokens());
    }

    /** 客户端断开是常态（用户关页面），不该把线程炸掉；写失败就当作这轮结束。 */
    private void send(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(json.writeValueAsString(payload)));
        } catch (IOException | IllegalStateException exception) {
            throw new ClientGoneException();
        }
    }
}
