package io.github.xiaou61.ai.internal.provider;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.credential.AiProperties;
import io.github.xiaou61.ai.internal.support.FinishReasons;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Duration;
import java.util.List;

/**
 * "一次非流式调用、不要工具、只要 JSON"这类调用的**共同骨架**。
 *
 * <p>第一个消费者是简历那三次（WORK-018：解析 / 分析 / 提案），第四个是训练营出卷
 * （WORK-029）。它们之间的差异只有 system 与 user 两段文字，其余——模型构造、不挂工具、
 * 关掉原生结构化输出、超时、错误归一、空内容按失败——完全相同。抽成一个基类而不是复制粘贴，
 * 是因为这几条设置里任何一条改了，所有调用点必须一起改：只改一处会得到
 * "解析走思考模式、出卷不走"这种极难察觉的不一致。
 *
 * <p>思考关不掉（deepseek-flash 默认就思考，本项目没有关它的开关）：思考 token 占用
 * {@code maxTokens}，给少了会把 JSON 截成半截（2026-10-19 在点评那路实测复现），
 * 故 {@code AiProperties.maxTokens} 取 4096。
 *
 * <p>为什么 {@code nativeStructuredOutput(false)}：DeepSeek 不支持原生结构化输出。
 * 我们用"要求模型只回 JSON + 本地严格校验"那套，与 {@code AgentScopeInterviewReviewer} 一致。
 */
public abstract class SingleShotModelCaller {

    private final AiProperties properties;
    private final GuardedHttpTransport transport;

    protected SingleShotModelCaller(AiProperties properties, GuardedHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    /**
     * 一次调用的结果：模型文本 + 用量。用量必须带回去——三次调用都要计入积分，
     * 而失败时也要能按已发生的用量结算。
     */
    protected record ModelReply(String text, TokenUsage usage) {
    }

    /**
     * 发一次非流式调用（用默认上限）。
     *
     * @param system 系统提示词
     * @param user   用户消息（已把简历/JD 用分隔标记包好）
     */
    protected ModelReply call(String system, String user, ModelAccess access) {
        return call(system, user, access, maxTokens(), properties.getRequestTimeoutSeconds());
    }

    /**
     * 发一次非流式调用，**显式给输出上限与超时**。
     *
     * <p>为什么需要这个重载：训练营一次要出 12–18 道带解析的题，输出长度与耗时都随题数变化。
     * 两个都要按调用方本次的规模给：
     * <ul>
     *   <li>固定 {@value #MAX_TOKENS} 会在第 3 道题处截断（实测 {@code finish_reason=length}）；</li>
     *   <li>默认 30 秒的超时会把整份生成判成"上游不可用"——实测 12 道题耗时 25–30 秒，
     *       诊断卷 29.8 秒**压线通过**、复测 30.1 秒就失败。这种"看运气"的边界不能留。</li>
     * </ul>
     * 两个值都由调用方算，不放在子类字段里——这些调用点都在 singleton bean 上，
     * 字段会被并发请求串味。
     */
    protected ModelReply call(String system, String user, ModelAccess access, int maxTokens,
                              int timeoutSeconds) {
        if (access == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "平台尚未配置 AI 服务，请联系管理员");
        }
        GenerateOptions options = GenerateOptions.builder()
                .modelName(access.model())
                .stream(false)
                .temperature(properties.getTemperature())
                .maxTokens(maxTokens)
                .build();
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(access.apiKey())
                .baseUrl(access.baseUrl())
                .modelName(access.model())
                .formatter(new DeepSeekFormatter())
                .stream(false)
                .generateOptions(options)
                .nativeStructuredOutput(false)
                .nativeStructuredOutputWithTools(false)
                .httpTransport(transport)
                .build();

        // 不挂工具：三次调用都不需要工具，而工具与结构化输出在 DeepSeek 上不能并存。
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(system).build(),
                Msg.builder().role(MsgRole.USER).textContent(user).build());

        ChatResponse response;
        try {
            response = model.stream(messages, List.of(), options)
                    .blockLast(Duration.ofSeconds(timeoutSeconds));
        } catch (HttpTransportException exception) {
            throw toUpstreamFailure(exception);
        } catch (RuntimeException exception) {
            throw upstreamUnavailable(exception);
        }
        if (response == null) {
            throw upstreamUnavailable(null);
        }
        // 截断要先判：它在解析层看起来跟"模型没按格式回答"一模一样，但处置完全不同
        // （抬 maxTokens / 压输出规模，而不是改提示词）。见 FinishReasons。
        if (FinishReasons.truncated(response)) {
            throw new BizException(ErrorCode.INTERNAL, "模型输出被长度上限截断，本次结果不可用，请重试");
        }
        String text = extractText(response);
        if (text.isBlank()) {
            // 空内容按失败处理：不落库、退款。**不把它当成"模型没有意见"**——
            // 那会让用户以为"我的简历没问题"。
            throw new BizException(ErrorCode.INTERNAL, "模型返回了空内容，请稍后再试");
        }
        return new ModelReply(text, usageOf(response));
    }

    /**
     * 单次生成的 token 上限。
     *
     * <p>简历解析与提案的输出都比摘要长得多（一份简历可能十几个条目）。默认的
     * {@code paideia.ai.max-tokens} 是给短卡片定的 1024，直接用会把 JSON 截断成半截——
     * 那种失败看起来像"模型不听话"，其实是我们的上限太小。
     *
     * <p>ponytail: 固定 {@value #MAX_TOKENS}。天花板是一份极长的简历可能仍被截断，
     * 那时会在解析层报"不是合法 JSON"而不是静默丢内容；升级路径是按文档长度动态放大。
     */
    private static final int MAX_TOKENS = 4096;

    protected int maxTokens() {
        return MAX_TOKENS;
    }

    private static TokenUsage usageOf(ChatResponse response) {
        ChatUsage usage = response == null ? null : response.getUsage();
        return usage == null
                ? null
                : new TokenUsage(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
    }

    private static String extractText(ChatResponse response) {
        StringBuilder builder = new StringBuilder();
        if (response.getContent() != null) {
            for (var block : response.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    builder.append(textBlock.getText());
                }
            }
        }
        return builder.toString();
    }

    /** 上游错误分类，刻意不复述上游报文（它可能回显 Authorization 头或内部地址）。 */
    private static BizException toUpstreamFailure(HttpTransportException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("401") || message.contains("403")) {
            return new BizException(ErrorCode.INVALID_ARGUMENT, "上游拒绝了这组凭据，请联系管理员检查平台配置");
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
}
