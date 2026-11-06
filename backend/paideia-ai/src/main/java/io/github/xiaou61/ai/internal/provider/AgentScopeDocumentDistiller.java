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
import io.github.xiaou61.ai.DistilledDocument;
import io.github.xiaou61.ai.DistilledEntry;
import io.github.xiaou61.ai.DocumentDistiller;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.credential.AiProperties;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 用 AgentScope 完成一次文档蒸馏（WORK-076 TASK-004）。
 *
 * <p>与 {@link AgentScopeEntrySummarizer} 同一套最小面：一个 OpenAI 兼容模型 + DeepSeek formatter
 * + 一次非流式调用，不挂工具。区别是产物是「一条条目草稿」而不是卡片，且输出上限更大
 * （蒸馏一条完整文档正文需要更多输出 token）。
 */
@Component
class AgentScopeDocumentDistiller implements DocumentDistiller {

    /** 蒸馏输出上限：不按 properties 的默认 4096 给，因为要产出一条完整条目正文。 */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    private final AiProperties properties;
    private final DistilledEntryValidator validator;
    private final GuardedHttpTransport transport;

    AgentScopeDocumentDistiller(AiProperties properties,
                                DistilledEntryValidator validator,
                                GuardedHttpTransport transport) {
        this.properties = properties;
        this.validator = validator;
        this.transport = transport;
    }

    @Override
    public DistilledDocument distill(String fileName, String text, ModelAccess access) {
        TruncatedInput input = TruncatedInput.of(text, properties.getMaxInputChars());

        GenerateOptions options = GenerateOptions.builder()
                .modelName(access.model())
                .stream(false)
                .temperature(properties.getTemperature())
                .maxTokens(Math.max(properties.getMaxTokens(), MAX_OUTPUT_TOKENS))
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

        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(DocumentDistillPrompt.system()).build(),
                Msg.builder().role(MsgRole.USER).textContent(DocumentDistillPrompt.user(fileName, input.text())).build());

        ChatResponse response;
        try {
            response = model.stream(messages, List.of(), options)
                    .blockLast(Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
        } catch (HttpTransportException exception) {
            throw toUpstreamFailure(exception);
        } catch (RuntimeException exception) {
            throw upstreamUnavailable(exception);
        }
        if (response == null) {
            throw upstreamUnavailable(null);
        }

        String raw = extractText(response);
        DistilledEntry entry = validator.validate(raw);
        return new DistilledDocument(entry, toUsage(response.getUsage()), input.truncated());
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

    private static TokenUsage toUsage(ChatUsage usage) {
        return usage == null
                ? null
                : new TokenUsage(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
    }

    private static BizException toUpstreamFailure(HttpTransportException exception) {
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

    /** 超长文本按段落截断；返回是否截断过。 */
    private record TruncatedInput(String text, boolean truncated) {

        static TruncatedInput of(String body, int maxChars) {
            if (body == null) {
                return new TruncatedInput("", false);
            }
            if (body.length() <= maxChars) {
                return new TruncatedInput(body, false);
            }
            String cut = body.substring(0, maxChars);
            int lastParagraph = cut.lastIndexOf("\n\n");
            if (lastParagraph > maxChars / 2) {
                cut = cut.substring(0, lastParagraph);
            }
            return new TruncatedInput(cut, true);
        }
    }
}
