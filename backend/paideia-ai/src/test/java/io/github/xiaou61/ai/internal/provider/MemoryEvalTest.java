package io.github.xiaou61.ai.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.ConversationTurn;
import io.github.xiaou61.ai.MemoryCandidate;
import io.github.xiaou61.ai.MemoryExtractor;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.memory.MemoryCandidateParser;
import io.github.xiaou61.ai.internal.memory.MemoryExtractionPrompt;
import io.github.xiaou61.ai.internal.memory.MemoryProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 提取侧的离线评测（WORK-050 TASK-003）。
 *
 * <p><b>它测的是"提示词 + 模型 + 解析器"这三件合起来的结果</b>，所以必须真连上游；没有凭据时整类跳过。
 * 跳过就是没测——报告文件不会生成，验收要看 surefire 的 {@code Skipped} 列，不看 {@code BUILD SUCCESS}。
 *
 * <p><b>这一版只出数字，不设闸</b>：基线要用**改动前的代码**跑出来，之后同一套金集复测才能回答
 * "是变好了还是只是变了"。设阈值会让金集被反向优化。
 *
 * <p>指标口径（分子/分母都在报告里写清，避免事后各说各话）：
 * <ul>
 *   <li>precision = 被期望覆盖到的实际写入 / 实际写入总数</li>
 *   <li>recall = 被实际写入覆盖到的期望 / 期望总数</li>
 *   <li>错 UPDATE 率 = 指向了"不该改的 id"的 UPDATE 数 / UPDATE 总数——这比漏记更危险</li>
 *   <li>propose 命中率 = 命中期望的 PROPOSE / 期望的 PROPOSE 数（v1 提示词恒为 0，是预期结果）</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class MemoryEvalTest {

    private static final String GOLD_SET = "golden/memory/extraction-cases.json";
    private static final String REPORT = "target/memory-eval/extraction-report.md";
    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String MODEL = "deepseek-flash";

    @Test
    @DisplayName("提取金集：算四项指标并写出报告")
    void extractionGoldSet() throws IOException {
        JsonNode root = readGoldSet();
        JsonNode cases = root.get("cases");
        assertThat(cases).as("金集里必须有 cases 数组").isNotNull();
        assertThat(cases.size()).as("用例数应与 TASK-001 约定的 40 条一致").isEqualTo(40);

        AgentScopeMemoryExtractor extractor = new AgentScopeMemoryExtractor(
                new MemoryProperties(), new MemoryCandidateParser(),
                new GuardedHttpTransport(new UpstreamGuard()));
        ModelAccess access = new ModelAccess(BASE_URL, MODEL, System.getenv("DEEPSEEK_API_KEY"));

        Metrics total = Metrics.empty();
        List<String> rows = new ArrayList<>();
        int errors = 0;
        for (JsonNode node : cases) {
            String id = node.get("id").asText();
            List<MemoryExtractor.MemoryLine> existing = memories(node.get("existing"));
            List<ConversationTurn> turns = turns(node.get("turns"));
            JsonNode expect = node.get("expect");
            try {
                MemoryExtractor.MemoryExtraction result = extractor.extract(
                        new MemoryExtractor.MemoryExtractionRequest(turns, existing), access);
                total = total.plus(judge(expect, result.candidates(), result.usage()));
                rows.add("- " + id + " " + describe(result.candidates()));
            } catch (RuntimeException failure) {
                errors += 1;
                rows.add("- " + id + " 调用失败：" + failure.getMessage());
            }
        }

        Path report = Path.of(REPORT);
        Files.createDirectories(report.getParent());
        Files.writeString(report, render(total, cases.size(), errors, rows), StandardCharsets.UTF_8);

        // 只断言"这轮确实跑起来了、且绝大多数用例能被解析"——阈值留给基线之后定
        assertThat(errors).as("失败用例不应超过一成").isLessThanOrEqualTo(cases.size() / 10);
        assertThat(report).exists();
        assertThat(Files.readString(report)).contains("precision").contains("recall").contains("propose");
    }

    record Metrics(int cases, int expectedAdd, int matchedAdd, int actualAdd,
                   int expectedUpdate, int matchedUpdate, int actualUpdate, int wrongUpdate,
                   int expectedDelete, int matchedDelete,
                   int expectedPropose, int matchedPropose,
                   int skipCases, int skipClean, int forbidAddViolations,
                   long inputTokens, long outputTokens) {

        static Metrics empty() {
            return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        Metrics plus(Metrics other) {
            return new Metrics(cases + other.cases,
                    expectedAdd + other.expectedAdd, matchedAdd + other.matchedAdd,
                    actualAdd + other.actualAdd,
                    expectedUpdate + other.expectedUpdate, matchedUpdate + other.matchedUpdate,
                    actualUpdate + other.actualUpdate, wrongUpdate + other.wrongUpdate,
                    expectedDelete + other.expectedDelete, matchedDelete + other.matchedDelete,
                    expectedPropose + other.expectedPropose, matchedPropose + other.matchedPropose,
                    skipCases + other.skipCases, skipClean + other.skipClean,
                    forbidAddViolations + other.forbidAddViolations,
                    inputTokens + other.inputTokens, outputTokens + other.outputTokens);
        }
    }

    /** 一条用例的判定。期望里用"包含某片段"而不是逐字相等，避免被措辞变化判错。 */
    private static Metrics judge(JsonNode expect, List<MemoryCandidate> actual, TokenUsage usage) {
        int expectedAdd = 0;
        int matchedAdd = 0;
        for (JsonNode node : array(expect, "add")) {
            expectedAdd += 1;
            if (actual.stream().anyMatch(c -> c.action() == MemoryCandidate.Action.ADD
                    && sameStore(c, node) && contains(c.content(), node))) {
                matchedAdd += 1;
            }
        }

        int expectedUpdate = 0;
        int matchedUpdate = 0;
        List<Long> expectedUpdateIds = new ArrayList<>();
        for (JsonNode node : array(expect, "update")) {
            expectedUpdate += 1;
            long targetId = node.get("targetId").asLong();
            expectedUpdateIds.add(targetId);
            if (actual.stream().anyMatch(c -> c.action() == MemoryCandidate.Action.UPDATE
                    && c.targetId() != null && c.targetId() == targetId && contains(c.content(), node))) {
                matchedUpdate += 1;
            }
        }

        int expectedDelete = 0;
        int matchedDelete = 0;
        for (JsonNode node : array(expect, "delete")) {
            expectedDelete += 1;
            long targetId = node.get("targetId").asLong();
            if (actual.stream().anyMatch(c -> c.action() == MemoryCandidate.Action.DELETE
                    && c.targetId() != null && c.targetId() == targetId)) {
                matchedDelete += 1;
            }
        }

        int expectedPropose = 0;
        int matchedPropose = 0;
        for (JsonNode node : array(expect, "propose")) {
            expectedPropose += 1;
            if (actual.stream().anyMatch(c -> "PROPOSE".equals(c.action().name())
                    && contains(c.content(), node))) {
                matchedPropose += 1;
            }
        }

        int actualAdd = (int) actual.stream()
                .filter(c -> c.action() == MemoryCandidate.Action.ADD).count();
        int actualUpdate = (int) actual.stream()
                .filter(c -> c.action() == MemoryCandidate.Action.UPDATE).count();
        int wrongUpdate = (int) actual.stream()
                .filter(c -> c.action() == MemoryCandidate.Action.UPDATE
                        && c.targetId() != null && !expectedUpdateIds.contains(c.targetId()))
                .count();

        boolean skipCase = expect.path("skip").asBoolean(false);
        int skipClean = 0;
        if (skipCase) {
            boolean wrote = actual.stream().anyMatch(c -> c.action() == MemoryCandidate.Action.ADD
                    || c.action() == MemoryCandidate.Action.UPDATE
                    || c.action() == MemoryCandidate.Action.DELETE);
            skipClean = wrote ? 0 : 1;
        }

        int forbidAddViolations = expect.path("forbidAdd").asBoolean(false) && actualAdd > 0 ? 1 : 0;

        return new Metrics(1, expectedAdd, matchedAdd, actualAdd,
                expectedUpdate, matchedUpdate, actualUpdate, wrongUpdate,
                expectedDelete, matchedDelete, expectedPropose, matchedPropose,
                skipCase ? 1 : 0, skipClean, forbidAddViolations,
                usage == null ? 0 : usage.inputTokens(),
                usage == null ? 0 : usage.outputTokens());
    }

    /**
     * 报告渲染。**空结果必须写成"未评测"**——把"一条都没跑"渲染成"0 通过"是本项目踩过的坑
     * （绿 BUILD 里躺着 115 条被跳过的集成测试）。
     */
    static String render(Metrics m, int goldCases, int errors, List<String> rows) {
        StringBuilder out = new StringBuilder();
        out.append("# 记忆评测报告（提取侧）\n\n");
        out.append("- 时间：").append(Instant.now()).append('\n');
        out.append("- 提示词版本：").append(MemoryExtractionPrompt.VERSION).append('\n');
        out.append("- 模型：").append(MODEL).append('\n');
        out.append("- 金集用例：").append(goldCases).append("（调用失败 ").append(errors).append("）\n\n");
        if (m.cases() == 0) {
            out.append("**未评测**：一轮都没有跑完，下面的数字不作数。\n");
            return out.toString();
        }
        out.append("| 指标 | 数值 | 分子/分母 |\n| --- | --- | --- |\n");
        out.append(row("precision", m.matchedAdd(), m.actualAdd()));
        out.append(row("recall", m.matchedAdd(), m.expectedAdd()));
        out.append(row("update 命中率", m.matchedUpdate(), m.expectedUpdate()));
        out.append(row("错 UPDATE 率", m.wrongUpdate(), m.actualUpdate()));
        out.append(row("delete 命中率", m.matchedDelete(), m.expectedDelete()));
        out.append(row("propose 命中率", m.matchedPropose(), m.expectedPropose()));
        out.append(row("skip 正确率", m.skipClean(), m.skipCases()));
        out.append(row("该 UPDATE 却 ADD", m.forbidAddViolations(), m.cases()));
        out.append("\n- token 输入/输出：").append(m.inputTokens()).append(" / ").append(m.outputTokens())
                .append('\n');
        out.append("\n## 逐条结果\n\n");
        for (String line : rows) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String row(String name, int numerator, int denominator) {
        String value = denominator == 0
                ? "—"
                : String.format(Locale.ROOT, "%.3f", (double) numerator / denominator);
        return "| " + name + " | " + value + " | " + numerator + " / " + denominator + " |\n";
    }

    private static boolean sameStore(MemoryCandidate candidate, JsonNode expected) {
        String wanted = expected.path("store").asText("");
        if (wanted.isEmpty()) {
            return true;
        }
        return candidate.store().name().equalsIgnoreCase(wanted)
                || candidate.store().name().equals(wanted.toUpperCase(Locale.ROOT));
    }

    private static boolean contains(String content, JsonNode expected) {
        if (content == null) {
            return false;
        }
        String token = expected.path("contentContains").asText("");
        return token.isEmpty() || content.contains(token);
    }

    private static String describe(List<MemoryCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "无候选";
        }
        List<String> parts = new ArrayList<>();
        for (MemoryCandidate candidate : candidates) {
            parts.add(candidate.action() + (candidate.targetId() == null ? "" : "#" + candidate.targetId())
                    + ":" + (candidate.content() == null ? "" : candidate.content()));
        }
        return String.join(" | ", parts);
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isArray() ? new ObjectMapper().createArrayNode() : value;
    }

    private static List<MemoryExtractor.MemoryLine> memories(JsonNode node) {
        List<MemoryExtractor.MemoryLine> lines = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return lines;
        }
        for (JsonNode item : node) {
            lines.add(new MemoryExtractor.MemoryLine(
                    item.get("store").asText("text"), item.get("id").asLong(), item.get("text").asText()));
        }
        return lines;
    }

    private static List<ConversationTurn> turns(JsonNode node) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (JsonNode item : node) {
            ConversationTurn.Role role = "assistant".equals(item.get("role").asText())
                    ? ConversationTurn.Role.ASSISTANT
                    : ConversationTurn.Role.USER;
            turns.add(new ConversationTurn(role, item.get("content").asText()));
        }
        return turns;
    }

    private static JsonNode readGoldSet() throws IOException {
        try (InputStream stream = MemoryEvalTest.class.getClassLoader().getResourceAsStream(GOLD_SET)) {
            assertThat(stream).as("找不到金集 " + GOLD_SET).isNotNull();
            return new ObjectMapper().readTree(stream);
        }
    }
}
