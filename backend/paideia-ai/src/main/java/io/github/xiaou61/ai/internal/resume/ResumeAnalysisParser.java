package io.github.xiaou61.ai.internal.resume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.ResumeAnalysis;
import io.github.xiaou61.ai.internal.support.ModelJson;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 分析结果的解析与裁剪（WORK-018 TASK-003）。
 *
 * <p><b>要求条目必须有 JD 引证，但缺了不丢</b>：与简历解析那边相反。理由是两侧的失败代价不同——
 * 简历里一条凭空出现的经历会被用户当事实用，必须丢；而一条缺引证的 JD 要求只是少了一处出处，
 * 分桶与界面都还能用，丢掉反而让"我缺什么"少一条。
 *
 * <p>`term` 为空或超长的条目直接丢：分桶靠它做查找，空字符串会把"缺口"算成"命中"。
 */
@Component
public class ResumeAnalysisParser {

    private static final int MAX_TERMS = 40;
    private static final int MAX_TERM_CHARS = 60;
    private static final int MAX_QUOTE_CHARS = 300;
    private static final int MAX_SUMMARY_CHARS = 200;

    private final ObjectMapper mapper = new ObjectMapper();

    public ResumeAnalysis parse(String rawModelOutput) {
        JsonNode root = readObject(rawModelOutput);
        JsonNode requirementsNode = root.get("requirements");
        if (requirementsNode == null || !requirementsNode.isArray() || requirementsNode.isEmpty()) {
            // 与分析那条同一个教训：零结果不能当成"没有要求"。这里判失败，让调用方明确报错，
            // 而不是返回一个"这个岗位什么都不要"的空结果。
            throw failure("模型没有给出任何 JD 要求");
        }

        List<ResumeAnalysis.Requirement> requirements = new ArrayList<>();
        for (JsonNode node : requirementsNode) {
            if (requirements.size() >= MAX_TERMS) {
                break;
            }
            if (!node.isObject()) {
                continue;
            }
            String term = text(node, "term");
            if (term.isBlank()) {
                continue;
            }
            String trimmed = term.length() > MAX_TERM_CHARS ? term.substring(0, MAX_TERM_CHARS).trim() : term;
            String quote = text(node, "quote");
            if (quote.length() > MAX_QUOTE_CHARS) {
                quote = quote.substring(0, MAX_QUOTE_CHARS);
            }
            requirements.add(new ResumeAnalysis.Requirement(trimmed, quote));
        }
        if (requirements.isEmpty()) {
            throw failure("模型给出的 JD 要求全部为空");
        }

        String summary = text(root, "summary");
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS);
        }
        return new ResumeAnalysis(List.copyOf(requirements), summary);
    }

    private JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw failure("模型没有返回任何内容");
        }
        try {
            JsonNode node = mapper.readTree(stripFence(raw));
            if (node == null || !node.isObject()) {
                throw failure("模型返回的不是一个 JSON 对象");
            }
            return node;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure("模型返回的内容不是合法 JSON");
        }
    }

        private static String stripFence(String raw) {
        return ModelJson.stripFence(raw);
    }

        private static String text(JsonNode node, String field) {
        return ModelJson.textOrEmpty(node, field);
    }

    private static BizException failure(String reason) {
        return new BizException(ErrorCode.INTERNAL, "没有拿到可用的岗位分析结果（" + reason + "）");
    }
}
