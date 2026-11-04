package io.github.xiaou61.ai.internal.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.credential.AiProperties;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 五个"一次性小结果"的模型调用（WORK-067）。
 *
 * <p>共同点：**输入是页面手上现成的一段文字，输出是一小段文字**，不查库、不挂工具、
 * 不写任何表。所以它们共用一个基类 {@link SingleShotModelCaller} 与一个提示词版本号；
 * 差异只在各自的 system 提示与 JSON 形状。
 *
 * <p>刻意不做的事：不记住结果（没有表）、不自动触发（每个都由用户点按钮）、
 * 不把模型产出的内容当成事实回写（要点、讲解只展示）。
 */
@Component
public class QuickInsightCaller extends SingleShotModelCaller {

    /** 提示词版本：措辞一改就改它，观测面板才分得清"哪一版效果好"。 */
    public static final String PROMPT_VERSION = "quick-insight-v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REVIEW_SYSTEM = """
            你是耐心的小老师。用户做错了一道题，会给你题干、选项、他选的、正确项与（可能有的）解析。
            用 120 字以内解释三件事：①他选的为什么不对；②正确的为什么对；③一句话怎么记住。
            不要复述整道题，不要标题，不要 Markdown，只输出这段解释。
            """;

    private static final String NEXT_STEP_SYSTEM = """
            你是学习规划助手。用户会给你四段学习记录摘要（各线进度、卡住的关卡、到期的复习、最薄弱的概念）。
            给出**今天最该做的三件事**，按先后排序。
            硬性要求：每条一句话、40 字以内、必须引用记录里的具体数字或条目名；不要空话，不得只说"多练习"。
            只输出 JSON：{"steps":["...","...","..."]}，不要任何解释文字。
            """;

    private static final String CONCEPT_SYSTEM = """
            你是技术讲解员。用户会给你一个概念名与（可能有的）摘要。
            用 150 字以内的大白话解释它：先说"它解决什么问题"，再给一个最小例子（一行代码、一行 SQL 或日常类比）。
            不确定的部分不要编。只输出这段文字，不要标题，不要 Markdown。
            """;

    private static final String NOTE_SYSTEM = """
            把用户的一条笔记提炼成 3–5 个要点。只保留笔记里写过的事实，不许补充、不许推断。
            每条 25 字以内。只输出 JSON：{"points":["...","..."]}，不要任何解释文字。
            """;

    private static final String QUIZ_SYSTEM = """
            根据用户给的内容出一道单选题，考"理解"而不是背诵。四个选项，只有一个正确；
            错误项要像"看懂了但记错了一点"，不能明显荒谬。
            只输出 JSON：{"question":"...","options":["A","B","C","D"],"answer":0,"explain":"一句话解释为什么"}，
            answer 是正确选项的下标（0 起）。不要任何解释文字。
            """;

    /** 一道生成的题。`answer` 是下标，越界一律判为"格式不对"。 */
    public record QuizView(String question, List<String> options, int answer, String explain) {
    }

    /** 结果 + 本次用量：用量必须带回去，控制器按它结算积分。 */
    public record Result<T>(T value, TokenUsage usage) {
    }

    QuickInsightCaller(AiProperties properties, GuardedHttpTransport transport) {
        super(properties, transport);
    }

    public Result<String> explainReviewItem(String stem, List<String> options, int choice, int answer,
                                            String explain, ModelAccess access) {
        StringBuilder user = new StringBuilder();
        user.append("题干：").append(stem).append('\n');
        for (int i = 0; i < options.size(); i++) {
            user.append(i == answer ? "[正确] " : "[   ] ").append(i + 1).append(". ").append(options.get(i)).append('\n');
        }
        user.append("用户选了第 ").append(choice + 1).append(" 项。");
        if (explain != null && !explain.isBlank()) {
            user.append("\n原题解析：").append(explain);
        }
        ModelReply reply = call(REVIEW_SYSTEM, user.toString(), access);
        return new Result<>(reply.text().trim(), reply.usage());
    }

    public Result<List<String>> nextSteps(String lines, String failing, String dueReviews,
                                          String weakConcepts, ModelAccess access) {
        String user = "各线进度：" + lines + "\n卡住的关卡：" + failing
                + "\n到期的复习：" + dueReviews + "\n最薄弱的概念：" + weakConcepts;
        ModelReply reply = call(NEXT_STEP_SYSTEM, user, access);
        return new Result<>(strings(parse(reply.text()), "steps", 1, 3), reply.usage());
    }

    public Result<String> explainConcept(String name, String summary, ModelAccess access) {
        String user = summary == null || summary.isBlank() ? "概念：" + name : "概念：" + name + "\n摘要：" + summary;
        ModelReply reply = call(CONCEPT_SYSTEM, user, access);
        return new Result<>(reply.text().trim(), reply.usage());
    }

    public Result<List<String>> notePoints(String title, String body, ModelAccess access) {
        String user = "标题：" + title + "\n正文：\n" + body;
        ModelReply reply = call(NOTE_SYSTEM, user, access);
        return new Result<>(strings(parse(reply.text()), "points", 1, 5), reply.usage());
    }

    public Result<QuizView> quiz(String title, String body, ModelAccess access) {
        ModelReply reply = call(QUIZ_SYSTEM, "标题：" + title + "\n正文：\n" + body, access);
        JsonNode root = parse(reply.text());
        String question = root.path("question").asText("").trim();
        List<String> options = new ArrayList<>();
        root.path("options").forEach(node -> options.add(node.asText("").trim()));
        int answer = root.path("answer").asInt(-1);
        String explain = root.path("explain").asText("").trim();
        if (question.isBlank() || options.size() != 4 || answer < 0 || answer >= options.size()
                || options.stream().anyMatch(String::isBlank) || explain.isBlank()) {
            throw formatError();
        }
        return new Result<>(new QuizView(question, List.copyOf(options), answer, explain), reply.usage());
    }

    /** 模型偶尔会包一层 ```json 围栏；剥掉再解析，别为这个让用户重试。 */
    static JsonNode parse(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) {
                text = text.substring(firstLine + 1, lastFence).trim();
            }
        }
        try {
            return JSON.readTree(text);
        } catch (Exception notJson) {
            throw formatError();
        }
    }

    static List<String> strings(JsonNode root, String field, int min, int max) {
        JsonNode array = root.path(field);
        if (!array.isArray()) {
            throw formatError();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            String text = node.asText("").trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        if (values.size() < min || values.size() > max) {
            throw formatError();
        }
        return List.copyOf(values);
    }

    private static BizException formatError() {
        return new BizException(ErrorCode.INTERNAL, "模型返回的格式不对，请再试一次");
    }
}
