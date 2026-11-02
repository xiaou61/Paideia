package io.github.xiaou61.ai.internal.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.InterviewEvaluation;
import io.github.xiaou61.ai.internal.support.ModelJson;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 点评返回值的校验与解析（WORK-010 T-10）。
 *
 * <p>与 {@code SummarySchemaValidator} 同一纪律：**解析失败一律判失败，绝不落库**。
 * 理由相同——模型返回空内容是本项目已知的真实故障模式（DeepSeek 把 `max_tokens` 吃在思考上时会
 * **静默返回空**），而一份空评分写进库之后，"进度与弱项"就永远错在那一次上。
 *
 * <p>校验比摘要更严一点：分数必须是 **1–5 的整数**。理由：评分会进"这个人哪块弱"的判断，
 * 而 0 分、负数、`"3/5"` 这种写法一旦被当成数字接受，后续比较就失真了。
 */
@Component
public class InterviewEvaluationParser {

    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;
    private static final int MAX_DIMENSIONS = 4;
    private static final int MAX_ITEMS = 4;
    private static final int MAX_COMMENT = ReviewPrompt.MAX_DIMENSION_COMMENT;
    private static final int MAX_IMPROVEMENT = ReviewPrompt.MAX_IMPROVEMENT_CHARS;

    // 自建而不是注入容器里的 ObjectMapper：与项目其它几处一致（容器并未暴露该 Bean）
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InterviewEvaluation parse(String rawModelOutput) {
        if (rawModelOutput == null || rawModelOutput.isBlank()) {
            throw failure("模型没有返回任何内容");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(rawModelOutput));
        } catch (Exception exception) {
            throw failure("模型返回的内容不是合法 JSON");
        }

        int overall = score(root, "overall", "总分");

        JsonNode dimensionsNode = root.get("dimensions");
        if (dimensionsNode == null || !dimensionsNode.isArray() || dimensionsNode.isEmpty()) {
            throw failure("没有任何维度评分");
        }
        if (dimensionsNode.size() > MAX_DIMENSIONS) {
            throw failure("维度数量超出上限");
        }
        List<InterviewEvaluation.Dimension> dimensions = new ArrayList<>();
        for (JsonNode node : dimensionsNode) {
            String name = text(node, "name");
            if (name == null || name.isBlank()) {
                throw failure("有维度缺少名称");
            }
            String comment = text(node, "comment");
            if (comment != null && comment.length() > MAX_COMMENT) {
                throw failure("维度评语超长");
            }
            dimensions.add(new InterviewEvaluation.Dimension(name.trim(), score(node, "score", name),
                    comment == null ? "" : comment.trim()));
        }

        List<String> strengths = items(root, "strengths", "优点");
        List<String> improvements = items(root, "improvements", "改进建议");
        if (strengths.isEmpty() && improvements.isEmpty()) {
            // 一份什么都没说的评估没有价值：宁可报错让用户重试，也不要落一条空壳
            throw failure("优点与改进建议都是空的");
        }
        return new InterviewEvaluation(overall, List.copyOf(dimensions),
                List.copyOf(strengths), List.copyOf(improvements));
    }

    /** 分数必须是 1–5 的整数。`3.0` 可以接受（有些模型这么写），`3.5` 与字符串一律拒。 */
    private static int score(JsonNode node, String field, String label) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw failure(label + "缺少分数");
        }
        if (!value.isNumber()) {
            throw failure(label + "的分数不是数字");
        }
        double asDouble = value.asDouble();
        if (asDouble != Math.rint(asDouble)) {
            throw failure(label + "的分数不是整数");
        }
        int asInt = (int) asDouble;
        if (asInt < MIN_SCORE || asInt > MAX_SCORE) {
            throw failure(label + "的分数超出 1–" + MAX_SCORE);
        }
        return asInt;
    }

    private static List<String> items(JsonNode root, String field, String label) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw failure(label + " 不是数组");
        }
        if (node.size() > MAX_ITEMS) {
            throw failure(label + " 条数超出上限");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > MAX_IMPROVEMENT) {
                throw failure(label + " 有一条超长");
            }
            values.add(text);
        }
        return values;
    }

    /** 模型偶尔仍会套一层 ```json；只在整段被包住时剥掉。 */
        private static String stripFence(String raw) {
        return ModelJson.stripFence(raw);
    }

        private static String text(JsonNode node, String field) {
        return ModelJson.textOrNull(node, field);
    }

    private static BizException failure(String reason) {
        return new BizException(ErrorCode.INTERNAL, "没有拿到可用的评估结果（" + reason + "）");
    }
}
