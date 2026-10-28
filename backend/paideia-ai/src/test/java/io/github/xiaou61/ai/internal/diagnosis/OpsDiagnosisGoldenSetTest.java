package io.github.xiaou61.ai.internal.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.OpsDiagnoser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 运维诊断的金集重放（WORK-043 REQ-020/REQ-021）。
 *
 * <p>放在本模块而不是 `paideia-app`：被测的是 {@link OpsDiagnosisPrompt}，它在这里。
 * 跨模块引用内部包会破坏 Modulith 边界（`ModularityTest` 会拦住）。
 *
 * <p><b>默认只做提示词模式</b>：把 12 条证据快照各自组装成一次提示词，断言结构正确、没有泄露、
 * 长度在预算内。这一层不调模型、不花钱、可在 CI 跑——它守的是"证据有没有被正确带进去"，
 * 而不是"模型答得好不好"。
 *
 * <p><b>live 模式要显式打开</b>（`PAIDEIA_OPS_EVAL=1` 且给了 `DEEPSEEK_API_KEY`）：
 * 真调模型，按 `mustMention` / `mustNotMention` 打分，逐条结果写到
 * `target/ops-eval-report.md`。**不放进 CI**：一次跑 12 次调用，且模型输出天然抖动，
 * 拿它当门禁会变成"改一行提示词就要重跑一小时"。
 */
class OpsDiagnosisGoldenSetTest {

    private static final String BASE_URL = System.getenv().getOrDefault(
            "PAIDEIA_OPS_EVAL_BASE_URL", "https://api.deepseek.com");
    private static final String MODEL = System.getenv().getOrDefault(
            "PAIDEIA_OPS_EVAL_MODEL", "deepseek-flash");

    /** 证据总长上限。生产 `paideia.ops.diagnosis.max-evidence-chars` 默认 12000，留一倍余量。 */
    private static final int MAX_PROMPT_CHARS = 24_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode goldenSet() {
        try (InputStream in = OpsDiagnosisGoldenSetTest.class
                .getResourceAsStream("/ops-eval/golden-set.json")) {
            assertThat(in).as("找不到 /ops-eval/golden-set.json").isNotNull();
            return JSON.readTree(in);
        } catch (IOException failure) {
            throw new AssertionError("读不到金集文件", failure);
        }
    }

    static List<Map.Entry<String, JsonNode>> cases() {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        for (JsonNode node : goldenSet().path("cases")) {
            out.add(Map.entry(node.path("name").asText(), node));
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("提示词模式：五节齐全、证据标签完整、不含泄露、长度在预算内")
    void promptMode(Map.Entry<String, JsonNode> entry) {
        JsonNode testCase = entry.getValue();
        OpsDiagnoser.Evidence evidence = evidenceOf(testCase);
        String system = OpsDiagnosisPrompt.system();
        String user = OpsDiagnosisPrompt.user(evidence);

        assertThat(system).contains(
                "## 结论", "## 证据", "## 可能原因", "## 建议动作", "## 需要人工确认");

        // 每段证据都要出现，空值也要给占位——否则模型看不出"这一段本来有数据"
        assertThat(user).contains(
                "<trigger>", "<summary>", "<metrics>", "<baselines>", "<context>",
                "<alerts>", "<logs>", "<traces>", "<deploys>");
        assertThat(user).startsWith("<evidence>").endsWith("</evidence>");
        assertThat(user.length()).isLessThan(MAX_PROMPT_CHARS);

        // mustMention 里的东西在证据里必须真的存在，否则 live 模式会因"证据没带进去"误判模型
        for (JsonNode fact : testCase.path("expected").path("mustMention")) {
            assertThat(user).as("证据应包含 %s", fact.asText()).contains(fact.asText());
        }

        // 金集里显式列出的敏感串，一个都不许出现在提示词里
        for (JsonNode secret : testCase.path("expected").path("mustNotMention")) {
            assertThat(user).as("提示词不应包含 %s", secret.asText()).doesNotContain(secret.asText());
        }
    }

    @Test
    @DisplayName("金集至少 20 条，每条都带 mustMention")
    void goldenSetIsWideEnough() {
        List<Map.Entry<String, JsonNode>> all = cases();
        assertThat(all).hasSizeGreaterThanOrEqualTo(20);
        for (Map.Entry<String, JsonNode> entry : all) {
            assertThat(entry.getValue().path("expected").path("mustMention"))
                    .as("%s 必须给出 mustMention", entry.getKey())
                    .isNotEmpty();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PAIDEIA_OPS_EVAL", matches = "1")
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    @DisplayName("live 模式：真调模型，按 mustMention/mustNotMention 打分并写报告")
    void liveReplay() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        StringBuilder report = new StringBuilder("# 运维诊断金集重放\n\n")
                .append("模型：").append(MODEL).append("\n\n| 用例 | 结论 | 缺失的必须项 | 出现的不该有 |\n")
                .append("| --- | --- | --- | --- |\n");
        int passed = 0;
        List<String> failures = new ArrayList<>();

        // 只跑指定的几条（逗号分隔前缀）：失败后定向复跑，比整跑 24 次省半小时
        String only = System.getenv("PAIDEIA_OPS_EVAL_CASES");
        for (Map.Entry<String, JsonNode> entry : cases()) {
            if (only != null && java.util.Arrays.stream(only.split(","))
                    .noneMatch(entry.getKey()::startsWith)) {
                continue;
            }
            JsonNode testCase = entry.getValue();
            String answer = callModel(http, apiKey,
                    OpsDiagnosisPrompt.system(), OpsDiagnosisPrompt.user(evidenceOf(testCase)));
            // 回答逐条落盘：失败时只看「缺哪个词」无法判断是措辞差异还是真的没引用证据
            Path answers = Path.of("target", "ops-eval-answers");
            Files.createDirectories(answers);
            Files.writeString(answers.resolve(entry.getKey() + ".md"), answer, StandardCharsets.UTF_8);
            // 空回答不是「缺 mustMention」：flash 的思考 token 吃满 max_tokens 时 content 为空，
            // 生产端会按失败退款；这里单独标记，别把模型抖动误读成提示词缺陷。
            if (answer.isBlank()) {
                failures.add(entry.getKey() + "：上游返回空内容");
                report.append("| ").append(entry.getKey())
                        .append(" | 失败 | 空回答（思考可能吃满 max_tokens） |  |\n");
                continue;
            }

            List<String> missing = new ArrayList<>();
            for (JsonNode fact : testCase.path("expected").path("mustMention")) {
                if (!answer.contains(fact.asText())) {
                    missing.add(fact.asText());
                }
            }
            List<String> leaked = new ArrayList<>();
            for (JsonNode secret : testCase.path("expected").path("mustNotMention")) {
                if (answer.contains(secret.asText())) {
                    leaked.add(secret.asText());
                }
            }
            boolean ok = missing.isEmpty() && leaked.isEmpty();
            if (ok) {
                passed++;
            } else {
                failures.add(entry.getKey() + "：缺 " + missing + "，多 " + leaked);
            }
            report.append("| ").append(entry.getKey()).append(" | ").append(ok ? "通过" : "失败")
                    .append(" | ").append(String.join("、", missing))
                    .append(" | ").append(String.join("、", leaked)).append(" |\n");
        }

        report.append("\n通过 ").append(passed).append('/').append(cases().size()).append(" 条。\n");
        Path out = Path.of("target", "ops-eval-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);

        assertThat(failures).as("失败的用例见 " + out.toAbsolutePath()).isEmpty();
    }

    private static OpsDiagnoser.Evidence evidenceOf(JsonNode testCase) {
        JsonNode e = testCase.path("evidence");
        return new OpsDiagnoser.Evidence(
                text(e, "trigger"), text(e, "alertName"), null,
                text(e, "summary"), text(e, "metrics"), text(e, "baselines"),
                text(e, "context"), text(e, "alerts"), text(e, "logs"),
                text(e, "traces"), text(e, "deploys"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String callModel(HttpClient http, String apiKey, String system, String user)
            throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(Map.of(
                "model", MODEL,
                "temperature", 0.2,
                "max_tokens", 4096,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/chat/completions"))
                .timeout(Duration.ofSeconds(150))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("上游状态码").isEqualTo(200);
        return JSON.readTree(response.body())
                .path("choices").path(0).path("message").path("content").asText();
    }
}
