package io.github.xiaou61.ai.internal.tools;

import io.github.xiaou61.ai.InterviewEvaluation;
import io.github.xiaou61.ai.internal.interview.InterviewReviewService;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 面试域的外部工具（WORK-044 TASK-007）。
 *
 * <p>**只有读**。触发点评（`review`）刻意不做：那是一次真实上游调用、要扣积分，
 * 且需要整场对话作为输入；外部助手要评自己手里的对话，用自己的模型更合理。
 */
public final class InterviewTools {

    private static final String GROUP = "interview";

    /** 与 `InterviewReviewService.listMine` 内部的 LIMIT 50 对齐。 */
    private static final int MAX_LISTED = 50;

    private InterviewTools() {
    }

    /** 历次面试评估。 */
    @Component
    public static class ListEvaluations implements PaideiaTool {

        private final InterviewReviewService reviews;

        ListEvaluations(InterviewReviewService reviews) {
            this.reviews = reviews;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "interview_list", GROUP, "列出我的面试评估",
                    "列出**当前调用者自己**历次模拟面试的评分（id、总分、时间，新→旧）。"
                            + "用户问「我面试练过几次」「上次多少分」时用它；要看具体点评再用 interview_get。",
                    ResumeTools.objectSchema(Map.of()),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            List<InterviewReviewService.EvaluationView> items = reviews.listMine(context.userId());
            if (items.isEmpty()) {
                return ToolResult.ok("当前用户还没有任何面试评估。");
            }
            List<String> lines = new ArrayList<>();
            for (InterviewReviewService.EvaluationView item : items) {
                lines.add("id=" + item.id() + "  总分=" + scoreOf(item.evaluation())
                        + "  时间=" + item.createdAt());
            }
            return ToolResult.ok(String.join("\n", lines));
        }
    }

    /** 单次面试的完整点评。 */
    @Component
    public static class GetEvaluation implements PaideiaTool {

        private final InterviewReviewService reviews;

        GetEvaluation(InterviewReviewService reviews) {
            this.reviews = reviews;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "interview_get", GROUP, "读取一次面试点评",
                    "按 id 读取**当前调用者自己**一次模拟面试的完整评分（各维度、优势、改进建议）。"
                            + "id 来自 interview_list。不存在或不是本人的都返回「没有找到」。",
                    ResumeTools.objectSchema(Map.of("evaluationId",
                            Map.of("type", "integer", "description", "评估 id"))),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Long id = ResumeTools.longOf(arguments, "evaluationId");
            if (id == null) {
                return ToolResult.fail("缺少 evaluationId");
            }
            // 从本人列表里筛，不新增 mapper 方法：listMine 已经返回完整 evaluation，
            // 而它的 LIMIT 50 与"最近 50 次面试"这个自然范围一致
            return reviews.listMine(context.userId()).stream()
                    .filter(item -> item.id() == id)
                    .findFirst()
                    .map(item -> ToolResult.ok(render(item)))
                    .orElseGet(() -> ToolResult.fail("没有找到这次面试评估。"));
        }

        static String render(InterviewReviewService.EvaluationView view) {
            InterviewEvaluation evaluation = view.evaluation();
            StringBuilder out = new StringBuilder();
            out.append("面试评估 id=").append(view.id())
                    .append("  总分=").append(scoreOf(evaluation))
                    .append("  时间=").append(view.createdAt()).append('\n');
            if (evaluation.dimensions() != null && !evaluation.dimensions().isEmpty()) {
                out.append("\n## 各维度\n");
                for (InterviewEvaluation.Dimension dimension : evaluation.dimensions()) {
                    out.append("- ").append(dimension.name()).append("：").append(dimension.score());
                    if (dimension.comment() != null && !dimension.comment().isBlank()) {
                        out.append("（").append(dimension.comment().strip()).append("）");
                    }
                    out.append('\n');
                }
            }
            appendSection(out, "## 优势", evaluation.strengths());
            appendSection(out, "## 改进建议", evaluation.improvements());
            return out.toString();
        }
    }

    private static void appendSection(StringBuilder out, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        out.append('\n').append(title).append('\n');
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                out.append("- ").append(item.strip()).append('\n');
            }
        }
    }

    /** 总分。评估本身可能为 null（库里脏数据），那样显示"未知"而不是抛 NPE。 */
    private static String scoreOf(InterviewEvaluation evaluation) {
        return evaluation == null ? "未知" : String.valueOf(evaluation.overall());
    }
}
