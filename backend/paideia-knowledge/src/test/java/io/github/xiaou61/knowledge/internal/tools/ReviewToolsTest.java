package io.github.xiaou61.knowledge.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.knowledge.internal.learning.LearningProgressService;
import io.github.xiaou61.knowledge.internal.learning.LearningReviewService;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 复习工具（WORK-044 TASK-009）。 */
class ReviewToolsTest {

    private static final ToolContext CALLER =
            new ToolContext(7L, "learner", Set.of(ToolScope.READ), ToolContext.Origin.EXTERNAL);

    private final LearningReviewService reviews = mock(LearningReviewService.class);
    private final LearningProgressService progress = mock(LearningProgressService.class);

    private static LearningReviewService.ReviewDueView due(long entryId, String title, int stage) {
        return new LearningReviewService.ReviewDueView(entryId, title, 3L, "索引原理",
                Instant.parse("2026-10-26T00:00:00Z"), stage);
    }

    private static LearningProgressService.View progress(long entryId, String title) {
        return new LearningProgressService.View(entryId, title, "reading", "in_progress", 0,
                null, 0, null, Map.of(), Map.of());
    }

    @Test
    void descriptorsMatchCatalogRules() {
        ToolDescriptor due = new ReviewTools.Due(reviews).descriptor();
        ToolDescriptor plan = new ReviewTools.Plan(reviews, progress).descriptor();

        assertThat(due.name()).isEqualTo("review_due");
        assertThat(plan.name()).isEqualTo("review_plan");
        assertThat(due.toolset()).isEqualTo("review");
        assertThat(plan.toolset()).isEqualTo("review");
        assertThat(due.name()).startsWith("review_");
        assertThat(plan.name()).startsWith("review_");
        assertThat(due.readOnly()).isTrue();
        assertThat(plan.readOnly()).isTrue();
        assertThat(due.defaultGroup()).as("不进默认视图").isFalse();
        assertThat(plan.defaultGroup()).as("不进默认视图").isFalse();
    }

    @Test
    void dueRendersEachEntry() {
        when(reviews.due(anyLong(), any())).thenReturn(new LearningReviewService.DueList(
                List.of(due(11L, "B+ 树", 2)), 1));

        ToolResult result = new ReviewTools.Due(reviews).call(CALLER, Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("entryId=11").contains("B+ 树")
                .contains("索引原理").contains("阶段=2");
    }

    @Test
    void dueSaysSoWhenNothingIsDue() {
        when(reviews.due(anyLong(), any())).thenReturn(new LearningReviewService.DueList(List.of(), 0));

        assertThat(new ReviewTools.Due(reviews).call(CALLER, Map.of()).text())
                .contains("没有到期");
    }

    /** 超过 50 条时截断并说明还有多少——不说明的话模型会以为这就是全部。 */
    @Test
    void dueTruncatesAndSaysHowManyLeft() {
        List<LearningReviewService.ReviewDueView> many = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(due(i, "条目" + i, 1));
        }
        when(reviews.due(anyLong(), any())).thenReturn(new LearningReviewService.DueList(many, 60));

        String text = new ReviewTools.Due(reviews).call(CALLER, Map.of()).text();

        assertThat(text).contains("还有 10 条未列出");
    }

    @Test
    void planSummarizesStageDistribution() {
        when(reviews.due(anyLong(), any())).thenReturn(new LearningReviewService.DueList(
                List.of(due(1L, "a", 1), due(2L, "b", 1), due(3L, "c", 3)), 3));
        when(progress.listMine(anyLong(), anyInt())).thenReturn(List.of(progress(9L, "JVM 内存模型")));

        String text = new ReviewTools.Plan(reviews, progress).call(CALLER, Map.of()).text();

        assertThat(text).contains("待复习：3 条")
                .contains("阶段 1 = 2 条")
                .contains("阶段 3 = 1 条")
                .contains("JVM 内存模型");
    }

    @Test
    void planHandlesNoRecentReading() {
        when(reviews.due(anyLong(), any())).thenReturn(new LearningReviewService.DueList(List.of(), 0));
        when(progress.listMine(anyLong(), anyInt())).thenReturn(List.of());

        String text = new ReviewTools.Plan(reviews, progress).call(CALLER, Map.of()).text();

        assertThat(text).contains("待复习：0 条").contains("最近还没有阅读记录");
    }
}
