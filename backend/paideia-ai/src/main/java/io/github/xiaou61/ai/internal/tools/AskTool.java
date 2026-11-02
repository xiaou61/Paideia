package io.github.xiaou61.ai.internal.tools;

import io.github.xiaou61.ai.internal.conversation.AssistantAnswerer;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.conversation.HarnessPrompts;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * `assistant_ask`：让外部助手调用平台 AI 回答一次（WORK-019 REQ-028）。
 *
 * <p><b>为什么这个工具自己管计费</b>：其余工具要么不花钱、要么由调用它的用例自己收钱；而这里
 * 每一次调用都会真实地打一次上游模型，不预留就会在并发下超扣。所以它先
 * {@code reserve}、拿到用量后 {@code settle}、异常时 {@code refund}——与对话路径同一套原语，
 * 只是把"一次工具调用"当作一次调用。
 *
 * <p><b>余额不足要在发请求之前就拒</b>：`reserve` 抛 402 时上游一次请求都没发。
 *
 * <p><b>不落会话</b>：外部调用不是用户的一段对话，写进去会让他的会话列表多出"我在别处问过"。
 */
@Component
public class AskTool implements PaideiaTool {

    static final String NAME = "assistant_ask";
    private static final int MAX_QUESTION = 2_000;

    /**
     * <b>为什么是 {@code ObjectProvider} 而不是直接注入</b>：本类本身是一个 `PaideiaTool`，
     * 会被 `ToolCatalog` 收集；而问答实现又依赖目录（它要挂工具）。直接注入会形成
     * `catalog → askTool → answerer → bridge → catalog` 这个启动期循环。
     * 本工具只在**真正被调用**时才需要问答实现，所以延迟解析既打破循环，也不改变语义。
     */
    private final org.springframework.beans.factory.ObjectProvider<AssistantAnswerer> answerer;
    private final io.github.xiaou61.ai.internal.credential.CredentialService credentials;
    private final CreditService credits;
    private final io.github.xiaou61.ai.RunRecorder recorder;

    AskTool(org.springframework.beans.factory.ObjectProvider<AssistantAnswerer> answerer,
            io.github.xiaou61.ai.internal.credential.CredentialService credentials,
            CreditService credits,
            io.github.xiaou61.ai.RunRecorder recorder) {
        this.answerer = answerer;
        this.credentials = credentials;
        this.credits = credits;
        this.recorder = recorder;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                NAME, "assistant", "让平台 AI 回答一个学习问题",
                "把一个问题交给 Paideia 的学习助手回答。它能看到平台知识库，会按需检索条目。"
                        + "**每次调用都会消耗调用者的积分**；余额不足时直接失败。"
                        + "问题应当是一个完整、自包含的提问（外部调用没有聊天历史可用）。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "question", Map.of("type", "string", "description", "要问的问题，完整自包含"),
                                "entryId", Map.of("type", "integer",
                                        "description", "可选：把某篇知识条目作为上下文")),
                        "required", List.of("question")),
                Map.of("type", "object",
                        "properties", Map.of("answer", Map.of("type", "string"))),
                false, false, true, ToolScope.WRITE, false);
    }

    @Override
    public ToolResult call(ToolContext context, Map<String, Object> arguments) {
        String question = stringOf(arguments, "question");
        if (question == null) {
            return ToolResult.fail("缺少 question");
        }
        if (question.length() > MAX_QUESTION) {
            return ToolResult.fail("问题不能超过 " + MAX_QUESTION + " 个字符");
        }
        ModelAccess modelAccess = credentials.access(context.userId()).orElse(null);
        if (modelAccess == null) {
            return ToolResult.fail("平台尚未配置 AI 服务");
        }
        Optional<Long> entryId = Optional.ofNullable(longOf(arguments, "entryId"));

        // 先预扣：余额不足时这里就抛 402，一次上游请求都不会发出去。
        // **预扣本身也在 try 里**：402 是预期的业务结果，应当变成一条可读的工具错误返回给
        // 外部模型，而不是冒泡成"工具执行失败"（那会让它以为是自己参数写错了）。
        long reserved = 0;
        var run = recorder.begin(io.github.xiaou61.ai.RunSpec
                .of(io.github.xiaou61.ai.RunKind.MCP_ASK, context.userId())
                .withModel(modelAccess.model())
                // 外部问答走的是同一个通用助手提示词，版本也就跟着它
                .withPromptVersion(HarnessPrompts.version(io.github.xiaou61.ai.Harness.ASSISTANT)));
        try {
            run.attribute("reqChars", question.length());
            reserved = credits.reserve(context.userId(), credits.estimate());
            AssistantAnswerer.Answer answer =
                    answerer.getObject().answer(context.userId(), question, entryId, modelAccess);
            TokenUsage usage = answer.usage();
            int charged;
            if (usage == null) {
                credits.refund(context.userId(), reserved, null, "外部问答未返回用量");
                charged = 0;
            } else {
                charged = credits.settleExternalAsk(context.userId(), reserved, modelAccess.model(),
                        usage.inputTokens(), usage.outputTokens());
                run.tokens(usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens());
            }
            run.addCredits(charged);
            run.finish(io.github.xiaou61.ai.RunOutcome.OK, null);
            return ToolResult.ok(answer.text(), Map.of("answer", answer.text()));
        } catch (RuntimeException failure) {
            // 预留成功过才退：reserve 自己抛错时余额没被扣
            if (reserved > 0) {
                credits.refund(context.userId(), reserved, null, "外部问答失败");
            }
            run.finish(io.github.xiaou61.ai.RunOutcome.FAILED, "mcp_ask_failed");
            return ToolResult.fail(messageOf(failure));
        } finally {
            run.close();
        }
    }

    private static String stringOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static Long longOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
