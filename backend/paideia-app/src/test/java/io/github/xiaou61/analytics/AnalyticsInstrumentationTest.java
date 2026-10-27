package io.github.xiaou61.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.security.AuthPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * 两项新埋点的写入路径（WORK-045 AC-004b）。
 *
 * <p>走 HTTP 而不是直接调 mapper：mapper 在各自模块的 internal 包里，
 * 跨模块引用会被 `ModularityTest` 拦；而"用户点了会不会记下来"本来就是端点语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AnalyticsInstrumentationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    DataSource dataSource;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("速递点击：POST 一次多一行，且条目不存在也返回 200")
    void digestClickIsRecorded() throws Exception {
        long itemId = 8_881_001L;
        long before = countClicks(itemId);

        mockMvc.perform(post("/api/v1/digest/items/{id}/click", itemId)
                        .header("Authorization", tokenFor("learner")))
                .andExpect(status().isOk());

        assertThat(countClicks(itemId)).isEqualTo(before + 1);

        // 不存在的条目同样 200：这条埋点对用户没有价值，报错只是噪音
        mockMvc.perform(post("/api/v1/digest/items/{id}/click", 8_881_999L)
                        .header("Authorization", tokenFor("learner")))
                .andExpect(status().isOk());
        assertThat(countClicks(8_881_999L)).isEqualTo(1);
    }

    @Test
    @DisplayName("搜索：带 q 才记一条，空 q 不记；记录的结果数与接口返回的总数一致")
    void searchIsRecordedOnlyWithKeyword() throws Exception {
        long beforeEmpty = countSearches();
        mockMvc.perform(get("/api/v1/knowledge/entries")
                        .header("Authorization", tokenFor("learner")))
                .andExpect(status().isOk());
        assertThat(countSearches()).as("空 q 不该记").isEqualTo(beforeEmpty);

        String keyword = "zqxjkvbwmfp" + Long.toHexString(System.nanoTime()) + "tvzq";
        long before = countSearches();
        String body = mockMvc.perform(get("/api/v1/knowledge/entries")
                        .param("q", keyword)
                        .header("Authorization", tokenFor("learner")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countSearches()).isEqualTo(before + 1);

        // **不断言"零结果"**：知识库全文索引用 ngram parser，长 ASCII 串会被切成二元组，
        // 随机词也可能命中（实测踩过：随机串返回 1 条）。要守的是"记的数与用户看到的数一致"。
        long apiTotal = JSON.readTree(body).path("data").path("total").asLong();
        assertThat(resultCountOf(keyword))
                .as("记录的结果数必须等于接口返回的总数")
                .isEqualTo(apiTotal);
    }

    @Test
    @DisplayName("超长关键词截断到 100 字符并带出结果数")
    void longKeywordIsTruncated() throws Exception {
        String longKeyword = "长".repeat(150) + System.nanoTime();
        mockMvc.perform(get("/api/v1/knowledge/entries")
                        .param("q", longKeyword)
                        .header("Authorization", tokenFor("learner")))
                .andExpect(status().isOk());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT CHAR_LENGTH(keyword) FROM knowledge_search_log ORDER BY id DESC LIMIT 1")) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(100);
            }
        }
    }

    private long countClicks(long itemId) throws Exception {
        return scalar("SELECT COUNT(*) FROM digest_item_clicks WHERE item_id = ?", itemId);
    }

    private long countSearches() throws Exception {
        return scalar("SELECT COUNT(*) FROM knowledge_search_log", null);
    }

    private long resultCountOf(String keyword) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT result_count FROM knowledge_search_log WHERE keyword = ? ORDER BY id DESC LIMIT 1")) {
            statement.setString(1, keyword);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("应当记下这条搜索").isTrue();
                return rows.getLong(1);
            }
        }
    }

    private long scalar(String sql, Long param) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (param != null) {
                statement.setLong(1, param);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private String tokenFor(String role) {
        return "Bearer " + authPort.issue(new AuthPort.Subject("1", role)).value();
    }
}
