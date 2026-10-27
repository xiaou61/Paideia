package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.OpsDiagnoser;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.AuthPort;
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
 * 手动诊断端点的权限、落库与失败语义（WORK-042 AC-007/AC-010/AC-013）。
 *
 * <p>模型被替换成桩：本测试只证明"端点 → 编排 → 表"这条链路，以及失败原因确实落库。
 * 真实上游与积分结算由 `DefaultOpsDiagnoserTest` 覆盖。
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
class OpsDiagnosisIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    OpsDiagnosisMapper mapper;

    @MockitoBean
    OpsDiagnoser diagnoser;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(diagnoser.diagnose(anyLong(), any())).thenReturn(new OpsDiagnoser.Diagnosis(
                "## 结论\n服务正常。\n## 证据\ncpu = 1.0 %\n## 可能原因\n无\n"
                        + "## 建议动作\n继续观察\n## 需要人工确认\n看面板",
                new TokenUsage(10, 20, 30), "deepseek-flash"));
    }

    @Test
    @DisplayName("管理员提交后返回 202，报告最终落库并可读详情")
    void adminStartsAndReadsReport() throws Exception {
        String body = mockMvc.perform(post("/api/v1/ops/admin/diagnoses")
                        .header("Authorization", tokenFor("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"为什么慢\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn().getResponse().getContentAsString();

        long id = json.readTree(body).path("data").path("id").asLong();
        OpsDiagnosisRow row = awaitFinished(id);

        assertThat(row.getStatus()).isEqualTo(OpsDiagnosisRow.STATUS_DONE);
        assertThat(row.getReportMd()).contains("## 结论", "## 需要人工确认");
        assertThat(row.getQuestion()).isEqualTo("为什么慢");
        assertThat(row.getModel()).isEqualTo("deepseek-flash");

        mockMvc.perform(get("/api/v1/ops/admin/diagnoses/{id}", id)
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportMd").value(org.hamcrest.Matchers.containsString("## 结论")));

        mockMvc.perform(get("/api/v1/ops/admin/diagnoses").header("Authorization", tokenFor("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("学习者不能提交也不能读诊断")
    void learnerIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/ops/admin/diagnoses")
                        .header("Authorization", tokenFor("learner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/ops/admin/diagnoses").header("Authorization", tokenFor("learner")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("上游失败时仍返回 202，但行落成 FAILED 并带错误原因")
    void upstreamFailureIsPersisted() throws Exception {
        when(diagnoser.diagnose(anyLong(), any()))
                .thenThrow(new BizException(ErrorCode.INSUFFICIENT_CREDITS, "积分不足"));

        String body = mockMvc.perform(post("/api/v1/ops/admin/diagnoses")
                        .header("Authorization", tokenFor("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        long id = json.readTree(body).path("data").path("id").asLong();
        OpsDiagnosisRow row = awaitFinished(id);

        assertThat(row.getStatus()).isEqualTo(OpsDiagnosisRow.STATUS_FAILED);
        assertThat(row.getErrorMessage()).contains("积分不足");
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

    private String tokenFor(String role) {
        // 生产令牌的 subject 是账号 id（数字）；这里也必须给数字，控制器会解析它记账。
        return "Bearer " + authPort.issue(new AuthPort.Subject("1", role)).value();
    }
}
