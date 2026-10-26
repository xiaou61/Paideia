package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.OpsDiagnoser;
import io.github.xiaou61.ai.TokenUsage;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Grafana webhook 的两道门与冷却（WORK-042 AC-008/AC-011）。
 *
 * <p>安全链要求回环来源，控制器再校验令牌；两者都要证明**拒绝**路径真的拒绝。
 * 模型被替换成桩，测试只关心入口与落库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paideia.ops.hook-token=test-token",
        "paideia.ops.diagnosis.user-id=1",
        "paideia.ops.diagnosis.cooldown-seconds=600",
        "paideia.ops.prometheus-url=http://127.0.0.1:1",
        "paideia.ops.loki-url=http://127.0.0.1:1",
        "paideia.ops.query-timeout-ms=100"
})
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class OpsHookIntegrationTest {

    private static final String PAYLOAD = """
            {"status":"firing","alerts":[{"status":"firing",
             "labels":{"alertname":"%s","severity":"warning"}}]}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    OpsDiagnosisMapper mapper;

    @MockitoBean
    OpsDiagnoser diagnoser;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(diagnoser.diagnose(anyLong(), any())).thenReturn(new OpsDiagnoser.Diagnosis(
                "## 结论\n告警已确认\n## 证据\nup=0\n## 可能原因\n目标下线\n"
                        + "## 建议动作\n检查进程\n## 需要人工确认\n看面板",
                new TokenUsage(5, 6, 11), "deepseek-flash"));
    }

    @Test
    @DisplayName("缺少令牌或令牌错误返回 401，不建行")
    void badTokenIsRejected() throws Exception {
        mockMvc.perform(loopback(post("/api/v1/ops/hooks/grafana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD.formatted("alert-x")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(loopback(post("/api/v1/ops/hooks/grafana"))
                        .header("X-Paideia-Ops-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD.formatted("alert-x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("非回环来源在安全链被拒")
    void nonLoopbackIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/ops/hooks/grafana")
                        .with(request -> {
                            request.setRemoteAddr("10.1.2.3");
                            return request;
                        })
                        .header("X-Paideia-Ops-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD.formatted("alert-x")))
                // 匿名请求被回环规则拒绝时 Spring 走认证入口，表现为 401；关键是被拒而不是 200。
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("firing 告警触发诊断并落库")
    void firingAlertStartsDiagnosis() throws Exception {
        String alert = "alert-" + UUID.randomUUID();
        String body = mockMvc.perform(loopback(post("/api/v1/ops/hooks/grafana"))
                        .header("X-Paideia-Ops-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD.formatted(alert)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = json.readTree(body);
        assertThat(root.path("data").path("accepted").asBoolean())
                .as("response=%s", body)
                .isTrue();
        OpsDiagnosisRow row = awaitFinished(root.path("data").path("id").asLong());
        assertThat(row.getTriggerType()).isEqualTo("alert");
        assertThat(row.getAlertName()).isEqualTo(alert);
        assertThat(row.getStatus()).isEqualTo(OpsDiagnosisRow.STATUS_DONE);
    }

    @Test
    @DisplayName("resolved 事件不建行")
    void resolvedIsIgnored() throws Exception {
        mockMvc.perform(loopback(post("/api/v1/ops/hooks/grafana"))
                        .header("X-Paideia-Ops-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"resolved","alerts":[{"status":"resolved",
                                 "labels":{"alertname":"alert-resolved"}}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.skipped").value("no-firing"));
    }

    @Test
    @DisplayName("同一告警在冷却窗口内第二次不建新行")
    void cooldownSkipsSecondAlert() throws Exception {
        String alert = "alert-" + UUID.randomUUID();
        String payload = PAYLOAD.formatted(alert);

        String first = mockMvc.perform(loopback(post("/api/v1/ops/hooks/grafana"))
                        .header("X-Paideia-Ops-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andReturn().getResponse().getContentAsString();
        awaitFinished(json.readTree(first).path("data").path("id").asLong());

        mockMvc.perform(loopback(post("/api/v1/ops/hooks/grafana"))
                        .header("X-Paideia-Ops-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.skipped").exists());
    }

    /** MockMvc 默认不保证 remoteAddr 是回环；安全规则按它判定，测试必须显式给。 */
    private static MockHttpServletRequestBuilder loopback(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setRemoteAddr("127.0.0.1");
            return request;
        });
    }

    private OpsDiagnosisRow awaitFinished(long id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            OpsDiagnosisRow row = mapper.findById(id).orElseThrow();
            if (!OpsDiagnosisRow.STATUS_PENDING.equals(row.getStatus())
                    && !OpsDiagnosisRow.STATUS_RUNNING.equals(row.getStatus())) {
                return row;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("诊断未在 5 秒内完成");
    }
}
