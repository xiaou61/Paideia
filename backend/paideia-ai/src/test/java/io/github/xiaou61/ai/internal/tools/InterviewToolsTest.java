package io.github.xiaou61.ai.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.InterviewEvaluation;
import io.github.xiaou61.ai.internal.interview.InterviewReviewService;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 面试工具（WORK-044 TASK-007）。 */
class InterviewToolsTest {

    private static final ToolContext CALLER =
            new ToolContext(7L, "learner", Set.of(ToolScope.READ), ToolContext.Origin.EXTERNAL);

    private final InterviewReviewService reviews = mock(InterviewReviewService.class);

    private static InterviewReviewService.EvaluationView evaluation(long id, int overall) {
        return new InterviewReviewService.EvaluationView(id,
                new InterviewEvaluation(overall,
                        List.of(new InterviewEvaluation.Dimension("技术准确性", 4, "基本答对")),
                        List.of("表达清楚"),
                        List.of("多讲项目细节")),
                Instant.parse("2026-10-20T10:00:00Z"));
    }

    @Test
    void descriptorMatchesCatalogRules() {
        ToolDescriptor descriptor = new InterviewTools.ListEvaluations(reviews).descriptor();

        assertThat(descriptor.name()).isEqualTo("interview_list");
        assertThat(descriptor.toolset()).isEqualTo("interview");
        assertThat(descriptor.name()).startsWith("interview_");
        assertThat(descriptor.readOnly()).isTrue();
        assertThat(descriptor.defaultGroup()).as("不进默认视图").isFalse();
    }

    @Test
    void listRendersScorePerSession() {
        when(reviews.listMine(7L)).thenReturn(List.of(evaluation(5L, 4)));

        ToolResult result = new InterviewTools.ListEvaluations(reviews).call(CALLER, Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("id=5").contains("总分=4");
    }

    @Test
    void listSaysSoWhenEmpty() {
        when(reviews.listMine(7L)).thenReturn(List.of());

        assertThat(new InterviewTools.ListEvaluations(reviews).call(CALLER, Map.of()).text())
                .contains("还没有任何面试评估");
    }

    @Test
    void getRendersDimensionsAndSuggestions() {
        when(reviews.listMine(7L)).thenReturn(List.of(evaluation(5L, 4)));

        String text = new InterviewTools.GetEvaluation(reviews)
                .call(CALLER, Map.of("evaluationId", 5)).text();

        assertThat(text)
                .contains("## 各维度").contains("技术准确性").contains("基本答对")
                .contains("## 优势").contains("表达清楚")
                .contains("## 改进建议").contains("多讲项目细节");
    }

    /** 别人的 id 与不存在的 id 走同一条。 */
    @Test
    void getTreatsOtherPeoplesEvaluationAsNotFound() {
        when(reviews.listMine(7L)).thenReturn(List.of(evaluation(5L, 4)));

        ToolResult result = new InterviewTools.GetEvaluation(reviews)
                .call(CALLER, Map.of("evaluationId", 999));

        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("没有找到");
    }

    @Test
    void getRequiresId() {
        assertThat(new InterviewTools.GetEvaluation(reviews).call(CALLER, Map.of()).text())
                .contains("缺少");
    }
}
