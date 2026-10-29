package io.github.xiaou61.ai.internal.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 学习规划提示词（WORK-053）。
 *
 * <p>这几条是**行为约束**，被改掉不会报错，只会让规划助手退化成又一个闲聊窗口：
 * 先问再答、最多三个问题、不许伪造学习结果、生成数据要调工具。
 */
class PlannerPromptTest {

    @Test
    @DisplayName("要求先反问，并限制问题数量")
    void asksFirstWithLimit() {
        String system = PlannerPrompt.system();
        assertThat(system).contains("先问再建议");
        assertThat(system).contains("最多三个问题");
        assertThat(system).contains("能用默认值就用默认值");
    }

    @Test
    @DisplayName("禁止伪造学习结果——能建议，不能替用户写")
    void neverFakesLearningResults() {
        String system = PlannerPrompt.system();
        assertThat(system).contains("不得伪造学习结果");
        assertThat(system).contains("已加入计划");
        assertThat(system).contains("写入由他在页面上点按钮完成");
    }

    @Test
    @DisplayName("生成数据要走工具，不凭记忆写建表语句")
    void usesToolsForGeneration() {
        assertThat(PlannerPrompt.system()).contains("planner_build_dataset");
    }

    @Test
    @DisplayName("有版本号，且与其它场景不同——改文案要能追溯到具体版本")
    void hasOwnVersion() {
        assertThat(PlannerPrompt.version()).isNotBlank();
        assertThat(PlannerPrompt.version()).isNotEqualTo(ConversationPrompt.VERSION);
        assertThat(PlannerPrompt.version()).isNotEqualTo(PlannerPrompt.system());
    }
}
