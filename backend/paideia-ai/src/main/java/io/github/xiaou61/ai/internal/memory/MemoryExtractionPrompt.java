package io.github.xiaou61.ai.internal.memory;

import io.github.xiaou61.ai.ConversationTurn;
import io.github.xiaou61.ai.MemoryExtractor;
import java.util.List;
import java.util.Locale;

/**
 * 记忆自动整理的提示词（WORK-013）。
 *
 * <p><b>{@link #VERSION} 单独一个</b>（不与 {@code assistant-chat-v1} 共用）：将来出现"同一段对话
 * 为什么整理得不一样"时，要能分辨是"聊天提示词改了"还是"整理提示词改了"。
 *
 * <p><b>要求模型只回一段 JSON，不走框架的结构化输出通道</b>：AgentScope 对 DeepSeek 的结构化输出
 * 走的是"合成工具 + 强制工具调用"的回退路径，而本项目**已知**"工具调用与结构化输出不能在同一次
 * 调用里并用"；为了少一层协议，这里用提示词要求 JSON，再由 {@link MemoryCandidateParser} 逐字段
 * 校验——校验器本来就不可少（原生结构化输出也会字段漂移）。这一点与设计里写的
 * `response_format: json_object` 不同，属**有意偏离**，已记入更新历史。
 *
 * <p>四条约束是"不让它胡来"的核心：只能依据对话内容、宁少勿多、同一件事要 `UPDATE` 不要 `ADD`、
 * 没有值得记的就回空数组。
 *
 * <p>它必须是 {@code public}：调用方 {@code AgentScopeMemoryExtractor} 在
 * {@code internal.provider} 里（本模块内跨包，与 {@code GuardedHttpTransport} 同理）。
 */
public final class MemoryExtractionPrompt {

    /**
     * 与设计一致的版本号；改文案必须改它。
     *
     * <p>public 是为了让离线评测（`MemoryEvalTest`）在报告里写明"这份数字对应哪版提示词"——
     * 没有它，跨版本的指标差异永远无法归因。
     */
    public static final String VERSION = "memory-extract-v1";

    private MemoryExtractionPrompt() {
    }

    public static String system() {
        return """
                你在维护一份"关于某个学习者"的长期记忆。给你的是这个人最近几轮与助手的对话，
                以及已经记住的内容。你的任务：判断这几轮里有哪些**值得长期记住**的信息，
                并给出对记忆库的操作。
                只输出一个 JSON 对象，不要解释、不要用 markdown 代码块包裹。
                JSON 结构：
                {"candidates":[{"action":"ADD|UPDATE|DELETE|SKIP","store":"structured|text",\
                "targetId":12,"kind":"weak_topic|goal|preference","refId":3,"content":"一句话",\
                "reason":"为什么"}]}

                两个存放位置（store）：
                - structured：关于学习者的**既成事实**，必须给 kind，取值只能是
                  weak_topic（还不懂/卡住的知识点）、goal（学习目标）、preference（偏好）。
                - text：他说过的**话**、习惯、背景（不需要 kind）。

                四种动作（action）：
                - ADD：新增一条。
                - UPDATE：**已有记忆里那条说法要改**（更准/更完整/已过期）。必须给 targetId，
                  以及该条所在的 store。同一件事的第二次说法**应该 UPDATE，不要 ADD**。
                - DELETE：已有记忆**已经不成立**（例如他后来学会了、目标已变）。必须给 targetId。
                - SKIP：看见了但不值得记。可以不给 content。

                规则：
                1. 只依据给定对话，不要推测、不要补充对话里没有的事实。
                2. **宁少勿多**。寒暄、一次性提问、与这个人本身无关的知识内容都不值得记。
                3. content 是一句提炼后的陈述，不超过 50 字，用第三人称（"他…"），不要照抄整段原话。
                4. targetId 只能取自「已经记住的内容」里给出的编号，**不许自己编**。
                5. 没有值得记的就回 {"candidates":[]}。
                """;
    }

    /** 用户侧：先给已有记忆（要带编号，模型才能引用），再给这几轮对话。 */
    public static String user(List<MemoryExtractor.MemoryLine> memories, List<ConversationTurn> turns) {
        StringBuilder builder = new StringBuilder();
        builder.append("已经记住的内容（编号 | 位置 | 内容）：\n");
        if (memories.isEmpty()) {
            builder.append("（空）\n");
        } else {
            for (MemoryExtractor.MemoryLine line : memories) {
                builder.append("#").append(line.id()).append(" | ").append(line.store())
                        .append(" | ").append(line.text()).append('\n');
            }
        }
        builder.append("\n最近这几轮对话（旧 → 新）：\n");
        for (ConversationTurn turn : turns) {
            builder.append('[').append(turn.role().name().toLowerCase(Locale.ROOT)).append("] ")
                    .append(turn.content()).append('\n');
        }
        return builder.toString();
    }
}
