package io.github.xiaou61.ai.internal.conversation;

import java.util.List;

/**
 * 点评官的提示词（WORK-010）。
 *
 * <p><b>它必须是独立的一次调用、且不带任何工具</b>：DeepSeek 上"工具调用与结构化输出不能在同一次
 * 调用里并用"，而这次要的是**可解析的结构化评分**。所以点评走 {@code harness=review}，
 * 工具集为空（T-06 有断言）。这不是绕路，是把"评分"和"对话"当成两件不同的事。
 *
 * <p>要它输出 JSON 而不是 markdown 卡片：评分要落库、要能查进度，塞进消息文本等于不可查。
 *
 * <p>它必须是 {@code public}：调用方 {@code AgentScopeInterviewReviewer} 在
 * {@code internal.provider} 里（本模块内跨包，与 {@code MemoryExtractionPrompt} 同理）。
 */
public final class ReviewPrompt {

    /** 独立版本号：改字段或改约束都必须改它。 */
    public static final String VERSION = "review-v2";

    /**
     * 固定的四个评估维度（WORK-078）。
     *
     * <p>**为什么固定**：跨场做自适应要能按维度比较，模型每次自造维度名（"技术准确性"/"深度"/
     * "表达清晰度"…）就没法聚合。四者覆盖"知识 / 表达 / 经历 / 临场"，够用且不啰嗦。
     */
    public static final List<String> DIMENSIONS = List.of("技术深度", "表达结构", "项目经验", "应变能力");

    /** 各维度最多几个字、整体上限。与解析器保持一致。 */
    static final int MAX_DIMENSION_COMMENT = 200;
    static final int MAX_IMPROVEMENTS = 5;
    static final int MAX_IMPROVEMENT_CHARS = 120;

    private ReviewPrompt() {
    }

    public static String system() {
        return """
                你是面试评估员。给你一场面试的完整对话，你要给出一份可执行的评估。
                只输出一个 JSON 对象，不要解释、不要用 markdown 代码块包裹。

                JSON 结构：
                {"overall": 3, "dimensions": [{"name": "技术深度", "score": 3, "comment": "一句话"}],\
                "strengths": ["具体优点"], "improvements": ["具体可执行的改进建议"]}

                要求：
                1. `overall` 与每个 `score` 都是 **1 到 5 的整数**，5 最好。
                2. `dimensions` **必须且只能**是这四项、名字逐字照抄：技术深度、表达结构、项目经验、应变能力。
                   每项给一个 score 与一句话 comment。不要改名、不要增删、不要换顺序。
                3. `strengths` 与 `improvements` 各 1 到 4 条，每条一句话，**必须基于对话里的具体表现**，
                   不要写"继续努力""多练习"这种放之四海皆准的空话。
                4. **只依据这场对话**。没聊到的方向给该维度一个合理低分或中性分，不要编造他说过的话。
                5. 打分要诚实：答不上来就是低分，不要为了照顾情绪给虚高的分。
                """;
    }

    /**
     * 把对话拼成一段供评估的文本。
     *
     * @param transcript 已渲染成"面试官：… / 候选人：…"的整段对话
     */
    public static String user(String transcript) {
        return "以下是这场面试的完整对话（旧 → 新）：\n\n" + transcript;
    }
}
