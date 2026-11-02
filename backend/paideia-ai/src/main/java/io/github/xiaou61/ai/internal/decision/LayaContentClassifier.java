package io.github.xiaou61.ai.internal.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用 Laya 做一次前向判断的 {@link ContentClassifier} 实现（WORK-026）。
 *
 * <p>Laya 是 System 1 决策模型：**不生成文字**，只对给定的一组类型化问题一次前向返回
 * 「选哪个」和概率。这里只问两件事：内容类别、大致主题。
 *
 * <p><b>为什么用 choice 而不是 noul</b>（2026-10-20 实测）：同一个判定用 `noul`（是非题）时，
 * 概率会随文本长度剧烈漂移——同一段面经，结尾多三个字「有哪些」就从 0.243 跳到 0.675，
 * 再来一句又回到 0.644。那种量级的漂移让任何固定阈值都不可用。换成 `choice`（三分类）后
 * 同一批样例 7 条判对 6 条，technical 的 softmax 概率稳定在 0.98 一档。**这是选型依据，
 * 不是偏好**：noul 的绝对概率不可用于阈值，choice 的相对概率可以。
 *
 * <p>已知仍会判错的那一条：「如何提高工程能力？我觉得多写代码就行」被判成 technical。
 * 它确实提到了代码，所以这不是纯粹的错误，但它不在我们要的那类内容里——这也是
 * {@link TechEssayFilter} 仍以规则为主、只把边界情况交给模型的原因。
 *
 * <p><b>不抛异常，全部返回 empty</b>：未启用、连不上、超时、返回格式不对、置信度不够，
 * 都表示"这次没判断出来"。调用方自己退回规则判断。
 */
@Component
public class LayaContentClassifier implements ContentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LayaContentClassifier.class);

    /** laya-serve 的判定端点。 */
    private static final String PATH = "/v1/systemone";

    /** 问模型的题目定义。**问题文本刻意用英文**：官方实测英文指令在中文输入上更稳。 */
    private static final Map<String, Object> QUESTIONS = buildQuestions();

    /** 判定为技术内容时 `kind` 的答案值。 */
    private static final String TECHNICAL = "technical";

    private final DecisionProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http;
    /** 连续失败后不再逐条打日志：定时任务一次要判几十条，每条一行会把日志冲掉。 */
    private boolean loggedFailure;

    public LayaContentClassifier(DecisionProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(properties.getTimeoutMs(), 1000)))
                // 决策服务是我们自己配置的固定地址，不跟随重定向，避免配置被改后跳去别处。
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Optional<ContentClassification> classify(String text) {
        if (!properties.isEnabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        String state = text.length() > properties.getMaxTextChars()
                ? text.substring(0, properties.getMaxTextChars())
                : text;

        try {
            HttpResponse<String> response = http.send(buildRequest(state), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return failed("HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception exception) {
            return failed(exception.getClass().getSimpleName());
        }
    }

    private HttpRequest buildRequest(String state) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", state);
        payload.put("questions", QUESTIONS);
        String body = objectMapper.writeValueAsString(payload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + PATH))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!properties.getApiKey().isBlank()) {
            builder.header("authorization", "Bearer " + properties.getApiKey());
        }
        return builder.build();
    }

    private Optional<ContentClassification> parse(String body) throws Exception {
        JsonNode answers = objectMapper.readTree(body).path("answers");
        JsonNode kind = answers.path("kind");
        String choice = kind.path("choice").asText("");
        double probability = kind.path("probabilities").path(TECHNICAL).asDouble(0);
        if (!TECHNICAL.equals(choice) || probability < properties.getMinConfidence()) {
            // 不是技术内容，或模型自己也没把握——两种情况都当"没判断出来"，让规则兜底。
            return Optional.empty();
        }
        String topic = answers.path("topic").path("choice").asText("");
        loggedFailure = false;
        return Optional.of(new ContentClassification(true, probability, topic));
    }

    private Optional<ContentClassification> failed(String reason) {
        if (!loggedFailure) {
            loggedFailure = true;
            log.warn("决策模型判定失败，退回规则判断：{}（后续同类失败不再逐条记录）", reason);
        }
        return Optional.empty();
    }

    private static Map<String, Object> buildQuestions() {
        Map<String, Object> kinds = new LinkedHashMap<>();
        kinds.put("technical", "technical interview questions about programming, databases, "
                + "operating systems, concurrency or algorithms");
        kinds.put("career", "job hunting, offers, salary, company news, internship experience, "
                + "career advice, interview logistics");
        kinds.put("other", "anything else");

        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "choice");
        kind.put("instructions", "What kind of content is this?");
        kind.put("criteria", kinds);

        Map<String, Object> topics = new LinkedHashMap<>();
        topics.put("java", "Java language, JVM, collections, concurrency, Spring");
        topics.put("database", "MySQL, indexes, SQL, transactions, Redis");
        topics.put("career", "job hunting, offers, salary, companies, internship experience");
        topics.put("other", "anything else");

        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("type", "choice");
        topic.put("instructions", "Which topic does this text mainly discuss?");
        topic.put("criteria", topics);

        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("kind", kind);
        questions.put("topic", topic);
        return questions;
    }
}
