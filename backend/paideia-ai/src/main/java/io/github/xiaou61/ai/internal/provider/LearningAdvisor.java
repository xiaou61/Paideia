package io.github.xiaou61.ai.internal.provider;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.internal.credential.AiProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把推荐理由交给模型润色一次（WORK-047）。
 *
 * <p><b>它不参与排序。</b>入参是前端已经排好的 Top 3，出参只是三句话。这样做的原因：
 * 排序必须可测、可复现；让模型看全量轨道再决定顺序，测试就只能断言"返回了三条"，
 * 而且同一个人今天和明天看到的顺序会跳。
 *
 * <p><b>只拼结构化字段。</b>提示词里除了岗位名与几条轨道的 slug / 名称 / 数字之外
 * 没有任何自由文本——这是唯一的注入面，所以调用方必须先做白名单校验（见控制器）。
 *
 * <p>与 {@link AgentScopeEntrySummarizer} 同一套最小面：OpenAI 兼容模型 + DeepSeek
 * formatter + 一次同步调用，没有 agent 运行时，也没有工具。
 */
@Component
public class LearningAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LearningAdvisor.class);

    /** 提示词版本：改了措辞就改它，观测面板才能分辨"哪一版效果好"。 */
    public static final String PROMPT_VERSION = "learning-advice-v1";

    private static final String SYSTEM = """
            你是学习平台的学习规划助手。用户会给你一个目标岗位和几条已经排好序的学习轨道。
            你的任务只是为每条轨道写一句"为什么现在学它"。

            硬性要求：
            1. 每条理由必须引用给你的具体数字（已过几关 / 共几关），不许编造数字；
            2. 30 个汉字以内，口语，不要"建议你努力""很重要"这类空话；
            3. 不要改变顺序，不要评价轨道是否值得学；
            4. 只输出 JSON，形如 {"reasons":[{"slug":"...","reason":"..."}]}，不要任何解释文字。
            """;

    private final AiProperties properties;
    private final GuardedHttpTransport transport;

    LearningAdvisor(AiProperties properties, GuardedHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    /** 一条轨道的输入。字段全部来自白名单校验之后的客户端入参。 */
    public record Pick(String slug, String name, String tier, int passed, int total) {
    }

    public record Result(Map<String, String> reasons, Integer inputTokens, Integer outputTokens) {
    }

    public Result write(String roleLabel, List<Pick> picks, ModelAccess access) {
        GenerateOptions options = GenerateOptions.builder()
                .modelName(access.model())
                .stream(false)
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .build();
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(access.apiKey())
                .baseUrl(access.baseUrl())
                .modelName(access.model())
                .formatter(new DeepSeekFormatter())
                .stream(false)
                .generateOptions(options)
                // DeepSeek 不支持原生结构化输出；显式关掉，免得将来加工具时才踩这条
                .nativeStructuredOutput(false)
                .nativeStructuredOutputWithTools(false)
                .httpTransport(transport)
                .build();

        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(SYSTEM).build(),
                Msg.builder().role(MsgRole.USER).textContent(userBlock(roleLabel, picks)).build());
        ChatResponse response = model.stream(messages, List.of(), options)
                .blockLast(Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
        ChatUsage usage = response == null ? null : response.getUsage();
        String text = response == null || response.getContent() == null
                ? ""
                : response.getContent().stream()
                        .filter(block -> block instanceof TextBlock)
                        .map(block -> ((TextBlock) block).getText())
                        .reduce("", String::concat);
        return new Result(parse(text), usage == null ? null : usage.getInputTokens(),
                usage == null ? null : usage.getOutputTokens());
    }

    private static String userBlock(String roleLabel, List<Pick> picks) {
        StringBuilder text = new StringBuilder("目标岗位：").append(roleLabel).append("\n轨道：\n");
        int index = 1;
        for (Pick pick : picks) {
            text.append(index++).append(". slug=").append(pick.slug())
                    .append(" 名称=").append(pick.name())
                    .append(" 定位=").append(tierLabel(pick.tier()))
                    .append(" 进度=").append(pick.passed()).append('/').append(pick.total())
                    .append('\n');
        }
        return text.toString();
    }

    private static String tierLabel(String tier) {
        return switch (tier) {
            case "core" -> "核心";
            case "advanced" -> "进阶";
            default -> "选读";
        };
    }

    /**
     * 解析模型输出。
     *
     * <p>容错优先：模型偶尔会在 JSON 前后加一句话，或者用 ```json 包起来。**解析不出来就返回空**，
     * 由调用方退回规则理由——理由缺失是产品允许状态，不该让整次请求失败。
     */
    public static Map<String, String> parse(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return result;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw.substring(start, end + 1));
            for (com.fasterxml.jackson.databind.JsonNode item : root.path("reasons")) {
                String slug = item.path("slug").asText("");
                String reason = item.path("reason").asText("").trim();
                if (!slug.isBlank() && !reason.isBlank()) {
                    result.put(slug, reason.length() > 60 ? reason.substring(0, 60) : reason);
                }
            }
        } catch (Exception failure) {
            log.debug("learning.advice.parse_failed length={}", raw.length());
        }
        return result;
    }

    /** 只用于让控制器构造同类型入参，避免把内部 record 暴露到 web 层之外。 */
    public static List<Pick> picks(List<String[]> rows) {
        List<Pick> picks = new ArrayList<>();
        for (String[] row : rows) {
            picks.add(new Pick(row[0], row[1], row[2], Integer.parseInt(row[3]), Integer.parseInt(row[4])));
        }
        return picks;
    }
}
