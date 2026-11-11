package io.github.xiaou61.ai.internal.digest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.internal.provider.GuardedHttpTransport;
import io.github.xiaou61.ai.internal.support.ModelJson;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每天那一次 AI 压缩（WORK-027）。
 *
 * <p>与 {@code EssayExtractor} 同一套最小面：一个 OpenAI 兼容模型 + DeepSeek formatter
 * + 一次非流式调用，没有 agent 运行时。
 *
 * <p><b>三条部署已知限制</b>（照抄既有结论，理由相同）：
 * <ul>
 *   <li>{@code nativeStructuredOutput(false)}：DeepSeek 不支持原生结构化输出；</li>
 *   <li>不用工具——"工具与结构化输出同一次调用"会互相干扰；</li>
 *   <li>思考 token 吃 {@code maxTokens}，给足 8192，吃满时上游会静默返回空。</li>
 * </ul>
 *
 * <p><b>失败不抛给调用方</b>：返回空 map，编排层把全部条目标 {@code degraded} 入库
 * （AC-005）。"标题 + 链接本身就有价值"。
 */
@Component
class DigestSummarizer {

    private static final Logger log = LoggerFactory.getLogger(DigestSummarizer.class);

    /**
     * 一次调用的 token 上限。
     *
     * <p>**2026-10-21 线上实测：8192 在 60 条候选时不够**——上游把 maxTokens 吃在思考上并
     * 静默返回空，日志只有一行「速递 AI 返回空内容（思考 token 可能吃满）」，60 条全部降级。
     * 5 条时 2320 token 够用，12 条时也够，**问题只在满批 60 条**。
     *
     * <p>deepseek-flash **默认思考且关不掉**（项目已知限制，见 `EssayExtractor` 同一段注释），
     * 思考 token 与正文共用这个预算。给 16384 与面经抽题同量级。
     *
     * <p>**另一道保险是分批**：{@link #BATCH_SIZE} 把大候选池拆成几次调用，
     * 既降低单次超限风险，也让"某批失败"只影响那一批（其余批仍能拿到摘要）。
     */
    private static final int MAX_TOKENS = 16384;

    /**
     * 单次调用最多处理多少条。
     *
     * <p>60 条一次性喂进去时，即使 16384 也可能被思考吃满。拆成 24 条一批，
     * 单批输出量可控；代价是多几次调用（但总 token 相近，且失败时损失面更小）。
     */
    private static final int BATCH_SIZE = 24;

    /** 编辑类任务，要稳。 */
    private static final double TEMPERATURE = 0.3;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GuardedHttpTransport transport;

    DigestSummarizer(GuardedHttpTransport transport) {
        this.transport = transport;
    }

    /** 一条 AI 写出的结果。`heat` 是 0-100 的重要性分，只给源本身没有热度信号的条目用。 */
    record Rewritten(String title, String summary, Integer heat) {
    }

    /**
     * 一次调用的产物：改写结果 + token 用量。
     *
     * <p>用量要带出来是为了进 `ai_runs`（AC-008）：日报一天只调一次，运营统计里看不到这条链路
     * 就等于"最贵的那次调用不在账上"。
     */
    record Outcome(Map<String, Rewritten> rewritten, io.github.xiaou61.ai.TokenUsage usage) {
    }

    /**
     * 给一批候选写标题与摘要（**内部分批调用**）。
     *
     * <p>**只接受输入集合内的指纹**：模型返回集合外的键直接丢弃，不写垃圾行。
     * 某个指纹没返回就等于"这条降级"，由调用方处理。
     *
     * <p>**不抛异常**：批失败只影响那一批，其余批照常返回（AC-005）。
     * 全部失败时返回空 map + null 用量，由编排层把全部条目标 degraded。
     */
    Outcome summarize(List<FetchedItem> items, List<String> fingerprints,
                      ModelAccess access, int timeoutSeconds) {
        if (items.isEmpty()) {
            return new Outcome(Map.of(), null);
        }
        Map<String, Rewritten> merged = new LinkedHashMap<>();
        int totalInput = 0;
        int totalOutput = 0;
        boolean anyUsage = false;

        for (int start = 0; start < items.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, items.size());
            List<FetchedItem> batch = items.subList(start, end);
            List<String> batchFingerprints = fingerprints.subList(start, end);
            try {
                CallResult result = call(batch, batchFingerprints, access, timeoutSeconds);
                merged.putAll(parse(result.text(), batchFingerprints));
                if (result.usage() != null) {
                    anyUsage = true;
                    totalInput += result.usage().inputTokens();
                    totalOutput += result.usage().outputTokens();
                }
                if (merged.size() < end && start + BATCH_SIZE < items.size()) {
                    log.info("速递第 {}-{} 条只写回部分摘要，继续下一批", start + 1, end);
                }
            } catch (RuntimeException exception) {
                // 单批失败不影响其它批：AC-005
                log.warn("速递第 {}-{} 条 AI 压缩失败，这 {} 条按降级处理：{}",
                        start + 1, end, end - start, exception.getMessage());
            }
        }
        io.github.xiaou61.ai.TokenUsage usage = anyUsage
                ? new io.github.xiaou61.ai.TokenUsage(totalInput, totalOutput, totalInput + totalOutput)
                : null;
        return new Outcome(merged, usage);
    }

    /** 一次上游调用的原始产物。 */
    private record CallResult(String text, io.github.xiaou61.ai.TokenUsage usage) {
    }

    private CallResult call(List<FetchedItem> items, List<String> fingerprints, ModelAccess access,
                            int timeoutSeconds) {
        GenerateOptions options = GenerateOptions.builder()
                .modelName(access.model())
                .stream(false)
                .temperature(TEMPERATURE)
                .maxTokens(MAX_TOKENS)
                .build();
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(access.apiKey())
                .baseUrl(access.baseUrl())
                .modelName(access.model())
                .formatter(new DeepSeekFormatter())
                .stream(false)
                .generateOptions(options)
                .nativeStructuredOutput(false)
                .nativeStructuredOutputWithTools(false)
                .httpTransport(transport)
                .build();

        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(DigestPrompt.system()).build(),
                Msg.builder().role(MsgRole.USER)
                        .textContent(DigestPrompt.user(items, fingerprints)).build());

        ChatResponse response = model.stream(messages, List.of(), options)
                .blockLast(Duration.ofSeconds(timeoutSeconds));
        if (response == null) {
            throw new IllegalStateException("上游没有返回内容");
        }
        StringBuilder text = new StringBuilder();
        if (response.getContent() != null) {
            for (var block : response.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    text.append(textBlock.getText());
                }
            }
        }
        var usage = response.getUsage() == null ? null
                : new io.github.xiaou61.ai.TokenUsage(response.getUsage().getInputTokens(),
                        response.getUsage().getOutputTokens(), response.getUsage().getTotalTokens());
        return new CallResult(text.toString(), usage);
    }

    /**
     * 解析模型输出。
     *
     * <p>容忍两种情况：整体被 markdown 围栏包住、或前后有解释文字——都靠"取第一个 `{`
     * 到最后一个 `}`"兜住。**空内容按失败处理**：DeepSeek 会把 maxTokens 吃在思考上
     * 并静默返回空，那不是"今天没什么可写"。
     */
    Map<String, Rewritten> parse(String raw, List<String> allowedFingerprints) {
        if (raw == null || raw.isBlank()) {
            log.warn("速递 AI 返回空内容（思考 token 可能吃满），全部按降级处理");
            return Map.of();
        }
        String body = ModelJson.objectBody(raw);
        if (body == null) {
            log.warn("速递 AI 返回里没有 JSON 对象，全部按降级处理");
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode list = root.path("items");
            if (!list.isArray()) {
                return Map.of();
            }
            java.util.Set<String> allowed = new java.util.HashSet<>(allowedFingerprints);
            Map<String, Rewritten> result = new LinkedHashMap<>();
            for (JsonNode node : list) {
                String fingerprint = node.path("fingerprint").asText("");
                // **集合外的指纹直接丢**：模型编的键不能进库
                if (!allowed.contains(fingerprint)) {
                    continue;
                }
                String title = node.path("title").asText("");
                String summary = node.path("summary").asText("");
                if (title.isBlank() && summary.isBlank()) {
                    continue;
                }
                result.put(fingerprint, new Rewritten(title, summary, heatOf(node)));
            }
            return result;
        } catch (java.io.IOException exception) {
            log.warn("速递 AI 返回的 JSON 解析失败：{}", exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * 取模型给的重要性分。
     *
     * <p>越界与缺省都收敛成 null 或夹到 0-100：这个值会进 `heat` 排序，脏值比没有值更糟。
     */
    private static Integer heatOf(com.fasterxml.jackson.databind.JsonNode node) {
        com.fasterxml.jackson.databind.JsonNode heat = node.path("heat");
        if (!heat.isNumber()) {
            return null;
        }
        return Math.max(0, Math.min(100, heat.asInt()));
    }
}
