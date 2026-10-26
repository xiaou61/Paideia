package io.github.xiaou61.knowledge.internal.tools;

import io.github.xiaou61.knowledge.internal.question.QuestionAttemptMapper;
import io.github.xiaou61.knowledge.internal.question.QuestionConceptAdminService;
import io.github.xiaou61.knowledge.internal.question.QuestionConceptSuggester;
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
 * 题目标注的管理工具组（WORK-038 TASK-006，4 个）。
 *
 * <p>题目标注是内容质量的一部分：模型可以读题目、看候选概念、提交标注，但**只写标注表**，
 * 不改题目本身（题目在条目正文的围栏块里）。
 */
public final class LearningAdminTools {

    private static final String GROUP = "admin_learning";
    private static final int MAX_EXPORT = 500;

    private LearningAdminTools() {
    }

    /** 某条目下的题目与已确认概念。 */
    @Component
    public static class ListQuestions implements PaideiaTool {

        private final QuestionConceptAdminService service;

        ListQuestions(QuestionConceptAdminService service) {
            this.service = service;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_learning_list_questions", GROUP, "列出条目的题目",
                    "列出某条目正文里的全部题目（blockKey / questionKey / 题干 / 选项数 / 正确答案下标）"
                            + "以及每个题目已确认的概念。标注前先用它定位题目。",
                    schema(Map.of("entryId", num("条目 id")), "entryId"),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long entryId = longOf(arguments, "entryId");
                if (entryId == null) {
                    return ToolResult.fail("缺少 entryId");
                }
                List<QuestionConceptAdminService.QuestionView> questions = service.list(entryId);
                if (questions.isEmpty()) {
                    return ToolResult.ok("这个条目里没有题目。");
                }
                List<String> lines = questions.stream()
                        .map(question -> question.blockKey() + " / " + question.questionKey()
                                + " 题干=" + question.stem()
                                + " 选项=" + question.optionCount() + " 答案=" + question.answerIndex()
                                + " 概念=" + tagged(question.concepts()))
                        .toList();
                return ToolResult.ok(join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取题目失败：" + messageOf(failure));
            }
        }

        private static String tagged(List<QuestionConceptAdminService.Tagged> concepts) {
            if (concepts.isEmpty()) {
                return "无";
            }
            return concepts.stream()
                    .map(item -> item.name() + "(id=" + item.conceptId() + ", w=" + item.weight() + ")")
                    .reduce((left, right) -> left + " " + right)
                    .orElse("无");
        }
    }

    /** 概念候选（不写库）。 */
    @Component
    public static class SuggestConcepts implements PaideiaTool {

        private final QuestionConceptAdminService service;

        SuggestConcepts(QuestionConceptAdminService service) {
            this.service = service;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_learning_suggest_concepts", GROUP, "推荐题目概念",
                    "对某一题给出候选概念（词面与结构打分，**不写库**）。确认后用 "
                            + "admin_learning_set_question_concepts 提交。",
                    schema(Map.of(
                            "entryId", num("条目 id"),
                            "blockKey", str("题目的 blockKey"),
                            "questionKey", str("题目的 questionKey"),
                            "limit", num("最多几条候选，默认 5，上限 20")),
                            "entryId", "blockKey", "questionKey"),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long entryId = longOf(arguments, "entryId");
                String blockKey = stringOf(arguments, "blockKey");
                String questionKey = stringOf(arguments, "questionKey");
                if (entryId == null || blockKey == null || questionKey == null) {
                    return ToolResult.fail("entryId、blockKey、questionKey 必填");
                }
                int limit = clamp(limitOf(arguments, "limit", 5));
                List<QuestionConceptSuggester.Suggestion> suggestions = service.suggestions(
                        entryId, blockKey, questionKey, limit);
                if (suggestions.isEmpty()) {
                    return ToolResult.ok("没有候选概念。");
                }
                List<String> lines = suggestions.stream()
                        .map(item -> "id=" + item.conceptId() + " " + item.name()
                                + " 得分=" + item.score()
                                + (item.hits().isEmpty() ? "" : " 命中=" + String.join("/", item.hits())))
                        .toList();
                return ToolResult.ok(join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("生成候选失败：" + messageOf(failure));
            }
        }
    }

    /** 提交某一题的概念标注（整组替换）。 */
    @Component
    public static class SetQuestionConcepts implements PaideiaTool {

        private final QuestionConceptAdminService service;

        SetQuestionConcepts(QuestionConceptAdminService service) {
            this.service = service;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_learning_set_question_concepts", GROUP, "设置题目的概念标注",
                    "**整组替换**这道题的概念标注。concepts 是对象数组，每项 {conceptId, weight}；"
                            + "weight 取 0-1，表示这题考这个概念的程度。source 一般写 author。"
                            + "概念 id 用 admin_content_save_concept 新建，或用 admin_learning_list_questions 看已有标注。",
                    schema(Map.of(
                            "entryId", num("条目 id"),
                            "blockKey", str("题目的 blockKey"),
                            "questionKey", str("题目的 questionKey"),
                            "concepts", Map.of("type", "array", "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "conceptId", Map.of("type", "integer",
                                                    "description", "概念 id"),
                                            "weight", Map.of("type", "number",
                                                    "description", "权重 0-1")),
                                    "required", List.of("conceptId", "weight"))),
                            "source", str("来源，一般写 author")),
                            "entryId", "blockKey", "questionKey", "concepts"),
                    null, false, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long entryId = longOf(arguments, "entryId");
                String blockKey = stringOf(arguments, "blockKey");
                String questionKey = stringOf(arguments, "questionKey");
                Object raw = arguments == null ? null : arguments.get("concepts");
                if (entryId == null || blockKey == null || questionKey == null || !(raw instanceof List<?> list)) {
                    return ToolResult.fail("entryId、blockKey、questionKey、concepts 必填");
                }
                List<QuestionConceptAdminService.TagRequest> concepts = new ArrayList<>();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) {
                        return ToolResult.fail("concepts 里每一项都必须是对象");
                    }
                    Long conceptId = number(map.get("conceptId"));
                    Double weight = decimal(map.get("weight"));
                    if (conceptId == null || weight == null) {
                        return ToolResult.fail("concepts 每项需要 conceptId 与 weight");
                    }
                    concepts.add(new QuestionConceptAdminService.TagRequest(conceptId,
                            Math.max(0, Math.min(weight, 1))));
                }
                service.replace(entryId, blockKey, questionKey, concepts, stringOf(arguments, "source"));
                return ToolResult.ok("已替换标注：题目 " + blockKey + "/" + questionKey
                        + " 共 " + concepts.size() + " 个概念");
            } catch (RuntimeException failure) {
                return ToolResult.fail("提交标注失败：" + messageOf(failure));
            }
        }
    }

    /** 评估导出：最近的作答明细。 */
    @Component
    public static class ExportEvaluation implements PaideiaTool {

        private final QuestionConceptAdminService service;

        ExportEvaluation(QuestionConceptAdminService service) {
            this.service = service;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_learning_export_evaluation", GROUP, "导出最近作答明细",
                    "按时间倒序返回最近的作答记录（用户、条目、题目、对错、耗时、判定方式），"
                            + "用于离线评估选题与掌握度模型。limit 上限 500。",
                    schema(Map.of("limit", num("最多几条，默认 100，上限 500"))),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                int limit = (int) Math.min(Math.max(limitOf(arguments, "limit", 100), 1), MAX_EXPORT);
                List<QuestionAttemptMapper.AttemptRow> rows = service.exportAttempts(limit);
                if (rows.isEmpty()) {
                    return ToolResult.ok("还没有作答记录。");
                }
                List<String> lines = rows.stream()
                        .map(row -> row.createdAt() + " 用户=" + row.userId() + " 条目=" + row.entryId()
                                + " " + row.blockKey() + "/" + row.questionKey()
                                + " 正确=" + (row.correct() ? "是" : "否")
                                + " 耗时=" + (row.latencyMs() == null ? "—" : row.latencyMs() + "ms")
                                + " 判定=" + row.judgedBy())
                        .toList();
                return ToolResult.ok("共 " + rows.size() + " 条：\n" + join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("导出失败：" + messageOf(failure));
            }
        }
    }

    // --------------------------------------------------------------- helpers

    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> num(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static String stringOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    static Long longOf(Map<String, Object> arguments, String key) {
        return number(arguments == null ? null : arguments.get(key));
    }

    static Long number(Object raw) {
        if (raw instanceof Number value) {
            return value.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Double decimal(Object raw) {
        if (raw instanceof Number value) {
            return value.doubleValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static int limitOf(Map<String, Object> arguments, String key, int fallback) {
        Long raw = number(arguments == null ? null : arguments.get(key));
        return raw == null || raw <= 0 ? fallback : raw.intValue();
    }

    static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 20);
    }

    static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
