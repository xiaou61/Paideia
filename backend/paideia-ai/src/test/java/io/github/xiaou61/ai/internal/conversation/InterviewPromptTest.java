package io.github.xiaou61.ai.internal.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.AssistantConversation;
import io.github.xiaou61.ai.Harness;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 面试官提示词的护栏（WORK-010，求职季那一节 2026-11-09）。
 *
 * <p>与 {@link TutorPromptTest} 同一档保证：只验**这几条还在**，不验模型是否真的照做
 * （那要跑上游，已登记为已知缺口）。改文案可以，改掉这几条等于拆掉约束。
 */
class InterviewPromptTest {

    @Test
    @DisplayName("两条硬规则必须在：一次只问一个、不替他回答")
    void hardRulesArePresent() {
        String system = InterviewPrompt.system();
        assertThat(system).contains("一次只问一个问题");
        assertThat(system).contains("不替他回答");
    }

    @Test
    @DisplayName("求职季换侧重：秋招铺广度、春招抠深度，且都写清楚了")
    void seasonChangesFocus() {
        String system = InterviewPrompt.system();
        assertThat(system).contains("【求职季】");
        assertThat(system).contains("秋招");
        assertThat(system).contains("广度");
        assertThat(system).contains("春招");
        assertThat(system).contains("深度");
    }

    @Test
    @DisplayName("没给求职季时不许模型自己猜——猜错的侧重比不侧重更糟")
    void seasonIsNotGuessed() {
        assertThat(InterviewPrompt.system()).contains("没给就别自己假设");
    }

    @Test
    @DisplayName("版本号非空，且与通用助手、导师都不同（改提示词要能分辨是哪份）")
    void versionIsSetAndDistinct() {
        assertThat(InterviewPrompt.VERSION).isNotBlank();
        assertThat(InterviewPrompt.VERSION).isNotEqualTo(ConversationPrompt.VERSION);
        assertThat(InterviewPrompt.VERSION).isNotEqualTo(TutorPrompt.version());
    }

    @Test
    @DisplayName("【求职季】跟着 pageContext 进了系统提示词，面试规则同时在场")
    void seasonReachesSystemPrompt() {
        // 这条是把"前端传参 → 服务端装配"这一段串起来验：前端只往 pageContext 里塞一行
        // `【求职季】秋招`，这里断言它真的出现在最终发给模型的系统提示词里，且第 8 条也在。
        AssistantConversation.Request request = new AssistantConversation.Request(
                1L, List.of(), "开始面试。目标岗位：后端开发。", null, null, "", List.of(),
                Harness.INTERVIEW, "【求职季】秋招", null);

        String prompt = AgentScopeConversation.systemPrompt(request);

        assertThat(prompt).contains("【求职季】秋招");
        assertThat(prompt).contains("一次只问一个问题");
        assertThat(prompt).contains("没给就别自己假设");
    }

    @Test
    @DisplayName("开场白带岗位；没有岗位时不出现空引号")
    void openingMentionsRole() {
        assertThat(InterviewPrompt.opening("后端开发", null)).contains("「后端开发」");
        assertThat(InterviewPrompt.opening(null, null)).doesNotContain("目标岗位");
        assertThat(InterviewPrompt.opening("  ", null)).doesNotContain("目标岗位");
    }

    @Test
    @DisplayName("开场白带上题源提示，但说清不必局限")
    void openingMentionsEntryHint() {
        String opening = InterviewPrompt.opening("前端", "《闭包与作用域》");
        assertThat(opening).contains("《闭包与作用域》");
        assertThat(opening).contains("不必局限于");
    }
}
