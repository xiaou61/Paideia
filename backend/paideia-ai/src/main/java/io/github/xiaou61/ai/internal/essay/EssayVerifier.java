package io.github.xiaou61.ai.internal.essay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.internal.provider.GuardedHttpTransport;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 面经答案的事实自检与修订（WORK-084）。
 *
 * <p>构建方式与 {@link EssayExtractor} 完全一致（同一套 AgentScope 最小面、关掉原生结构化、走
 * {@link GuardedHttpTransport}），区别只有两点：温度 0（判定要稳定），输出更短（只回一个 JSON 小对象）。
 * 这里**不调用任何工具**——检索是确定性的 Java 代码（TASK-005），模型只负责判断与改写。
 */
@Component
class EssayVerifier {

    private static final int MAX_TOKENS = 2048;

    /** 判定类任务用 0 度，尽量消除随机性。 */
    private static final double TEMPERATURE = 0.0;

    private static final int TIMEOUT_SECONDS = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GuardedHttpTransport transport;

    EssayVerifier(GuardedHttpTransport transport) {
        this.transport = transport;
    }

    FactCheck checkFactual(String title, String answer, ModelAccess access) {
        String raw = ask(EssayVerificationPrompt.verifySystem(),
                EssayVerificationPrompt.verifyUser(title, answer), access);
        JsonNode root = readJson(raw);
        String verdict = text(root, "verdict", "unsure");
        if (!verdict.equals("ok") && !verdict.equals("issues") && !verdict.equals("unsure")) {
            verdict = "unsure";
        }
        return new FactCheck(verdict, clamp(root.path("confidence").asDouble(0.0)), strings(root.path("issues")));
    }

    Revision revise(String title, String draft, List<String> issues,
                    List<ReferenceSnippet> snippets, ModelAccess access) {
        String raw = ask(EssayVerificationPrompt.reviseSystem(),
                EssayVerificationPrompt.reviseUser(title, draft, issues, snippets), access);
        JsonNode root = readJson(raw);
        String action = text(root, "action", "reject");
        if (!action.equals("accept") && !action.equals("revise") && !action.equals("reject")) {
            action = "reject";
        }
        String answer = root.path("answer").isMissingNode() ? null : root.path("answer").asText(null);
        return new Revision(action, answer, clamp(root.path("confidence").asDouble(0.0)),
                strings(root.path("usedRefs")), text(root, "notes", ""));
    }

    private String ask(String system, String user, ModelAccess access) {
        GenerateOptions options = GenerateOptions.builder()
                .modelName(access.model())
                .stream(false)
                .temperature(TEMPERATURE)
                .maxTokens(MAX_TOKENS)
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
                Msg.builder().role(MsgRole.SYSTEM).textContent(system).build(),
                Msg.builder().role(MsgRole.USER).textContent(user).build());
        ChatResponse response;
        try {
            response = model.stream(messages, List.of(), options).blockLast(Duration.ofSeconds(TIMEOUT_SECONDS));
        } catch (HttpTransportException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("401") || message.contains("403")) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "上游拒绝了这组凭据");
            }
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (response == null) {
            throw unavailable(null);
        }
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

    private static JsonNode readJson(String raw) {
        try {
            return JSON.readTree(stripFence(raw));
        } catch (Exception exception) {
            throw new IllegalStateException("校验输出不是合法 JSON", exception);
        }
    }

    /** 去掉 ```json 围栏（模型偶尔仍会加）。 */
    static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String text(JsonNode root, String field, String fallback) {
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? fallback : node.asText(fallback);
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static BizException unavailable(Throwable cause) {
        BizException failure = new BizException(ErrorCode.INTERNAL, "上游模型服务暂时不可用");
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }
}
