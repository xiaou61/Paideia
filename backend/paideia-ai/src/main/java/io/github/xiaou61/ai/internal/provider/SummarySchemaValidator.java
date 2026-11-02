package io.github.xiaou61.ai.internal.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.SummaryCard;
import io.github.xiaou61.ai.internal.support.ModelJson;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 卡片字段校验。
 *
 * <p>为什么必须有它：模型返回的**空内容**是本项目已知的真实故障模式——DeepSeek 的思考 token
 * 会吃掉 `max_tokens`，吃满时它**静默返回空**而不是报错。没有这道校验，空结果会被当成
 * "生成成功"写进缓存，之后所有用户打开这条内容都拿到一张空白卡片，而且因为命中缓存
 * 永远不会自愈。
 *
 * <p>所以：解析失败、字段缺失、字段为空、超长，一律按失败处理，且**失败结果绝不落库**。
 */
@Component
class SummarySchemaValidator {

    private static final int MAX_HEADLINE = 120;
    private static final int MAX_SECTION_HEADING = 60;
    private static final int MAX_SECTION_BODY = 2000;
    private static final int MAX_SECTIONS = 8;

    // 自建而不是注入容器里的 ObjectMapper：这个 Boot 4 组合里容器并没有暴露该 Bean
    // （全仓其它模块也都不用它）。这里只做"解析模型输出 / 序列化卡片"，用默认配置即可。
    private final ObjectMapper objectMapper = new ObjectMapper();

    SummaryCard validate(String rawModelOutput) {
        if (rawModelOutput == null || rawModelOutput.isBlank()) {
            throw failure("模型没有返回任何内容");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(rawModelOutput));
        } catch (Exception exception) {
            // 不把原始输出塞进异常：它可能很长，也可能被夹带进日志
            throw failure("模型返回的内容不是合法 JSON");
        }

        String headline = text(root, "headline");
        if (headline == null || headline.isBlank()) {
            throw failure("模型返回的卡片缺少 headline");
        }
        if (headline.length() > MAX_HEADLINE) {
            throw failure("headline 超长");
        }

        JsonNode sectionsNode = root.get("sections");
        if (sectionsNode == null || !sectionsNode.isArray() || sectionsNode.isEmpty()) {
            throw failure("模型返回的卡片没有任何小节");
        }
        if (sectionsNode.size() > MAX_SECTIONS) {
            throw failure("小节数量超出上限");
        }

        List<SummaryCard.SummarySection> sections = new ArrayList<>();
        for (JsonNode node : sectionsNode) {
            String heading = text(node, "heading");
            String body = text(node, "body");
            if (heading == null || heading.isBlank()) {
                throw failure("有小节缺少标题");
            }
            if (body == null || body.isBlank()) {
                throw failure("有小节的正文为空");
            }
            if (heading.length() > MAX_SECTION_HEADING || body.length() > MAX_SECTION_BODY) {
                throw failure("小节内容超长");
            }
            sections.add(new SummaryCard.SummarySection(heading.trim(), body.trim()));
        }
        return new SummaryCard(headline.trim(), sections);
    }

    /** 模型偶尔仍会套一层 ```json 代码块；只在整段被包住时剥掉，不做花哨的清洗。 */
        private static String stripFence(String raw) {
        return ModelJson.stripFence(raw);
    }

        private static String text(JsonNode node, String field) {
        return ModelJson.textOrNull(node, field);
    }

    private static BizException failure(String reason) {
        return new BizException(ErrorCode.INTERNAL, "模型没有返回可用的精简结果（" + reason + "）");
    }
}
