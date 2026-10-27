package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.security.AuthPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 数据大屏的性能（WORK-045 AC-007）。
 *
 * <p><b>为什么要造数据</b>：空库上跑 20 次只能证明"没有数据时很快"，那不能回答
 * "有数据以后会不会卡"。这里按生产量级灌一批（速递 600 条、AI 运行 200 条、
 * 浏览 200 条、速递点击 300 条），再测 P95。
 *
 * <p>门槛 800ms：一页 10+ 张表聚合 + 一次 Prometheus 往返。超过就分区块懒加载。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AnalyticsPerformanceTest {

    /** 跑多少次取 P95。20 次足够看出量级，又不至于让测试跑太久。 */
    private static final int ROUNDS = 20;

    /** 门槛。 */
    private static final long P95_BUDGET_MS = 800;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("有生产量级数据时 P95 在 800ms 以内")
    void p95WithinBudget() throws Exception {
        seed();

        String token = "Bearer " + authPort.issue(new AuthPort.Subject("1", "admin")).value();

        // 预热一轮：第一次请求要初始化连接池与 MyBatis 的映射缓存，
        // 把它算进 P95 会测到"冷启动"而不是"常态"
        mockMvc.perform(get("/api/v1/ops/admin/analytics/overview").header("Authorization", token))
                .andExpect(status().isOk());

        List<Long> samples = new ArrayList<>(ROUNDS);
        for (int round = 0; round < ROUNDS; round++) {
            long started = System.nanoTime();
            mockMvc.perform(get("/api/v1/ops/admin/analytics/overview")
                            .header("Authorization", token))
                    .andExpect(status().isOk());
            samples.add((System.nanoTime() - started) / 1_000_000);
        }

        Collections.sort(samples);
        long p50 = samples.get(samples.size() / 2);
        long p95 = samples.get((int) Math.ceil(samples.size() * 0.95) - 1);

        // 落一份结果：断言消息只在失败时可见，而报告里需要引用真实数字
        java.nio.file.Path out = java.nio.file.Path.of("target", "analytics-perf.txt");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out, "P50=" + p50 + "ms P95=" + p95
                + "ms max=" + samples.get(samples.size() - 1) + "ms rounds=" + ROUNDS + "\n");

        assertThat(p95)
                .as("P50=%dms P95=%dms（%d 次样本），预算 %dms", p50, p95, ROUNDS, P95_BUDGET_MS)
                .isLessThan(P95_BUDGET_MS);
    }

    /** 按生产量级灌数据。重复跑不会叠加（用固定 id 段 + 先删后插）。 */
    private void seed() throws Exception {
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            // digest_items：600 条，分布在近 14 天
            execute(connection, "DELETE FROM digest_items WHERE id BETWEEN 8800000 AND 8809999");
            for (int i = 0; i < 600; i++) {
                execute(connection, """
                        INSERT INTO digest_items (id, edition_date, group_key, source_key, source_name,
                            title, summary, url, fingerprint, heat, language, summary_state, created_at)
                        VALUES (?, DATE_SUB(CURDATE(), INTERVAL ? DAY), 'news', 'seed', '种子',
                            ?, '摘要', ?, ?, 10, 'Java', 'ready', DATE_SUB(?, INTERVAL ? DAY))
                        """,
                        8_800_000 + i, i % 14, "标题 " + i, "https://example.com/" + i,
                        "fp-" + i, now, i % 14);
            }

            // ai_runs：200 条
            execute(connection, "DELETE FROM ai_runs WHERE trace_id LIKE 'seed-perf-%'");
            for (int i = 0; i < 200; i++) {
                execute(connection, """
                        INSERT INTO ai_runs (trace_id, user_id, kind, status, started_at, input_tokens,
                            output_tokens, credits)
                        VALUES (?, 1, ?, ?, DATE_SUB(?, INTERVAL ? DAY), 100, 50, 3)
                        """,
                        "seed-perf-" + i, i % 3 == 0 ? "chat" : "digest",
                        i % 20 == 0 ? "failed" : "ok", now, i % 14);
            }

            // knowledge_entry_views：200 条。**必须用真实存在的 entry_id**：
            // 这张表有指向 knowledge_entries 的外键（与 digest_item_clicks 不同，后者刻意没有）
            List<Long> entryIds = queryIds(connection, "SELECT id FROM knowledge_entries LIMIT 50");
            if (!entryIds.isEmpty()) {
                execute(connection, "DELETE FROM knowledge_entry_views WHERE user_id BETWEEN 8800000 AND 8800099");
                for (int i = 0; i < 200; i++) {
                    execute(connection, """
                            INSERT INTO knowledge_entry_views (user_id, entry_id, view_count, first_viewed_at,
                                last_viewed_at)
                            VALUES (?, ?, 3, DATE_SUB(?, INTERVAL ? DAY), DATE_SUB(?, INTERVAL ? DAY))
                            """,
                            8_800_000 + i, entryIds.get(i % entryIds.size()), now, i % 14, now, i % 14);
                }
            }

            // digest_item_clicks：300 条
            execute(connection, "DELETE FROM digest_item_clicks WHERE user_id BETWEEN 8800000 AND 8800099");
            for (int i = 0; i < 300; i++) {
                execute(connection, """
                        INSERT INTO digest_item_clicks (user_id, item_id, clicked_at)
                        VALUES (?, ?, DATE_SUB(?, INTERVAL ? DAY))
                        """,
                        8_800_000 + (i % 50), 8_800_000 + (i % 600), now, i % 14);
            }

            // knowledge_search_log：150 条
            execute(connection, "DELETE FROM knowledge_search_log WHERE user_id BETWEEN 8800000 AND 8800099");
            for (int i = 0; i < 150; i++) {
                execute(connection, """
                        INSERT INTO knowledge_search_log (user_id, keyword, result_count, searched_at)
                        VALUES (?, ?, ?, DATE_SUB(?, INTERVAL ? DAY))
                        """,
                        8_800_000 + (i % 20), "关键词" + (i % 30), i % 4, now, i % 14);
            }

            connection.commit();
        }
    }

    private void execute(Connection connection, String sql, Object... params) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) {
                statement.setObject(index + 1, params[index]);
            }
            statement.executeUpdate();
        }
    }

    private List<Long> queryIds(Connection connection, String sql) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
        }
        return ids;
    }
}
