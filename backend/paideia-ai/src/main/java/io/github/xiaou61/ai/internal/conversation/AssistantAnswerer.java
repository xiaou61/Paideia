package io.github.xiaou61.ai.internal.conversation;

import io.github.xiaou61.ai.AssistantConversation;
import io.github.xiaou61.ai.ConversationTurn;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import java.util.List;
import java.util.Optional;

/**
 * 非流式问答端口（WORK-019 REQ-028）：外部 MCP 客户端问一句、平台答一句。
 *
 * <p><b>为什么不复用 {@link AssistantConversation}</b>：那是**流式**端口，带会话、带历史、带
 * {@code Listener}；MCP 工具调用是一问一答，把流式接口硬塞进工具调用会同时污染两侧的语义。
 *
 * <p>与对话端口的三条区别，都是刻意的：
 * <ul>
 *   <li><b>不带历史</b>：外部调用不属于用户的任何一段会话；</li>
 *   <li><b>不落库</b>：不写 `ai_conversations` / `ai_messages`（REQ-028），所以用户的会话列表
 *       不会凭空多出一段"我在别的软件里问过"；</li>
 *   <li><b>返回纯文本 + 用量</b>：MCP 工具结果就是文本，用量由调用方拿去结算。</li>
 * </ul>
 *
 * <p>`ponytail:` 一期只做单问单答（内部最多 4 轮工具），不做多会话、不做流式。真需要长任务时
 * 用 MCP 的 Tasks 扩展另开，而不是把这里做大。
 */
public interface AssistantAnswerer {

    /**
     * @param userId  调用者账号 id（工具/记忆/凭据都按它取）
     * @param question 用户的问题
     * @param entryId 可选的条目上下文；给了就把正文放进提示词
     * @param access  平台凭据
     */
    Answer answer(long userId, String question, Optional<Long> entryId, ModelAccess access);

    record Answer(String text, TokenUsage usage) {
    }

    /** 会话历史用不到，但实现需要知道"没有历史"这件事；用一个空列表表达。 */
    List<ConversationTurn> NO_HISTORY = List.of();
}
