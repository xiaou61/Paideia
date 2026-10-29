package io.github.xiaou61.ai.internal.conversation;

import io.github.xiaou61.ai.Harness;

/**
 * harness 注册表（WORK-010 T-04）。
 *
 * <p><b>为什么是注册表而不是一堆 if</b>：新增一种行为要改的地方只有这一处 + 一个新提示词类。
 * 调用方问它"这套行为用哪段系统提示词"，不必认识具体是哪个类。
 *
 * <p><b>为什么不合并成一张表放在配置里</b>：提示词是代码（有版本号、有单测、有既有的"一个任务
 * 一个类"纪律）。放配置会让它们脱离类型检查与代码评审，而它们直接决定模型怎么说话。
 *
 * <p>工具分层不在这里：工具是"能做什么"，与提示词是"怎么说话"分开维护（见 {@code AssistantTools}
 * 的 {@code schemas(Harness)}）。两者只靠 {@link Harness} 这个枚举对齐。
 */
public final class HarnessPrompts {

    private HarnessPrompts() {
    }

    /**
     * 这套行为用的系统提示词。
     *
     * <p>穷尽 switch 且**没有 default**：新增一个 harness 时编译器会强制这里也加一条，
     * 而不是静默落到通用助手的提示词上（那会让"面试"悄悄变成闲聊，且没有任何报错）。
     */
    static String system(Harness harness) {
        return switch (harness) {
            case ASSISTANT -> ConversationPrompt.system();
            case INTERVIEW -> InterviewPrompt.system();
            case REVIEW -> ReviewPrompt.system();
            case TUTOR -> TutorPrompt.system();
            case PLANNER -> PlannerPrompt.system();
        };
    }

    /**
     * 提示词版本，写进 `ai_runs.prompt_version`。
     *
     * <p><b>必须由调用方在起一轮时带进 {@code RunSpec}</b>：版本号只有落库才有意义——
     * 改了提示词之后，前后两批行长得一模一样，就再也分不出"涨的那两个点来自哪一版"。
     * 这是**补不回来的**：没带版本的那一轮永远不知道用的是哪份提示词。
     *
     * <p>穷尽 switch，与 {@link #system} 同一纪律。
     */
    public static String version(Harness harness) {
        return switch (harness) {
            case ASSISTANT -> ConversationPrompt.VERSION;
            case INTERVIEW -> InterviewPrompt.VERSION;
            case REVIEW -> ReviewPrompt.VERSION;
            case TUTOR -> TutorPrompt.version();
            case PLANNER -> PlannerPrompt.version();
        };
    }
}
