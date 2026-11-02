package io.github.xiaou61.ai.internal.review;

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
import io.github.xiaou61.ai.RunKind;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.provider.GuardedHttpTransport;
import io.github.xiaou61.knowledge.ReviewQuestionPort;
import io.github.xiaou61.platform.Hashes;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 复习出题（WORK-034 S2）。
 *
 * <p>一次调用给整场会话出题（最多 5 道单选），复用的规则与训练营一致：
 * 4 个选项、答案下标合法、题干 ≤200 字、解析非空、指纹不重复。
 *
 * <p>**失败返回 empty**：没凭据、上游超时、结构不合格都走降级（回承载条目），不抛异常。
 */
@Component
class ReviewQuestionGenerator implements ReviewQuestionPort {

    static final String VERSION = "review-gen-v1";
    private static final Logger log = LoggerFactory.getLogger(ReviewQuestionGenerator.class);
    private static final int MAX_TOKENS = 8192;
    private static final double TEMPERATURE = 0.3;
    private static final int OPTION_COUNT = 4;
    private static final int MAX_STEM = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CredentialService credentials;
    private final GuardedHttpTransport transport;
    private final RunRecorder recorder;
    private final CreditService credits;

    ReviewQuestionGenerator(CredentialService credentials, GuardedHttpTransport transport,
                            RunRecorder recorder, CreditService credits) {
        this.credentials = credentials;
        this.transport = transport;
        this.recorder = recorder;
        this.credits = credits;
    }

    @Override
    public Optional<Result> generate(long userId, List<Concept> concepts, int perConcept,
                                     Set<String> previousStems) {
        if (concepts.isEmpty()) {
            return Optional.empty();
        }
        var access = credentials.access(0);
        if (access.isEmpty()) {
            log.info("复习出题跳过：平台未配置 AI 凭据");
            return Optional.empty();
        }
        ModelAccess model = access.get();
        // 预留先于上游调用：余额不足直接 402，一个请求都不发（WORK-009 的硬规则）
        long reserved = credits.reserve(userId, credits.estimate());
        var run = recorder.begin(RunSpec.of(RunKind.REVIEW_GENERATE, 0L)
                .withModel(model.model())
                .withPromptVersion(VERSION));
        try {
            Call call = call(model, concepts, previousStems);
            List<Question> questions = parse(call.text(), concepts, previousStems);
            if (call.inputTokens() != null) {
                run.tokens(call.inputTokens(), call.outputTokens(), 0);
            }
            if (questions.isEmpty()) {
                // 用了 token 就按实际结算；一个 token 都没产生才全退
                settleOrRefund(userId, reserved, model, call);
            } else {
                credits.settleReview(userId, reserved, model.model(),
                        call.inputTokens(), call.outputTokens());
            }
            run.finish(questions.isEmpty() ? RunOutcome.FAILED : RunOutcome.OK, null);
            return questions.isEmpty() ? Optional.empty() : Optional.of(new Result(questions, model.model(),
                    call.inputTokens(), call.outputTokens()));
        } catch (io.github.xiaou61.platform.BizException biz) {
            // 积分不足等业务错误原样抛给调用方，由接口返回 402
            credits.refund(userId, reserved, null, "复习出题未发起");
            run.finish(RunOutcome.FAILED, "insufficient_or_rejected");
            throw biz;
        } catch (RuntimeException exception) {
            log.warn("复习出题失败：{}", exception.getMessage());
            credits.refund(userId, reserved, null, "复习出题失败");
            run.finish(RunOutcome.FAILED, null);
            return Optional.empty();
        } finally {
            run.close();
        }
    }

    private record Call(String text, Integer inputTokens, Integer outputTokens) {
    }

    private void settleOrRefund(long userId, long reserved, ModelAccess model, Call call) {
        if (call.inputTokens() == null) {
            credits.refund(userId, reserved, null, "复习出题未返回用量");
        } else {
            credits.settleReview(userId, reserved, model.model(), call.inputTokens(), call.outputTokens());
        }
    }

    private Call call(ModelAccess access, List<Concept> concepts, Set<String> previousStems) {
        GenerateOptions options = GenerateOptions.builder()
                .modelName(access.model()).stream(false).temperature(TEMPERATURE)
                .maxTokens(MAX_TOKENS).build();
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(access.apiKey()).baseUrl(access.baseUrl()).modelName(access.model())
                .formatter(new DeepSeekFormatter()).stream(false)
                .generateOptions(options).nativeStructuredOutput(false)
                .nativeStructuredOutputWithTools(false).httpTransport(transport).build();
        String user = prompt(concepts, previousStems);
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(systemPrompt()).build(),
                Msg.builder().role(MsgRole.USER).textContent(user).build());
        ChatResponse response = model.stream(messages, List.of(), options)
                .blockLast(Duration.ofSeconds(90));
        if (response == null || response.getContent() == null) {
            throw new IllegalStateException("上游没有返回内容");
        }
        StringBuilder text = new StringBuilder();
        for (var block : response.getContent()) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            }
        }
        Integer input = response.getUsage() == null ? null : response.getUsage().getInputTokens();
        Integer output = response.getUsage() == null ? null : response.getUsage().getOutputTokens();
        return new Call(text.toString(), input, output);
    }

    private static String systemPrompt() {
        return """
                你是出题老师，为复习生成四选一单选题。

                规则：
                1. 每个给定知识点出 1 道题，只考该概念本身，不要跨概念。
                2. 每题恰好 4 个选项，只有 1 个正确答案；干扰项要合理但明确错误。
                3. 题干不超过 200 字；解析一句话，说明为什么对。
                4. **不得重复给定的历史题干**，换一个角度问。
                5. 只输出 JSON，不要 markdown 围栏与解释：
                {"items":[{"conceptId":1,"stem":"...","options":["A","B","C","D"],"answerIndex":0,"explanation":"..."}]}
                """;
    }

    private static String prompt(List<Concept> concepts, Set<String> previousStems) {
        StringBuilder builder = new StringBuilder("知识点：\n");
        for (Concept concept : concepts) {
            builder.append("- conceptId=").append(concept.id()).append(" 名称=").append(concept.name());
            if (concept.summary() != null && !concept.summary().isBlank()) {
                builder.append(" 说明=").append(concept.summary());
            }
            builder.append('\n');
        }
        if (!previousStems.isEmpty()) {
            builder.append("\n历史题干（不要重复）：\n");
            previousStems.stream().limit(20).forEach(stem -> builder.append("- ").append(stem).append('\n'));
        }
        return builder.append("\n请按规则输出 JSON。").toString();
    }

    /** 解析并校验：结构不合格的题直接丢，不拿坏题去打分。 */
    List<Question> parse(String raw, List<Concept> concepts, Set<String> previousStems) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        Set<Long> allowed = new HashSet<>();
        for (Concept concept : concepts) {
            allowed.add(concept.id());
        }
        Set<String> seenStems = new LinkedHashSet<>(previousStems);
        List<Question> result = new ArrayList<>();
        try {
            JsonNode items = objectMapper.readTree(raw.substring(start, end + 1)).path("items");
            if (!items.isArray()) {
                return List.of();
            }
            for (JsonNode item : items) {
                long conceptId = item.path("conceptId").asLong(-1);
                String stem = item.path("stem").asText("").strip();
                JsonNode optionsNode = item.path("options");
                int answer = item.path("answerIndex").asInt(-1);
                String explanation = item.path("explanation").asText("").strip();
                List<String> options = new ArrayList<>();
                if (optionsNode.isArray()) {
                    optionsNode.forEach(option -> options.add(option.asText("").strip()));
                }
                if (!allowed.contains(conceptId) || stem.isEmpty() || stem.length() > MAX_STEM
                        || options.size() != OPTION_COUNT || options.stream().anyMatch(String::isBlank)
                        || answer < 0 || answer >= OPTION_COUNT || explanation.isEmpty()) {
                    continue;
                }
                String fingerprint = fingerprint(stem);
                if (!seenStems.add(fingerprint)) {
                    continue;
                }
                result.add(new Question(conceptId, stem, List.copyOf(options), answer, explanation, fingerprint));
            }
        } catch (Exception exception) {
            log.warn("复习出题返回解析失败：{}", exception.getClass().getSimpleName());
            return List.of();
        }
        return result;
    }

    static String fingerprint(String stem) {
        return Hashes.sha256Hex(stem);
    }
}