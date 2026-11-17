package io.github.xiaou61.ai.internal.essay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 解析模型返回的抽题 JSON（WORK-025）。
 *
 * <p>逐字段校验而不是"反序列化失败就整批丢"：一道题格式不对不该让整篇面经的其它题一起作废。
 * 单条不合格的丢弃并计数，其余照收。
 *
 * <p>**空数组是合法结果**（帖子里没有技术问题），**空内容不是**（那是故障）——
 * 两者必须分开：把故障当成"没什么可抽的"会让每天的抓取静默失效而没人发现。
 */
@Component
class EssayQuestionParser {

    /** 题目长度上限：超过的多半是模型把整段解析塞进了标题。 */
    private static final int MAX_TITLE_CHARS = 120;

    /** 解析长度上限：MEDIUMTEXT 装得下，但过长的多半是模型跑飞了。 */
    private static final int MAX_ANSWER_CHARS = 4000;

    /** 解析长度下限：低于这个数基本是"略"或一句废话。 */
    private static final int MIN_ANSWER_CHARS = 20;

    /** 拒答/空话只在这么短的解析里判，免得误杀长解析里偶然出现的词。 */
    private static final int REFUSAL_MAX_CHARS = 40;

    /** 明显的拒答/占位话术。 */
    private static final List<String> REFUSAL_MARKERS = List.of(
            "无法回答", "无法确定", "不能回答", "作为AI", "作为 AI",
            "抱歉", "不知道", "不清楚", "无可奉告", "文中未提及", "没有相关信息", "未提及");

    /** 复述题目判定的最小题目长度：太短的题目跟答案撞在一起是巧合，不算重复。 */
    private static final int TITLE_ECHO_MIN_TITLE_CHARS = 6;

    private final ObjectMapper objectMapper = new ObjectMapper();

    EssayExtractionPrompt.Extraction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw failure("模型没有返回任何内容");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(raw));
        } catch (Exception exception) {
            throw failure("模型输出不是合法 JSON");
        }
        JsonNode array = root.path("questions");
        if (!array.isArray()) {
            throw failure("模型输出缺少 questions 数组");
        }

        List<EssayExtractionPrompt.ExtractedQuestion> questions = new ArrayList<>();
        for (JsonNode node : array) {
            String title = node.path("title").asText("").strip();
            String answer = node.path("answer").asText("").strip();
            if (title.isBlank() || title.length() > MAX_TITLE_CHARS) {
                continue;
            }
            if (answer.length() < MIN_ANSWER_CHARS || answer.length() > MAX_ANSWER_CHARS) {
                continue;
            }
            if (looksLikeJunk(title, answer)) {
                continue;
            }
            questions.add(new EssayExtractionPrompt.ExtractedQuestion(title, answer));
        }
        return new EssayExtractionPrompt.Extraction(questions);
    }

    /** 模型偶尔会套一层 ```json 围栏，去掉它再解析。 */
    private static String stripFence(String raw) {
        String trimmed = raw.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        String body = trimmed.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return closing < 0 ? body : body.substring(0, closing);
    }

    /**
     * 「答案自检」质检门（2026-11-17）：把明显没信息量的解析挡在入库之前。
     *
     * <p>纯程序化、零额外模型调用。挡两类：
     * <ul>
     *   <li>拒答 / 空话——模型没从帖子里拿到信息时的退化输出（"无法回答""略""不清楚"）。</li>
     *   <li>复述题目——解析归一化后几乎就是题目本身，说明没补任何内容。</li>
     * </ul>
     * 挡不住的（答案和题目细微对不上）需要模型自评，属另一层。
     */
    static boolean looksLikeJunk(String title, String answer) {
        String plain = answer == null ? "" : answer.strip();
        if (plain.length() <= REFUSAL_MAX_CHARS) {
            for (String marker : REFUSAL_MARKERS) {
                if (plain.contains(marker)) {
                    return true;
                }
            }
        }
        String normalizedTitle = normalize(title);
        String normalizedAnswer = normalize(answer);
        return normalizedTitle.length() >= TITLE_ECHO_MIN_TITLE_CHARS
                && !normalizedAnswer.isEmpty()
                && normalizedTitle.contains(normalizedAnswer);
    }

    /** 与判重同口径的归一化：去空白与中英文标点、统一小写。 */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\p{Punct}，。；：！？、“”‘’「」『』（）【】《》〈〉·…—～]+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static BizException failure(String message) {
        return new BizException(ErrorCode.INTERNAL, message);
    }
}
