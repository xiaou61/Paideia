package io.github.xiaou61.knowledge.internal.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 知识点候选的 Laya 重排对照实验（WORK-070 REQ-003）。
 *
 * <h2>要回答的问题</h2>
 *
 * <p>`QuestionConceptSuggester`（中文 bigram 的 Dice 系数）在金集上的 Recall@3 是 **0.8302**。
 * 本实验先取它的 top-10，再让 Laya 在这 10 个候选里选一个，看排序后的 Recall@3 能不能超过基线。
 *
 * <h2>为什么只做实验、不进产品代码</h2>
 *
 * <p>Laya 零样本自测准确率 0.362，低于多数类；而这条路上已经有两套实现（bigram 启发式与
 * WORK-033 的 LLM 打标）。在拿到数字之前把第三个候选源塞进产品没有依据。
 *
 * <h2>怎么跑</h2>
 *
 * <pre>
 * mvn -pl paideia-knowledge test -Dtest=ConceptRerankLiveTest \
 *     -Dpaideia.laya.live=true -Dpaideia.laya.base-url=http://127.0.0.1:8091
 * </pre>
 *
 * <p>默认跳过：没开开关时本类不执行，`mvn test` 不受影响。
 *
 * <p><b>金集加载刻意与 `ConceptRecallTest` 重复一遍</b>：那是回归门（断言 ≥0.80），本类是一次性
 * 对照实验。把它俩耦合成一份共享夹具，等于让实验的改动有机会放松回归门。
 */
@EnabledIfSystemProperty(named = "paideia.laya.live", matches = "true")
class ConceptRerankLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 与回归门同一个 K，才可比。 */
    private static final int RECALL_K = 3;

    /** 送进模型的候选数：bigram 的 top-10 作为重排池。 */
    private static final int CANDIDATES = 10;

    private static final String PATH = "/v1/systemone";

    private static JsonNode goldSet;

    @BeforeAll
    static void loadGoldSet() throws Exception {
        try (InputStream stream = ConceptRerankLiveTest.class.getResourceAsStream(
                "/golden/concept-gold-set.json")) {
            assertThat(stream).as("金集资源缺失：/golden/concept-gold-set.json").isNotNull();
            goldSet = JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("AC-003 Laya 在 bigram top-10 里重排后的 Recall@3 与基线对照")
    void compareRerankAgainstBaseline() throws Exception {
        List<Concept> concepts = concepts();
        List<Question> questions = questions();
        List<QuestionConceptSuggester.ConceptText> texts = concepts.stream()
                .map(concept -> new QuestionConceptSuggester.ConceptText(
                        concept.id(), concept.name(), concept.summary()))
                .toList();

        String baseUrl = System.getProperty("paideia.laya.base-url", "http://127.0.0.1:8091");
        // 也认环境变量：密钥不进命令行（进程列表可见），只从环境读
        String apiKey = System.getProperty("paideia.laya.api-key",
                System.getenv().getOrDefault("PAIDEIA_LAYA_API_KEY", ""));
        int timeoutMs = Integer.getInteger("paideia.laya.timeout-ms", 8000);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 1000)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        int baselineTop3 = 0;
        int baselineTop1 = 0;
        int rerankedTop3 = 0;
        int rerankedTop1 = 0;
        int answered = 0;
        int failed = 0;
        long startedAt = System.nanoTime();

        for (Question question : questions) {
            List<QuestionConceptSuggester.Suggestion> candidates = QuestionConceptSuggester.suggest(
                    question.stem(), question.options(), texts, Set.of(), Map.of(), CANDIDATES);
            if (containsCorrect(candidates.stream().map(QuestionConceptSuggester.Suggestion::conceptId), question)) {
                baselineTop3++;
            }
            if (!candidates.isEmpty() && candidates.get(0).conceptId() == question.correctConceptId()) {
                baselineTop1++;
            }

            Optional<List<Long>> ranked = rerank(http, baseUrl, apiKey, timeoutMs, question, candidates, concepts);
            if (ranked.isEmpty()) {
                failed++;
                continue;
            }
            answered++;
            List<Long> ids = ranked.get();
            if (ids.subList(0, Math.min(RECALL_K, ids.size())).contains(question.correctConceptId())) {
                rerankedTop3++;
            }
            if (!ids.isEmpty() && ids.get(0) == question.correctConceptId()) {
                rerankedTop1++;
            }
        }

        double baseline = (double) baselineTop3 / questions.size();
        double reranked = answered == 0 ? 0 : (double) rerankedTop3 / questions.size();

        System.out.printf("[AC-003] 金集 %d 题 / %d 概念，候选池 = bigram top-%d%n",
                questions.size(), concepts.size(), CANDIDATES);
        System.out.printf("[AC-003] 基线       Recall@%d = %.4f（top1 %.4f）%n",
                RECALL_K, baseline, (double) baselineTop1 / questions.size());
        System.out.printf("[AC-003] Laya 重排   Recall@%d = %.4f（top1 %.4f）%n",
                RECALL_K, reranked, answered == 0 ? 0 : (double) rerankedTop1 / questions.size());
        System.out.printf("[AC-003] 调用 %d 次，成功 %d，失败 %d，耗时 %.1f 秒%n",
                questions.size(), answered, failed, (System.nanoTime() - startedAt) / 1e9);

        // 解析失败太多说明这个协议/候选规模不成立，此时任何 Recall 数字都不可采信
        assertThat(answered)
                .as("模型能正常回答的比例太低（失败 %d/%d），协议或候选规模不成立",
                        failed, questions.size())
                .isGreaterThanOrEqualTo((int) Math.ceil(questions.size() * 0.9));
    }

    private static boolean containsCorrect(java.util.stream.Stream<Long> ids, Question question) {
        return ids.limit(RECALL_K).anyMatch(id -> id == question.correctConceptId());
    }

    /**
     * 在给定的候选里让 Laya 选一个，返回按概率从高到低的候选 id。
     * 拿不到回答时返回 empty——实验里不抛，免得一条坏样本中断整轮。
     */
    private static Optional<List<Long>> rerank(HttpClient http, String baseUrl, String apiKey, int timeoutMs,
                                               Question question, List<QuestionConceptSuggester.Suggestion> candidates,
                                               List<Concept> concepts) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Concept> byId = new LinkedHashMap<>();
        for (Concept concept : concepts) {
            byId.put(concept.id(), concept);
        }
        Map<String, String> criteria = new LinkedHashMap<>();
        for (QuestionConceptSuggester.Suggestion candidate : candidates) {
            Concept concept = byId.get(candidate.conceptId());
            criteria.put(String.valueOf(candidate.conceptId()),
                    (concept == null ? candidate.name() : concept.name())
                            + (concept == null || concept.summary() == null ? "" : " — " + concept.summary()));
        }

        Map<String, Object> questionDefinition = new LinkedHashMap<>();
        questionDefinition.put("type", "choice");
        questionDefinition.put("instructions", "Which concept does this question test?");
        questionDefinition.put("criteria", criteria);

        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("concept", questionDefinition);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", question.stem() + "\n" + String.join("\n", question.options()));
        payload.put("questions", questions);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + PATH))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
            if (!apiKey.isBlank()) {
                builder.header("authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode probabilities = JSON.readTree(response.body())
                    .path("answers").path("concept").path("probabilities");
            if (probabilities.isMissingNode() || probabilities.isEmpty()) {
                return Optional.empty();
            }
            List<Long> ranked = new ArrayList<>();
            for (var entry : probabilities.properties()) {
                ranked.add(Long.valueOf(entry.getKey()));
            }
            ranked.sort(Comparator.comparingDouble(
                    id -> -probabilities.path(String.valueOf(id)).asDouble(0)));
            return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private record Concept(long id, String name, String summary) {
    }

    private record Question(String stem, List<String> options, long correctConceptId) {
    }

    private static List<Concept> concepts() {
        List<Concept> result = new ArrayList<>();
        goldSet.get("concepts").forEach(node -> result.add(
                new Concept(node.get("id").asLong(), node.get("name").asText(),
                        node.path("summary").asText(""))));
        return result;
    }

    private static List<Question> questions() {
        List<Question> result = new ArrayList<>();
        goldSet.get("questions").forEach(node -> {
            List<String> options = new ArrayList<>();
            node.get("options").forEach(option -> options.add(option.asText()));
            result.add(new Question(node.get("stem").asText(), options,
                    node.get("correctConceptId").asLong()));
        });
        return result;
    }
}
