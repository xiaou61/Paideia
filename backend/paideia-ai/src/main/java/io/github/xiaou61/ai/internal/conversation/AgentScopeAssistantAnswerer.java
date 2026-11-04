package io.github.xiaou61.ai.internal.conversation;

import io.agentscope.core.agent.accumulator.TextAccumulator;
import io.agentscope.core.agent.accumulator.ToolCallsAccumulator;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.ToolResultMessage;
import io.github.xiaou61.ai.Harness;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.provider.GuardedHttpTransport;
import io.github.xiaou61.ai.internal.tools.AgentToolBridge;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 非流式问答（WORK-019 TASK-012）。
 *
 * <p>它**复用**通用助手的工具集与提示词，但只走一遍循环、不落库、不带历史。之所以不直接调
 * {@link AgentScopeConversation}：那一个是流式端口，硬塞进 MCP 工具调用要同时改两侧的语义。
 * 这里用一个共享的最小循环，代价是两条循环并存——但两者的失败语义不同（一个推 SSE 事件、
 * 一个返回字符串），合并反而更难读。
 *
 * <p>`ponytail:` 工具轮次上限与对话侧一致（4 轮）。真需要更长时两边要一起改。
 */
@Component
class AgentScopeAssistantAnswerer implements AssistantAnswerer {

    private static final int MAX_TOOL_ROUNDS = 4;
    private static final int MAX_QUESTION_CHARS = 4_000;

    private final ConversationProperties properties;
    private final GuardedHttpTransport transport;
    private final AgentToolBridge tools;
    private final io.github.xiaou61.ai.internal.rag.KnowledgeRagService rag;

    AgentScopeAssistantAnswerer(ConversationProperties properties,
                                GuardedHttpTransport transport,
                                AgentToolBridge tools,
                                io.github.xiaou61.ai.internal.rag.KnowledgeRagService rag) {
        this.properties = properties;
        this.transport = transport;
        this.tools = tools;
        this.rag = rag;
    }

    @Override
    public Answer answer(long userId, String question, Optional<Long> entryId, ModelAccess access) {
        String trimmed = question == null ? "" : question.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "问题不能为空");
        }
        if (trimmed.length() > MAX_QUESTION_CHARS) {
            // 外部调用不该能把整本书塞进来：超长直接拒，不做静默截断
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "问题不能超过 " + MAX_QUESTION_CHARS + " 个字符");
        }

        GenerateOptions options = GenerateOptions.builder()
                .modelName(access.model())
                .stream(true)
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .build();
        Model model = buildModel(access, options);

        String knowledgeContext = rag.retrieveContext(trimmed);
        String system = HarnessPrompts.system(Harness.ASSISTANT);
        if (!knowledgeContext.isBlank()) {
            system += "\n\n" + ConversationPrompt.knowledge(knowledgeContext);
        }
        List<Msg> conversation = new ArrayList<>();
        conversation.add(Msg.builder().role(MsgRole.SYSTEM).textContent(system).build());
        conversation.add(Msg.builder().role(MsgRole.USER).textContent(trimmed).build());

        List<ToolSchema> core = tools.core(Harness.ASSISTANT);
        List<ToolSchema> discovery = tools.discoverySchemas();
        List<String> discovered = new ArrayList<>();

        TokenUsage total = null;
        StringBuilder content = new StringBuilder();
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            List<ToolSchema> schemas = new ArrayList<>(tools.concat(core, discovered));
            schemas.addAll(discovery);
            Round roundResult = runRound(model, conversation, schemas, options);
            if (roundResult == null) {
                throw new BizException(ErrorCode.INTERNAL, "上游没有返回内容");
            }
            total = AgentScopeConversation.add(total, roundResult.usage());
            if (roundResult.text() != null && !roundResult.text().isBlank()) {
                content.append(roundResult.text());
            }
            List<ToolUseBlock> requested = roundResult.toolCalls();
            if (requested.isEmpty()) {
                if (content.toString().isBlank()) {
                    // 与对话侧同一个兜底：思考吃满 max_tokens 时上游会静默返回空正文
                    throw new BizException(ErrorCode.INTERNAL, "只产生了思考过程，没有生成正文");
                }
                return new Answer(content.toString(), total);
            }
            conversation.add(roundResult.assistantMessage());
            // 每个 tool_call_id 各发一条 ToolResultMessage：OpenAI 协议要求 assistant 的每个
            // tool_calls 都有对应的 tool 消息。打包成一条时 formatter 只产出一个 tool 消息，
            // 上游直接 400（"insufficient tool messages following tool_calls message"）——
            // 同一批改动里 AgentScopeConversation 已按实测修过，这里原本漏了。
            for (ToolResultBlock result : execute(userId, requested, discovered)) {
                conversation.add(new ToolResultMessage(result));
            }
        }
        throw new BizException(ErrorCode.INTERNAL, "助手尝试调用工具的次数过多，已停止");
    }

    private Model buildModel(ModelAccess access, GenerateOptions options) {
        return AgentScopeConversation.openAiModel(access, options, transport);
    }

    private Round runRound(Model model, List<Msg> conversation, List<ToolSchema> schemas,
                           GenerateOptions options) {
        TextAccumulator text = new TextAccumulator();
        ToolCallsAccumulator calls = new ToolCallsAccumulator();
        ChatResponse last = null;
        for (ChatResponse response : model.stream(conversation, schemas, options)
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .toIterable()) {
            last = response;
            if (response.getContent() == null) {
                continue;
            }
            for (ContentBlock block : response.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    text.add(textBlock);
                } else if (block instanceof ToolUseBlock toolCall) {
                    calls.add(toolCall);
                }
            }
        }
        if (last == null) {
            return null;
        }
        List<ToolUseBlock> requested = calls.buildAllToolCalls();
        List<ContentBlock> blocks = new ArrayList<>();
        if (!text.getAccumulated().isEmpty()) {
            blocks.add(TextBlock.builder().text(text.getAccumulated()).build());
        }
        blocks.addAll(requested);
        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT).content(blocks).build();
        return new Round(assistant, text.getAccumulated(), requested,
                last.getUsage() == null ? null
                        : new TokenUsage(last.getUsage().getInputTokens(), last.getUsage().getOutputTokens(),
                                last.getUsage().getTotalTokens()));
    }

    private List<ToolResultBlock> execute(long userId, List<ToolUseBlock> requested, List<String> discovered) {
        List<ToolResultBlock> results = new ArrayList<>();
        for (ToolUseBlock call : requested) {
            AgentToolBridge.Outcome outcome = tools.run(userId, Harness.ASSISTANT, call.getName(), call.getInput());
            discovered.addAll(outcome.discovered());
            results.add(new ToolResultBlock(call.getId(), call.getName(),
                    TextBlock.builder().text(outcome.text()).build()));
        }
        return results;
    }

    private record Round(Msg assistantMessage, String text, List<ToolUseBlock> toolCalls, TokenUsage usage) {
    }
}
