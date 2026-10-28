package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.internal.memory.MemoryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 召回侧的离线评测（WORK-050 TASK-002 / TASK-003）。
 *
 * <p><b>与提取侧分开</b>：这里不需要模型，只需要库——排序是 SQL 里算的。所以它默认参与构建，
 * 而提取侧那一个没有凭据就整类跳过。
 *
 * <p>判定语义来自金集文件：含查询关键词的那条无论多旧都该排第一。改动前（按 `created_at DESC`）
 * 它会被"新但无关"的记忆压到后面；改动后必须稳定排第一。
 *
 * <p><b>只出数字，不设闸</b>：基线要用改动前的代码跑出来。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class MemoryRecallEvalTest {

    private static final String REPORT = "target/memory-eval/recall-report.md";

    @Autowired
    MemoryStore memories;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("召回金集：算 Recall@8 与 MRR 并写出报告")
    void recallGoldSet() throws Exception {
        JsonNode root = readGoldSet();
        JsonNode cases = root.get("cases");
        assertThat(cases).as("金集里必须有 cases 数组").isNotNull();
        assertThat(cases.size()).as("用例数应与 TASK-002 约定的 30 条一致").isEqualTo(30);

        int total = 0;
        int firstHit = 0;
        int inTop = 0;
        double reciprocalRank = 0;
        List<String> rows = new ArrayList<>();
        for (JsonNode node : cases) {
            jdbcTemplate.update("DELETE FROM ai_memories_structured");
            jdbcTemplate.update("DELETE FROM ai_memories_text");
            long userId = 9_050_000L + total;
            long expectedId = node.get("expectFirst").asLong();
            String expectedText = seed(userId, node.get("memories"), expectedId);

            List<MemoryStore.MemoryItem> recalled = memories.recall(userId, node.get("query").asText(""));
            int rank = 0;
            for (int index = 0; index < recalled.size(); index += 1) {
                if (recalled.get(index).text().equals(expectedText)) {
                    rank = index + 1;
                    break;
                }
            }
            total += 1;
            if (rank > 0) {
                inTop += 1;
                reciprocalRank += 1.0 / rank;
            }
            if (rank == 1) {
                firstHit += 1;
            }
            rows.add("- " + node.get("id").asText() + " 期望第 1，实际第 " + (rank == 0 ? "未召回" : rank)
                    + "（返回 " + recalled.size() + " 条）");
        }

        Path report = Path.of(REPORT);
        Files.createDirectories(report.getParent());
        Files.writeString(report, render(total, firstHit, inTop, reciprocalRank, rows),
                StandardCharsets.UTF_8);

        assertThat(total).isEqualTo(30);
        assertThat(report).exists();
    }

    /** 写入一条用例的全部记忆；返回期望命中的那条的正文，供排名比对。 */
    private String seed(long userId, JsonNode memoriesNode, long expectedId) {
        String expectedText = null;
        for (JsonNode node : memoriesNode) {
            long id = node.get("id").asLong();
            String text = node.get("text").asText();
            Instant createdAt = Instant.now().minus(node.get("ageDays").asLong(), ChronoUnit.DAYS);
            if ("structured".equals(node.get("store").asText())) {
                jdbcTemplate.update("""
                        INSERT INTO ai_memories_structured
                            (user_id, kind, ref_id, content, source, created_at)
                        VALUES (?, ?, ?, ?, 'auto', ?)
                        """,
                        userId, node.get("kind").asText(), node.has("refId") ? node.get("refId").asLong() : null,
                        text, Timestamp.from(createdAt));
            } else {
                jdbcTemplate.update("""
                        INSERT INTO ai_memories_text (user_id, content, source, created_at)
                        VALUES (?, ?, 'auto', ?)
                        """, userId, text, Timestamp.from(createdAt));
            }
            if (id == expectedId) {
                expectedText = text;
            }
        }
        return expectedText;
    }

    private static String render(int total, int firstHit, int inTop, double reciprocalRank,
                                 List<String> rows) {
        StringBuilder out = new StringBuilder();
        out.append("# 记忆评测报告（召回侧）\n\n");
        out.append("- 时间：").append(Instant.now()).append('\n');
        out.append("- 金集用例：").append(total).append('\n');
        if (total == 0) {
            out.append("\n**未评测**：一条都没有跑完，下面的数字不作数。\n");
            return out.toString();
        }
        out.append("\n| 指标 | 数值 | 分子/分母 |\n| --- | --- | --- |\n");
        out.append("| 首位命中率 | ").append(format(firstHit, total)).append(" | ")
                .append(firstHit).append(" / ").append(total).append(" |\n");
        out.append("| Recall@8 | ").append(format(inTop, total)).append(" | ")
                .append(inTop).append(" / ").append(total).append(" |\n");
        out.append("| MRR | ").append(String.format(java.util.Locale.ROOT, "%.3f", reciprocalRank / total))
                .append(" | 1/排名之和 ").append(String.format(java.util.Locale.ROOT, "%.2f", reciprocalRank))
                .append(" / ").append(total).append(" |\n");
        out.append("\n## 逐条结果\n\n");
        for (String line : rows) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String format(int numerator, int denominator) {
        return String.format(java.util.Locale.ROOT, "%.3f", (double) numerator / denominator);
    }

    private static JsonNode readGoldSet() throws Exception {
        // 金集只有一份，长在 paideia-ai 的测试资源里。测试资源的 classpath 不跨模块，
        // 所以这里按模块相对路径读文件——**不复制第二份**，否则两份迟早会漂。
        // surefire 的工作目录是模块根（backend/paideia-app）。
        Path file = Path.of("..", "paideia-ai", "src", "test", "resources", "golden", "memory",
                "recall-cases.json");
        assertThat(file).as("找不到金集 " + file.toAbsolutePath()).exists();
        return new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
    }
}
