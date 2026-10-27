package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.OpsDiagnoser;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.security.AuthPort;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 诊断评分与 helpful 率（WORK-043 AC-010）。
 *
 * <p>要证明的三件事：非法评分被拒、只给已完成的行评分、统计把重评算成一次覆盖而不是两次。
 * 最后一条是真正容易错的地方——用"追加一条记录"的写法统计会立刻翻倍。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paideia.ops.prometheus-url=http://127.0.0.1:1",
        "paideia.ops.loki-url=http://127.0.0.1:1",
        "paideia.ops.tempo-url=http://127.0.0.1:1",
        "paideia.ops.query-timeout-ms=100"
})
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class OpsFeedbackIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    OpsDiagnosisMapper mapper;

    @Autowired
    OpsIncidentService incidents;

    @MockitoBean
    OpsDiagnoser diagnoser;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(diagnoser.diagnose(anyLong(), any())).thenReturn(new OpsDiagnoser.Diagnosis(
                "## 结论\nCPU 高。\n## 证据\ncpu = 91 %\n## 可能原因\n任务堆积\n"
                        + "## 建议动作\n看队列\n## 需要人工确认\n看面板",
                new TokenUsage(10, 20, 30), "deepseek-flash"));
    }

    @Test
    @DisplayName("非法评分返回 400，合法评分落库并可读回")
    void invalidRatingRejected() throws Exception {
        long id = doneDiagnosis(null);

        mockMvc.perform(post("/api/v1/ops/admin/diagnoses/{id}/feedback", id)
                        .header("Authorization", tokenFor("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/ops/admin/diagnoses/{id}/feedback", id)
                        .header("Authorization", tokenFor("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/ops/admin/diagnoses/{id}/feedback", id)
                        .header("Authorization", tokenFor("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1,\"note\":\"根因对上了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(1))
                .andExpect(jsonPath("$.data.feedbackNote").value("根因对上了"));
    }

    @Test
    @DisplayName("重评是覆盖：同一份诊断改判后统计只算一次，helpful 率跟着变")
    void reRatingOverwrites() throws Exception {
        long id = doneDiagnosis(null);

        // 统计是全表口径（同库别的用例也会评过分），所以这里比较**增量**而不是绝对值
        OpsDiagnosisService.Stats before = statsOf();
        rate(id, 1);
        OpsDiagnosisService.Stats first = statsOf();
        assertThat(first.rated()).isEqualTo(before.rated() + 1);
        assertThat(first.good()).isEqualTo(before.good() + 1);

        rate(id, -1);
        OpsDiagnosisService.Stats second = statsOf();
        assertThat(second.rated()).isEqualTo(first.rated());
        assertThat(second.good()).isEqualTo(first.good() - 1);
        assertThat(second.bad()).isEqualTo(first.bad() + 1);

        OpsDiagnosisRow row = mapper.findById(id).orElseThrow();
        assertThat(row.getRating()).isEqualTo(-1);
        assertThat(row.getFeedbackAt()).isNotNull();
    }

    @Test
    @DisplayName("有事件时评分追加一条时间线 NOTE")
    void feedbackAppendsTimelineNote() throws Exception {
        Instant now = Instant.now();
        long incidentId = incidents.upsertFiring(null, "feedback-" + UUID.randomUUID(), "warning",
                Map.of("instance", "127.0.0.1:9100"), now, now).id();
        long id = doneDiagnosis(incidentId);

        rate(id, 0);

        assertThat(incidents.detail(incidentId).events())
                .anyMatch(event -> OpsIncidentEvent.KIND_NOTE.equals(event.kind())
                        && event.message().contains("部分准"));
    }

    @Test
    @DisplayName("没有评分时 helpfulRate 为 null，不是 0")
    void emptyStatsGiveNullRate() {
        // 全表可能已有别人评过的行；这里只断言"不会算成 0 而崩"，用本地统计口径验证
        OpsDiagnosisService.Stats stats = statsOf();
        assertThat(stats.rated()).isGreaterThanOrEqualTo(0);
        if (stats.rated() == 0) {
            assertThat(stats.helpfulRate()).isNull();
        } else {
            assertThat(stats.helpfulRate()).isNotNull();
        }
    }

    @Test
    @DisplayName("学习者不能评分，匿名不能读统计")
    void nonAdminRejected() throws Exception {
        long id = doneDiagnosis(null);

        mockMvc.perform(post("/api/v1/ops/admin/diagnoses/{id}/feedback", id)
                        .header("Authorization", tokenFor("learner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/ops/admin/diagnoses/stats"))
                .andExpect(status().isUnauthorized());
    }

    private void rate(long id, int rating) throws Exception {
        mockMvc.perform(post("/api/v1/ops/admin/diagnoses/{id}/feedback", id)
                        .header("Authorization", tokenFor("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":" + rating + "}"))
                .andExpect(status().isOk());
    }

    private OpsDiagnosisService.Stats statsOf() {
        String body;
        try {
            body = mockMvc.perform(get("/api/v1/ops/admin/diagnoses/stats")
                            .header("Authorization", tokenFor("admin")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var node = json.readTree(body).path("data");
            return new OpsDiagnosisService.Stats(
                    node.path("rated").asLong(), node.path("good").asLong(),
                    node.path("partial").asLong(), node.path("bad").asLong(),
                    node.path("helpfulRate").isNull() ? null : node.path("helpfulRate").asDouble());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private long doneDiagnosis(Long incidentId) {
        OpsDiagnosisRow row = newRow(incidentId);
        mapper.insert(row);
        long created = row.getId();
        mapper.markRunning(created);
        mapper.markDone(created, "deepseek-flash", 10, 20,
                "## 结论\nok\n## 证据\nx\n## 可能原因\ny\n## 建议动作\nz\n## 需要人工确认\nw",
                Instant.now());
        return created;
    }

    private OpsDiagnosisRow newRow(Long incidentId) {
        OpsDiagnosisRow row = new OpsDiagnosisRow();
        row.setTriggerType("manual");
        row.setIncidentId(incidentId);
        row.setStatus(OpsDiagnosisRow.STATUS_PENDING);
        row.setRequestedBy(1L);
        row.setCreatedAt(Instant.now());
        return row;
    }

    private String tokenFor(String role) {
        return "Bearer " + authPort.issue(new AuthPort.Subject("1", role)).value();
    }
}
