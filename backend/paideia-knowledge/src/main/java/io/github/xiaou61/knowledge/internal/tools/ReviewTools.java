package io.github.xiaou61.knowledge.internal.tools;

import io.github.xiaou61.knowledge.internal.learning.LearningProgressService;
import io.github.xiaou61.knowledge.internal.learning.LearningReviewService;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 复习域的外部工具（WORK-044 TASK-009）。
 *
 * <p>**只有读**。`start_session` / `submit` 刻意不做：它们是写操作、与训练营状态机耦合，
 * 外部助手乱调会把复习排期搞乱——而排期是"什么时候该复习哪一条"的唯一依据。
 *
 * <p>两个工具都只用既有服务，不新增 mapper 方法：`due` 与 `listMine` 已经能凑出"有什么要复习"
 * 与"复习到什么程度"这两件模型真正会问的事。
 */
public final class ReviewTools {

    private static final String GROUP = "review";
    private static final int MAX_LISTED = 50;

    private ReviewTools() {
    }

    /** 待复习条目。 */
    @Component
    public static class Due implements PaideiaTool {

        private final LearningReviewService reviews;

        Due(LearningReviewService reviews) {
            this.reviews = reviews;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "review_due", GROUP, "查看待复习条目",
                    "列出**当前调用者自己**已经到期、该复习的知识条目（标题、所属概念、"
                            + "到期时间、复习阶段）。用户问「我今天该复习什么」时用它。",
                    objectSchema(),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            LearningReviewService.DueList due = reviews.due(context.userId(), Instant.now());
            if (due.items().isEmpty()) {
                return ToolResult.ok("当前没有到期待复习的条目。");
            }
            List<String> lines = new ArrayList<>();
            int shown = 0;
            for (LearningReviewService.ReviewDueView item : due.items()) {
                if (shown >= MAX_LISTED) {
                    break;
                }
                shown++;
                lines.add("entryId=" + item.entryId() + "  " + item.title()
                        + "  概念=" + (item.conceptName() == null ? "—" : item.conceptName())
                        + "  阶段=" + item.stage()
                        + "  到期=" + item.reviewDueAt());
            }
            if (due.total() > shown) {
                lines.add("（还有 " + (due.total() - shown) + " 条未列出）");
            }
            return ToolResult.ok(String.join("\n", lines));
        }
    }

    /** 复习计划概览。 */
    @Component
    public static class Plan implements PaideiaTool {

        private final LearningReviewService reviews;
        private final LearningProgressService progress;

        Plan(LearningReviewService reviews, LearningProgressService progress) {
            this.reviews = reviews;
            this.progress = progress;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "review_plan", GROUP, "复习计划概览",
                    "汇总**当前调用者自己**的复习状态：待复习多少条、各复习阶段分布、"
                            + "最近在读哪些条目。用户问「我复习得怎么样」「还有多少没复习」时用它。",
                    objectSchema(),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            LearningReviewService.DueList due = reviews.due(context.userId(), Instant.now());
            // 阶段分布用 TreeMap：输出顺序稳定，模型与用户都能对比两次调用
            Map<Integer, Integer> byStage = new TreeMap<>();
            for (LearningReviewService.ReviewDueView item : due.items()) {
                byStage.merge(item.stage(), 1, Integer::sum);
            }

            StringBuilder out = new StringBuilder();
            out.append("待复习：").append(due.total()).append(" 条\n");
            if (!byStage.isEmpty()) {
                out.append("按阶段：");
                List<String> parts = new ArrayList<>();
                byStage.forEach((stage, count) -> parts.add("阶段 " + stage + " = " + count + " 条"));
                out.append(String.join("，", parts)).append('\n');
            }

            List<LearningProgressService.View> recent = progress.listMine(context.userId(), 5);
            if (recent.isEmpty()) {
                out.append("最近还没有阅读记录。\n");
            } else {
                out.append("\n最近在读：\n");
                for (LearningProgressService.View view : recent) {
                    out.append("- entryId=").append(view.entryId())
                            .append("  ").append(view.title() == null ? "（无标题）" : view.title())
                            .append('\n');
                }
            }
            return ToolResult.ok(out.toString());
        }
    }

    static Map<String, Object> objectSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }
}
