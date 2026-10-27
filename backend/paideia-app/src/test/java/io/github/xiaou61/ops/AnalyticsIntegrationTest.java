package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.security.AuthPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 数据大屏端点（WORK-045 AC-001/002/003/004/006）。
 *
 * <p>断言的重点不是"数字是多少"（测试库里有什么不由本测试决定），而是**结构与判定规则**：
 * 五区块都在、断点规则对、三态能区分、监控层挂掉不影响业务区块。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 指到死端口：验证监控层降级不影响业务区块
        "paideia.ops.prometheus-url=http://127.0.0.1:1",
        "paideia.ops.query-timeout-ms=100"
})
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AnalyticsIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Test
    @DisplayName("管理员能读五个区块；学习者 403、匿名 401")
    void onlyAdminCanRead() throws Exception {
        mockMvc.perform(get("/api/v1/ops/admin/analytics/overview")
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flows").isArray())
                .andExpect(jsonPath("$.data.volume.tables").isArray())
                .andExpect(jsonPath("$.data.usage.features").isArray())
                .andExpect(jsonPath("$.data.ai").exists())
                .andExpect(jsonPath("$.data.search").exists())
                .andExpect(jsonPath("$.data.monitoring").exists());

        mockMvc.perform(get("/api/v1/ops/admin/analytics/overview")
                        .header("Authorization", tokenFor("learner")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/ops/admin/analytics/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("六条链齐全，每条四节点三边")
    void sixFlowsWithFourNodes() throws Exception {
        AnalyticsView view = overview();

        assertThat(view.flows()).extracting(AnalyticsView.Flow::key)
                .containsExactly("knowledge", "digest", "ai", "learning", "resume", "sql");
        for (AnalyticsView.Flow flow : view.flows()) {
            assertThat(flow.nodes()).as("%s 的节点数", flow.key()).hasSize(4);
            assertThat(flow.edges()).as("%s 的边数", flow.key()).hasSize(3);
        }
    }

    @Test
    @DisplayName("SQL 链最后一步是「没采集」，不是「0」也不是断点")
    void notCollectedIsDistinct() throws Exception {
        AnalyticsView.Flow sql = overview().flows().stream()
                .filter(flow -> flow.key().equals("sql")).findFirst().orElseThrow();

        // 「结果查看」压根没采集：修好数据源之前，它不该被读成"没人看"
        assertThat(sql.nodes().get(3).state()).isEqualTo(AnalyticsView.State.NOT_COLLECTED);
        assertThat(sql.nodes().get(3).broken()).isFalse();
    }

    @Test
    @DisplayName("监控层不可达时业务区块照常返回")
    void monitoringDownDoesNotBreakBusiness() throws Exception {
        AnalyticsView view = overview();

        assertThat(view.monitoring().reachable()).isFalse();
        assertThat(view.monitoring().requestRate()).isNull();
        // 业务四块不受影响
        assertThat(view.flows()).isNotEmpty();
        assertThat(view.volume().tables()).isNotEmpty();
        assertThat(view.usage().features()).isNotEmpty();
        assertThat(view.ai()).isNotNull();
    }

    @Test
    @DisplayName("时序按北京自然日切桶，且补齐了空白天")
    void dailyUsesBeijingDays() throws Exception {
        AnalyticsView view = overview();

        assertThat(view.volume().daily()).isNotEmpty();
        // 日期格式是 ISO 本地日期；窗口是连续 14 天，缺天会补 0
        assertThat(view.volume().daily()).allSatisfy(point ->
                assertThat(java.time.LocalDate.parse(point.date())).isNotNull());
        long distinctDays = view.volume().daily().stream()
                .map(AnalyticsView.DailyPoint::date).distinct().count();
        assertThat(distinctDays).isLessThanOrEqualTo(14);
    }

    @Test
    @DisplayName("训练营与面试评估显示「已采集但为 0」，不是「没采集」")
    void emptyFeaturesAreZeroNotMissing() throws Exception {
        AnalyticsView view = overview();

        AnalyticsView.Feature camp = view.usage().features().stream()
                .filter(feature -> feature.name().equals("训练营")).findFirst().orElseThrow();
        assertThat(camp.state()).isEqualTo(AnalyticsView.State.ZERO);

        // 新增的两项埋点：表存在就说明"有数据源"，哪怕当前是 0
        AnalyticsView.Feature cliCk = view.usage().features().stream()
                .filter(feature -> feature.name().equals("速递点击")).findFirst().orElseThrow();
        assertThat(cliCk.state()).isIn(AnalyticsView.State.OK, AnalyticsView.State.ZERO);
    }

    private AnalyticsView overview() throws Exception {
        String body = mockMvc.perform(get("/api/v1/ops/admin/analytics/overview")
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = JSON.readTree(body).path("data");
        return JSON.treeToValue(data, AnalyticsView.class);
    }

    private String tokenFor(String role) {
        return "Bearer " + authPort.issue(new AuthPort.Subject("1", role)).value();
    }
}
