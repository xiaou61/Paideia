package io.github.xiaou61.ai.internal.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.DistilledEntry;
import io.github.xiaou61.ai.internal.support.ModelJson;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 蒸馏产物字段校验（WORK-076 TASK-004）。
 *
 * <p>与 {@code SummarySchemaValidator} 同一纪律：模型返回空内容/非法 JSON/字段缺失是本项目的
 * 已知真实故障（DeepSeek 思考 token 吃满 max_tokens 会静默返回半截或空）。解析失败、title 空、
 * body 空一律按失败处理，**失败结果绝不落库**。
 */
@Component
class DistilledEntryValidator {

    private static final int MAX_TITLE = 200;
    private static final int MAX_CONCEPTS = 20;

    // 自建 ObjectMapper：与 SummarySchemaValidator 同一理由（Boot 4 组合里没有该 Bean）。
    private final ObjectMapper objectMapper = new ObjectMapper();

    DistilledEntry validate(String rawModelOutput) {
        if (rawModelOutput == null || rawModelOutput.isBlank()) {
            throw failure("模型没有返回任何内容");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(ModelJson.stripFence(rawModelOutput));
        } catch (Exception exception) {
            throw failure("模型返回的内容不是合法 JSON");
        }

        String title = ModelJson.textOrNull(root, "title");
        if (title == null || title.isBlank()) {
            throw failure("缺少标题");
        }
        title = title.trim();
        if (title.length() > MAX_TITLE) {
            throw failure("标题超长");
        }

        // 主字段 bodyMarkdown；容忍模型写成 body。
        String body = ModelJson.textOrNull(root, "bodyMarkdown");
        if (body == null) {
            body = ModelJson.textOrNull(root, "body");
        }
        if (body == null || body.isBlank()) {
            throw failure("缺少正文");
        }

        List<String> concepts = new ArrayList<>();
        JsonNode array = root.get("suggestedConcepts");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                String concept = node == null ? "" : node.asText("").trim();
                if (!concept.isEmpty()) {
                    concepts.add(concept);
                }
                if (concepts.size() >= MAX_CONCEPTS) {
                    break;
                }
            }
        }
        return new DistilledEntry(title, body.trim(), concepts);
    }

    private static BizException failure(String reason) {
        return new BizException(ErrorCode.INTERNAL, "模型没有返回可用的蒸馏结果（" + reason + "）");
    }
}
