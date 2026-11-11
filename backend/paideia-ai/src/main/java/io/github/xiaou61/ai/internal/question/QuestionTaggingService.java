package io.github.xiaou61.ai.internal.question;

import io.github.xiaou61.ai.internal.prompt.PromptFences;
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
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.provider.GuardedHttpTransport;
import io.github.xiaou61.ai.internal.support.FinishReasons;
import io.github.xiaou61.ai.internal.support.ModelJson;
import io.github.xiaou61.knowledge.QuestionTaggingPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 题目概念候选的模型实现（WORK-033 REQ-010）。
 *
 * <p>一次调用处理一篇条目的全部题目（通常 3–10 道），只允许从给定概念列表里选 id，
 * 集合外的一律丢弃——模型编的 id 不能进候选。
 *
 * <p>**失败不抛给调用方**：没有凭据、上游超时、JSON 坏掉都返回空结果，
 * 管理端仍然能用启发式候选（{@code QuestionConceptSuggester}）。
 */
@Component
class QuestionTaggingService implements QuestionTaggingPort {

    static final String VERSION = "question-tag-v2";

    private static final Logger log = LoggerFactory.getLogger(QuestionTaggingService.class);
    /** 单次最多送多少题与多少概念：一篇条目的题目数远小于这个上限。 */
    private static final int MAX_QUESTIONS = 30;
    private static final int MAX_CONCEPTS = 40;
    private static final int MAX_TOKENS = 8192;
    private static final double TEMPERATURE = 0.2;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CredentialService credentials;
    private final GuardedHttpTransport transport;
    private final RunRecorder recorder;

    QuestionTaggingService(CredentialService credentials, GuardedHttpTransport transport,
                           RunRecorder recorder) {
        this.credentials = credentials;
        this.transport = transport;
        this.recorder = recorder;
    }

    @Override
    public Result tag(Request request) {
        if (request == null || request.questions().isEmpty() || request.concepts().isEmpty()) {
            return new Result(List.of(), null, VERSION);
        }
        List<Question> questions = request.questions().stream().limit(MAX_QUESTIONS).toList();
        List<Concept> concepts = request.concepts().stream().limit(MAX_CONCEPTS).toList();
        var access = credentials.access(0);
        if (access.isEmpty()) {
            log.info("题目打标跳过：平台未配置 AI 凭据");
            return new Result(List.of(), null, VERSION);
        }
        ModelAccess model = access.get();
        var run = recorder.begin(RunSpec.of(RunKind.QUESTION_TAGGING, 0L)
                .withModel(model.model())
                .withPromptVersion(VERSION));
        try {
            Call call = call(model, questions, concepts);
            Result parsed = parse(call.text(), questions, concepts, model.model());
            if (call.inputTokens() != null) {
                run.tokens(call.inputTokens(), call.outputTokens(), 0);
            }
            run.finish(parsed.suggestions().isEmpty() ? RunOutcome.FAILED : RunOutcome.OK, null);
            return parsed;
        } catch (RuntimeException exception) {
            log.warn("题目打标失败：{}", exception.getMessage());
            run.finish(RunOutcome.FAILED, null);
            return new Result(List.of(), model.model(), VERSION);
        } finally {
            run.close();
        }
    }

    private record Call(String text, Integer inputTokens, Integer outputTokens) {
    }

    private Call call(ModelAccess access, List<Question> questions, List<Concept> concepts) {
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
                Msg.builder().role(MsgRole.SYSTEM).textContent(systemPrompt()).build(),
                Msg.builder().role(MsgRole.USER).textContent(userPrompt(questions, concepts)).build());
        ChatResponse response = model.stream(messages, List.of(), options)
                .blockLast(Duration.ofSeconds(90));
        if (response == null || response.getContent() == null) {
            throw new IllegalStateException("上游没有返回内容");
        }
        // 打标是按批取的：截断意味着一半题目没有候选。宁可整批失败留一条明确的日志，
        // 也不要把半截结果当"模型认为无关"写回候选表。
        if (FinishReasons.truncated(response)) {
            throw new IllegalStateException("上游输出被长度上限截断");
        }
        StringBuilder text = new StringBuilder();
        for (var block : response.getContent()) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            }
        }
        Integer input = null;
        Integer output = null;
        if (response.getUsage() != null) {
            input = response.getUsage().getInputTokens();
            output = response.getUsage().getOutputTokens();
        }
        return new Call(text.toString(), input, output);
    }

    private static String systemPrompt() {
        return """
                你是课程编辑，负责把题目归到已有知识点上。

                规则：
                1. 只能从用户给出的知识点列表里选 id，**不得编造 id**。
                2. 每道题选 0-3 个最相关的知识点；完全无关就返回空数组，不要硬凑。
                3. 不要解释理由，只输出 JSON。

                输出格式：
                {"items":[{"questionKey":"...","conceptIds":[1,2]}]}
                """;
    }

    private static String userPrompt(List<Question> questions, List<Concept> concepts) {
        StringBuilder builder = new StringBuilder(PromptFences.DATA_ONLY + "知识点列表：\n");
        for (Concept concept : concepts) {
            builder.append("- id=").append(concept.id()).append(" 名称=").append(concept.name());
            if (concept.summary() != null && !concept.summary().isBlank()) {
                builder.append(" 说明=").append(concept.summary());
            }
            builder.append('\n');
        }
        builder.append("\n题目列表：\n");
        for (Question question : questions) {
            builder.append("- questionKey=").append(question.questionKey())
                    .append(" 题干=").append(question.stem());
            if (!question.options().isEmpty()) {
                builder.append(" 选项=").append(String.join(" / ", question.options()));
            }
            builder.append('\n');
        }
        return builder.append("\n请按规则输出 JSON。").toString();
    }

    /** 解析并过滤：只保留输入里存在的题目与概念 id。 */
    Result parse(String raw, List<Question> questions, List<Concept> concepts, String model) {
        String body = ModelJson.objectBody(raw);
        if (body == null) {
            return new Result(List.of(), model, VERSION);
        }
        Set<String> allowedQuestions = new HashSet<>();
        for (Question question : questions) {
            allowedQuestions.add(question.questionKey());
        }
        Set<Long> allowedConcepts = new HashSet<>();
        for (Concept concept : concepts) {
            allowedConcepts.add(concept.id());
        }
        try {
            JsonNode items = objectMapper.readTree(body).path("items");
            List<Suggestion> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String key = item.path("questionKey").asText("");
                    if (!allowedQuestions.contains(key)) {
                        continue;
                    }
                    JsonNode ids = item.path("conceptIds");
                    if (!ids.isArray()) {
                        continue;
                    }
                    for (JsonNode idNode : ids) {
                        long conceptId = idNode.asLong(-1);
                        if (!allowedConcepts.contains(conceptId)) {
                            continue;
                        }
                        String composed = key + "#" + conceptId;
                        if (seen.add(composed)) {
                            result.add(new Suggestion(key, conceptId));
                        }
                    }
                }
            }
            return new Result(result, model, VERSION);
        } catch (Exception exception) {
            log.warn("题目打标返回解析失败：{}", exception.getClass().getSimpleName());
            return new Result(List.of(), model, VERSION);
        }
    }
}
