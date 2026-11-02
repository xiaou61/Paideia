package io.github.xiaou61.knowledge.internal.question;

import io.github.xiaou61.platform.Hashes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 从条目正文里解析题目并给出稳定身份（WORK-033）。
 *
 * <p>题目**不复制入库**：正文是唯一权威，这里只负责「同一个身份」这件事。身份由
 * `entryId + blockKey + 题号` 组成，指纹再把题干/选项/答案算进去——内容一改指纹就变，
 * 旧作答统计不会被当成新题的证据。
 *
 * <p>只解析 quiz 围栏；坏 JSON 或缺少 id 的块整体跳过，不抛异常（内容作者写错不该让整页 500）。
 */
final class QuestionCatalog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern QUIZ_FENCE =
            Pattern.compile("```quiz\\s*\\n(.*?)\\n```", Pattern.DOTALL);

    /** 一道题的身份与判定所需的最小信息。 */
    record Question(String blockKey, String questionKey, String fingerprint, String stem,
                    List<String> options, int answerIndex) {
    }

    private QuestionCatalog() {
    }

    /** 解析正文里的全部题目，按出现顺序。 */
    static List<Question> parse(long entryId, String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<Question> result = new ArrayList<>();
        Matcher matcher = QUIZ_FENCE.matcher(body);
        while (matcher.find()) {
            JsonNode root;
            try {
                root = JSON.readTree(matcher.group(1));
            } catch (Exception exception) {
                continue;
            }
            String blockKey = root.path("id").asText("");
            JsonNode questions = root.path("questions");
            // 没有 id 的块无法形成稳定身份；宁可跳过，也不要用标题或下标编一个
            if (blockKey.isBlank() || !questions.isArray()) {
                continue;
            }
            for (int index = 0; index < questions.size(); index++) {
                JsonNode node = questions.get(index);
                String stem = node.path("question").asText("");
                JsonNode optionsNode = node.path("options");
                List<String> options = new ArrayList<>();
                if (optionsNode.isArray()) {
                    optionsNode.forEach(option -> options.add(option.asText("")));
                }
                int answer = node.path("answer").asInt(-1);
                if (stem.isBlank() || options.size() < 2 || answer < 0 || answer >= options.size()) {
                    continue;
                }
                String questionKey = blockKey + "#" + index;
                result.add(new Question(blockKey, questionKey,
                        fingerprint(entryId, blockKey, index, stem, options, answer),
                        stem, List.copyOf(options), answer));
            }
        }
        return result;
    }

    /** 按身份找一道题。找不到返回空——调用方据此返回 400，而不是静默记一条假作答。 */
    static Optional<Question> find(long entryId, String body, String blockKey, String questionKey) {
        if (blockKey == null || questionKey == null) {
            return Optional.empty();
        }
        return parse(entryId, body).stream()
                .filter(question -> question.blockKey().equals(blockKey)
                        && question.questionKey().equals(questionKey))
                .findFirst();
    }

    /** 身份指纹：内容一变就变。使用 `\u0001` 分隔，避免题干里出现 `|` 时撞串。 */
    static String fingerprint(long entryId, String blockKey, int index, String stem,
                              List<String> options, int answerIndex) {
        StringBuilder raw = new StringBuilder()
                .append(entryId).append('\u0001')
                .append(blockKey).append('\u0001')
                .append(index).append('\u0001')
                .append(stem == null ? "" : stem.strip()).append('\u0001');
        for (String option : options) {
            raw.append(option == null ? "" : option.strip()).append('\u0001');
        }
        raw.append(answerIndex);
        return Hashes.sha256Hex(raw.toString());
    }
}
