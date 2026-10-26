package io.github.xiaou61.ai.internal.conversation;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.github.xiaou61.ai.AssistantConversation;
import io.github.xiaou61.ai.ConversationTurn;
import io.github.xiaou61.ai.Harness;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.SpanSpec;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.provider.GuardedHttpTransport;
import io.github.xiaou61.ai.internal.tools.AgentToolBridge;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 用 AgentScope 的 {@link ReActAgent} 承载多轮对话（流式）。
 *
 * <p><b>WORK-031 第二步</b>：工具循环由框架驱动，我们不再自己写 {@code for (round...)}。
 * 原先那三处实测算出来的补丁现在这样处理：
 *
 * <ol>
 *   <li><b>最终轮才推正文</b>——仍然由本类自己保证。工具轮的旁白会进历史但不推给用户；
 *       判断依据是"这一轮有没有发出工具调用"，只有没有的那一轮才把累积正文推出去。</li>
 *   <li><b>每轮各一条 tool 结果</b>——框架按协议处理，不再需要我们拼。</li>
 *   <li><b>思考不进历史</b>——思考只走 {@link Listener#onThinking(String)}，从不拼回消息。</li>
 * </ol>
 *
 * <p><b>三条实测约束决定了这里的写法</b>（2026-10-21 尖刺，见
 * {@code .agent/references/agentscope-and-deepseek-2026-10.md} 第三节）：
 *
 * <ul>
 *   <li><b>每次请求新建 Agent</b>：复用实例时框架会自己留下上一轮对话，而历史在我们库里，
 *       复用会让历史发两遍。</li>
 *   <li><b>提示词必须合成一条 `sysPrompt`</b>：`inputMessages` 里出现 SYSTEM 会抛
 *       {@code IllegalStateException}，而条目上下文/记忆/学情本来都挂在 SYSTEM 上
 *       （放用户侧会被模型当成用户说的话）。这里按原顺序拼成一段。</li>
 *   <li><b>工具一次注册齐、可见性逐轮收窄</b>：运行中再注册的工具在同一请求里调不动；
 *       `find_tools` 的"下一轮就能用"靠的是工具已在执行侧、Middleware 决定这一轮发给模型看哪些。</li>
 * </ul>
 */
@Component
class AgentScopeConversation implements AssistantConversation {

    /**
     * 一轮对话里最多允许多少次"模型要工具、我们回结果"的往返。
     *
     * <p>ponytail: 固定 4 轮。它是防"模型绕着同一个工具转圈把用户的钱烧光"的兜底，
     * 不是功能上限——真出现"4 轮不够"的实测再提成配置。
     */
    private static final int MAX_TOOL_ROUNDS = 4;

    /** 工具入参与返回的截断上限（字符）。超限处标注，不静默截。 */
    private static final int ARGS_LIMIT = 1000;
    private static final int RESULT_LIMIT = 500;
    private static final Logger log = LoggerFactory.getLogger(AgentScopeConversation.class);

    private final ConversationProperties properties;
    private final GuardedHttpTransport transport;
    private final AgentToolBridge tools;

    AgentScopeConversation(ConversationProperties properties,
                           GuardedHttpTransport transport,
                           AgentToolBridge tools) {
        this.properties = properties;
        this.transport = transport;
        this.tools = tools;
    }

    @Override
    public void stream(Request request, ModelAccess access, RunRecorder.RunScope run, Listener listener) {
        GenerateOptions options = GenerateOptions.builder()
                .modelName(access.model())
                .stream(true)
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .build();
        Model model = buildModel(access, options);

        Turn turn = new Turn(request, run, listener);

        try (ReActAgent agent = ReActAgent.builder()
                .name("paideia-assistant")
                .sysPrompt(systemPrompt(request))
                .model(model)
                .generateOptions(options)
                .toolkit(turn.toolkit())
                .maxIters(MAX_TOOL_ROUNDS)
                .middleware(new RoundMiddleware(turn))
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId(String.valueOf(request.userId()))
                    .sessionId("req-" + System.nanoTime())
                    .build();

            // 超时语义：`requestTimeoutSeconds` 原本是**每一轮**的静默阈值（旧实现是
            // model.stream(...).timeout(...)，一轮结束重新计时）。这里若直接 blockLast(超时)，
            // 它变成整条工具循环的硬上限 —— maxIters(4) 下 4 次串行上游调用很容易超 60s，
            // 用户会在已经有产出之后被整条掐断。按轮数放大总预算，保持"每轮一份"的语义。
            long totalSeconds = (long) properties.getRequestTimeoutSeconds() * Math.max(1, MAX_TOOL_ROUNDS);
            agent.streamEvents(conversation(request), ctx)
                    .doOnNext(turn::observe)
                    .blockLast(Duration.ofSeconds(totalSeconds));

            listener.onUsage(turn.total);
            if (turn.failed == null) {
                listener.onCompleted();
            } else {
                listener.onFailed(turn.failed);
            }
        } catch (io.agentscope.core.model.transport.HttpTransportException exception) {
            log.warn("assistant.upstream.http-failed userId={}", request.userId(), exception);
            listener.onUsage(turn.total);
            listener.onFailed(toUpstreamFailure(exception));
        } catch (RuntimeException exception) {
            log.warn("assistant.upstream.failed userId={}", request.userId(), exception);
            listener.onUsage(turn.total);
            listener.onFailed(upstreamUnavailable(exception));
        }
    }

    /**
     * 把这一轮的所有 SYSTEM 内容按**原顺序**拼成一段。
     *
     * <p>顺序是设计的一部分：行为提示词 → 条目上下文 → 学情 → 记忆。换成别的顺序会让
     * "条目正文"看起来像平台自己的规则，或者让记忆盖住条目。
     */
    static String systemPrompt(Request request) {
        StringBuilder prompt = new StringBuilder(HarnessPrompts.system(request.harness()));
        if (request.hasEntryContext()) {
            prompt.append("\n\n").append(ConversationPrompt.entryContext(request.entryTitle(), request.entryBody()));
        }
        // 页面场景排在条目上下文之后、学情之前：它是"此刻屏幕上的东西"，
        // 比条目正文更具体，但不如学情那种跨会话的结论稳定
        if (request.hasPageContext()) {
            prompt.append("\n\n").append(ConversationPrompt.pageContext(request.pageContext()));
        }
        if (request.harness() == Harness.TUTOR && request.learningSummary() != null
                && !request.learningSummary().isBlank()) {
            prompt.append("\n\n").append(TutorPrompt.learningContext(request.learningSummary()));
        }
        if (!request.memorySnippets().isEmpty()) {
            prompt.append("\n\n").append(ConversationPrompt.memory(request.memorySnippets()));
        }
        return prompt.toString();
    }

    /** 历史 + 本轮输入。**只含 USER / ASSISTANT**——SYSTEM 走 `sysPrompt`，混进来会抛异常。 */
    private static List<Msg> conversation(Request request) {
        List<Msg> messages = new ArrayList<>();
        for (ConversationTurn turn : ConversationPrompt.history(request.history())) {
            messages.add(Msg.builder()
                    .role(turn.role() == ConversationTurn.Role.ASSISTANT ? MsgRole.ASSISTANT : MsgRole.USER)
                    .textContent(turn.content())
                    .build());
        }
        messages.add(Msg.builder().role(MsgRole.USER).textContent(request.userMessage()).build());
        return messages;
    }

    /**
     * 建模型客户端。
     *
     * <p>抽成方法是为了**可替换**：用量累加这件事没法用"桩掉端口"那一套测——桩里根本没有循环。
     * 只能在模型这一层注一个假模型，喂两轮响应（第一轮要工具、第二轮出正文），断言总量是两轮之和。
     */
    Model buildModel(ModelAccess access, GenerateOptions options) {
        return openAiModel(access, options, transport);
    }

    /**
     * 建 OpenAI 兼容模型的公共实现（流式对话与非流式问答共用）。
     *
     * <p>抽成静态方法是为了让两条循环用**完全一样**的模型配置——DeepSeek 那几条坑
     * （思考 token、nativeStructuredOutput 必须关）只写一处，不会有人在一侧漏掉。
     */
    static Model openAiModel(ModelAccess access, GenerateOptions options, GuardedHttpTransport transport) {
        return OpenAIChatModel.builder()
                .apiKey(access.apiKey())
                .baseUrl(access.baseUrl())
                .modelName(access.model())
                .stream(true)
                // DeepSeek 上工具调用与结构化输出不能同一次调用并用：关掉原生结构化输出，
                // 否则框架可能为了满足 response_format 而跳过工具调用
                .nativeStructuredOutputWithTools(false)
                .formatter(new DeepSeekFormatter())
                .httpTransport(transport)
                .generateOptions(options)
                .build();
    }

    /**
     * 截断到上限并在末尾标注；未超限原样返回。
     *
     * <p>超限时**标明原长**而不是静默切：读的人要能判断"这是不是全部内容"——
     * 一段被悄悄切掉的工具返回，会让人以为是工具本身只返回了那么多。
     */
    static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "…（已截断，原长 " + value.length() + " 字符）";
    }

    /** 两段用量相加。任一段缺失（上游没给）时按"另一段"计，两段都缺才是 null。 */
    static TokenUsage add(TokenUsage left, TokenUsage right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return new TokenUsage(left.inputTokens() + right.inputTokens(),
                left.outputTokens() + right.outputTokens(),
                left.totalTokens() + right.totalTokens(),
                left.cacheReadTokens() + right.cacheReadTokens());
    }

    private static TokenUsage toUsage(ChatUsage usage) {
        return usage == null
                ? null
                : new TokenUsage(usage.getInputTokens(), usage.getOutputTokens(),
                        usage.getTotalTokens(), usage.getCachedTokens());
    }

    /** 与精简那份同一套分类：只按"谁的问题"给说法，**不复述上游报文**（可能回显 Authorization）。 */
    private static BizException toUpstreamFailure(io.agentscope.core.model.transport.HttpTransportException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("401") || message.contains("403")) {
            return new BizException(ErrorCode.INVALID_ARGUMENT, "上游拒绝了这组凭据，请检查 API Key 与 baseUrl");
        }
        return upstreamUnavailable(exception);
    }

    private static BizException upstreamUnavailable(Throwable cause) {
        BizException failure = new BizException(ErrorCode.INTERNAL, "上游模型服务暂时不可用，请稍后再试");
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }

    /**
     * 一次请求的可变状态。
     *
     * <p>它同时被工具适配器与 Middleware 读写：工具跑的时候可能发现新工具（`find_tools`），
     * Middleware 在下一轮据此把 schema 放出去。两者必须在同一个对象上，所以不能是局部变量各自持有。
     */
    private final class Turn {

        private final Request request;
        private final RunRecorder.RunScope run;
        private final Listener listener;

        /** 这一轮**本来**就能看到的工具（不在里面就要靠 find_tools）。 */
        private final List<ToolSchema> core;
        /** `find_tools` 元工具的 schema；未启用时为空。 */
        private final List<ToolSchema> discovery;
        /** 本请求内已发现的工具名，逐轮累积。 */
        private final Set<String> discovered = new LinkedHashSet<>();

        private TokenUsage total;
        private BizException failed;
        private int rounds;

        Turn(Request request, RunRecorder.RunScope run, Listener listener) {
            this.request = request;
            this.run = run;
            this.listener = listener;
            this.core = tools.core(request.harness());
            // 核心包为空（点评官）时不加发现元工具：它不该有任何工具
            this.discovery = core.isEmpty() ? List.of() : tools.discoverySchemas();
        }

        /**
         * 执行侧一次注册齐。
         *
         * <p>注册的是"这个 harness **有可能**用到"的全部工具，而不是这一轮可见的那些——
         * 运行中再注册的调不动（实测），所以 `find_tools` 的"下一轮可用"只能这样实现。
         */
        Toolkit toolkit() {
            Toolkit toolkit = new Toolkit();
            for (ToolSchema schema : tools.callable(request.harness())) {
                toolkit.registerAgentTool(new BridgedTool(this, schema));
            }
            for (ToolSchema schema : discovery) {
                toolkit.registerAgentTool(new BridgedTool(this, schema));
            }
            return toolkit;
        }

        /** 这一轮发给模型看的 schema 清单：核心 + 已发现 + 发现元工具。 */
        List<ToolSchema> visibleSchemas() {
            List<ToolSchema> merged = new ArrayList<>(tools.concat(core, discovered));
            merged.addAll(discovery);
            return merged;
        }

        /** 逐事件把上游的增量转成端口回调和统计。 */
        void observe(AgentEvent event) {
            if (event instanceof ThinkingBlockDeltaEvent thinking) {
                // 思考即刻转发：用户要看到它正在想，这一路不进历史也不进正文
                listener.onThinking(thinking.getDelta());
            } else if (event instanceof ModelCallEndEvent end) {
                // 用量在**外层顺序流**里累加，而不是在每轮的 doFinally 里：
                // doFinally 的触发顺序由内向外、且可能在不同线程上，读到的一半是部分和。
                accumulate(end.getUsage());
            } else if (event instanceof ExceedMaxItersEvent) {
                // 绕满 maxIters 时框架**先发这个事件、再正常结束流**。靠每轮 doFinally
                // 里的"无工具无正文"判据不够：那几轮都有工具调用，谁也不会标失败，
                // 于是"放弃"会被当成成功上报（实测 5 次跑 3 次红）。
                fail(upstreamUnavailable(null));
            }
        }

        /** 模型轮序号（从 1 开始）。同步取，避免并发轮次撞号。 */
        synchronized int nextSeq() {
            return ++rounds;
        }

        /** 逐轮累加用量。同步，避免 Reactor 线程上的读-改-写丢更新。 */
        synchronized void accumulate(ChatUsage usage) {
            total = add(total, toUsage(usage));
        }

        void fail(BizException failure) {
            if (failed == null) {
                failed = failure;
            }
        }
    }

    /**
     * 每轮模型调用前后的钩子。
     *
     * <p>它做三件框架不做的事：**收窄 schema**（`find_tools` 语义）、**逐轮记用量与一步遥测**、
     * 以及**只在没有工具调用的那一轮把正文推给用户**（WORK-006 的实测修补丁）。
     */
    private final class RoundMiddleware implements MiddlewareBase {

        private final Turn turn;

        RoundMiddleware(Turn turn) {
            this.turn = turn;
        }

        @Override
        public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                            java.util.function.Function<ModelCallInput, Flux<AgentEvent>> next) {
            // 步序号必须在这里同步取：模型轮之间可能在不同 Reactor 线程上，
            // 读-改-写会撞号（实测曾让 5 轮的 seq 全是 1）。
            int seq = turn.nextSeq();
            RunRecorder.StepScope step = turn.run.step(SpanSpec.llm(seq));
            long roundStart = System.nanoTime();
            StringBuilder text = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            AtomicReference<ChatUsage> usage = new AtomicReference<>();
            AtomicInteger ttft = new AtomicInteger(-1);
            boolean[] sawToolCall = {false};

            // 这一轮可见的工具**在这里重算**：上一轮 find_tools 可能刚发现新的
            List<ToolSchema> visible = turn.visibleSchemas();
            ModelCallInput narrowed = new ModelCallInput(
                    input.messages(), visible, input.options(), input.model());

            // **请求体在这里快照**：入参就是"实际要发的东西"，序列化层做的裁剪与注入都已在内。
            // 只取安全字段（`PayloadJson` 白名单），凭据不进库。
            String requestBody = PayloadJson.request(input.messages(), visible, input.options());

            return next.apply(narrowed)
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            if (ttft.get() < 0) {
                                ttft.set((int) ((System.nanoTime() - roundStart) / 1_000_000));
                            }
                            text.append(delta.getDelta());
                        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
                            thinking.append(delta.getDelta());
                        } else if (event instanceof ToolCallStartEvent start) {
                            sawToolCall[0] = true;
                        } else if (event instanceof ModelCallEndEvent end) {
                            usage.set(end.getUsage());
                        }
                    })
                    // **正文必须在这里推，不能在 doFinally 里推**（2026-10-21 实测）：
                    // doFinally 由终止信号**之后**异步触发，此时 blockLast() 可能已经返回、
                    // listener.onCompleted() 也已经调过——用户会看到一条空回答。
                    // doOnComplete/doOnError 在信号同步传播路径上，早于外部订阅者看到终止。
                    .doOnComplete(() -> finishRound(step, usage.get(), text.toString(), thinking.toString(),
                            requestBody, ttft.get(), sawToolCall[0], null))
                    .doOnError(error -> finishRound(step, usage.get(), text.toString(), thinking.toString(),
                            requestBody, ttft.get(), sawToolCall[0], error))
                    .doOnCancel(() -> {
                        // 主动取消（前端断开）：记成中断，不推正文
                        ChatUsage used = usage.get();
                        if (used != null) {
                            step.tokens(used.getInputTokens(), used.getOutputTokens(), used.getCachedTokens());
                        }
                        step.failed("cancelled");
                        step.close();
                    });
        }

        /** 一轮收尾：记 tokens / 首字 / 完整载荷、判成败、最终轮推正文。只执行一次。 */
        private void finishRound(RunRecorder.StepScope step, ChatUsage used, String text, String thinking,
                                 String requestBody, int ttft,
                                 boolean sawToolCall, Throwable error) {
            if (used != null) {
                step.tokens(used.getInputTokens(), used.getOutputTokens(), used.getCachedTokens());
            }
            if (ttft >= 0) {
                step.ttftMs(ttft);
            }
            if (error != null) {
                step.failed("upstream_error");
                turn.fail(upstreamUnavailable(error));
            } else if (!sawToolCall && text.isEmpty()) {
                // 一轮既没工具调用、也没吐任何正文：DeepSeek 思考 token 吃掉 max_tokens
                // 时就是这样（"成功但 content 为空"）。旧实现把它判成上游不可用，沿用。
                step.failed("upstream_unavailable");
                turn.fail(upstreamUnavailable(null));
            } else {
                step.succeeded();
                // **最终轮才推正文**：工具轮的旁白会进历史（模型自己看得见），但不该算回答
                if (!sawToolCall) {
                    turn.listener.onContent(text);
                }
            }
            // 完整载荷：失败与成功都留（失败的更要看"它到底收到了什么"）
            step.payload(requestBody, PayloadJson.response(text, thinking, null));
            step.close();
        }
    }

    /**
     * 把平台目录里的一个工具包成 AgentScope 的 {@link AgentTool}。
     *
     * <p>为什么不用注解：工具目录是公共契约（`paideia-platform`），不能为了适配某个 SDK
     * 给业务类加 {@code @Tool}。这一个类就是全部的适配成本。
     */
    private final class BridgedTool implements AgentTool {

        private final Turn turn;
        private final ToolSchema schema;

        BridgedTool(Turn turn, ToolSchema schema) {
            this.turn = turn;
            this.schema = schema;
        }

        @Override
        public String getName() {
            return schema.getName();
        }

        @Override
        public String getDescription() {
            return schema.getDescription();
        }

        @Override
        public java.util.Map<String, Object> getParameters() {
            return schema.getParameters();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            // 每次工具调用各开一步（WORK-022）：工具现在唯一的可视化就是这里，
            // "哪个工具失败、哪个慢"只能靠它回答。
            try (RunRecorder.StepScope step = turn.run.step(SpanSpec.tool(getName()))) {
                AgentToolBridge.Outcome outcome = tools.run(
                        turn.request.userId(), turn.request.harness(), getName(), param.getInput());
                // 入参与返回记进这一步的 attrs（WORK-030 阶段 B）：排查"它为什么答错"时，
                // 光知道"工具成功了"没有用，得知道它收到什么、回了什么。
                // **截断在写入前做**：一次笔记全文或一段记忆就是几十 KB，attrs 是 JSON 列，
                // 写大了会拖慢整张步骤表。
                step.attribute("toolArgs", truncate(String.valueOf(param.getInput()), ARGS_LIMIT));
                step.attribute("toolResult", truncate(outcome.text(), RESULT_LIMIT));
                if (outcome.error()) {
                    step.failed("tool_error");
                } else {
                    step.succeeded();
                }
                if (outcome.draft() != null) {
                    // 草稿立刻送到界面：用户的"要不要存"不该等模型把话说完才出现
                    turn.listener.onDraft(outcome.draft());
                }
                // find_tools 的命中要在下一轮生效：看见什么就记下来
                turn.discovered.addAll(outcome.discovered());
                return Mono.just(ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name(getName())
                        .output(TextBlock.builder().text(outcome.text()).build())
                        .build());
            }
        }
    }
}
