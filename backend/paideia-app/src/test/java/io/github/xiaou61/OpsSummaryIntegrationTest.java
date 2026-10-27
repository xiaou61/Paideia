package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.security.AuthPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 运维摘要接口（WORK-028 REQ-028/AC-001/AC-003）。
 *
 * <p>两条最要紧的断言：
 * <ol>
 *   <li><b>学习者 403、匿名 401</b>——这条规则写反顺序不会报错，只会静默失效。</li>
 *   <li><b>Prometheus 不可达时仍然 200，七个组全 UNKNOWN</b>——监控自己挂了时，
 *       页面必须显示"未知"而不是全绿。这里把 prometheus-url 指到一个不存在的端口来构造。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 指向一个必然连不上的端口：本测试要的就是"Prometheus 不在"这个分支
        "paideia.ops.prometheus-url=http://127.0.0.1:1",
        "paideia.ops.query-timeout-ms=300"
})
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class OpsSummaryIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.create(mockMvc);
    }

    @Test
    @DisplayName("管理员能读到八个分组")
    void adminSeesAllGroups() {
        assertThat(mvc.get().uri("/api/v1/ops/admin/summary").header("Authorization", tokenFor("admin")))
                .hasStatusOk()
                .matches(jsonPath("$.data.groups").value(org.hamcrest.Matchers.hasSize(8)));
    }

    @Test
    @DisplayName("Prometheus 不可达时仍是 200，除配置开关外每个分组都是 UNKNOWN")
    void unreachablePrometheusIsUnknownNotFailure() {
        assertThat(mvc.get().uri("/api/v1/ops/admin/summary").header("Authorization", tokenFor("admin")))
                .hasStatusOk()
                .matches(jsonPath("$.data.prometheusReachable").value(false))
                // 配置开关直接读 Environment，本就不依赖 Prometheus，因此它是 OK 而不是 UNKNOWN。
                // 其余七个分组全部来自 Prometheus（含 WORK-044 的 mcp），必须如实显示"未知"——
                // 不能画成 0 或正常。
                .matches(jsonPath("$.data.groups[?(@.key!='switches')].status").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("UNKNOWN"))))
                .matches(jsonPath("$.data.groups[?(@.key=='switches')].status").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("OK"))));
    }

    @Test
    @DisplayName("配置开关分组不依赖 Prometheus，有真实值")
    void switchesComeFromEnvironment() {
        assertThat(mvc.get().uri("/api/v1/ops/admin/summary").header("Authorization", tokenFor("admin")))
                .hasStatusOk()
                .matches(jsonPath("$.data.groups[?(@.key=='switches')].metrics[*].name").value(
                        org.hamcrest.Matchers.hasSize(4)));
    }

    @Test
    @DisplayName("学习者拿到 403，匿名拿到 401")
    void nonAdminIsRejected() {
        assertThat(mvc.get().uri("/api/v1/ops/admin/summary").header("Authorization", tokenFor("learner")))
                .hasStatus(org.springframework.http.HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/v1/ops/admin/summary")).hasStatus(
                org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Grafana 反代路径在应用侧不设门，鉴权交给 Grafana 自己的会话")
    void grafanaProxyDefersToGrafanaSession() {
        // 这里刻意**不断言 403**：iframe 的子请求带不上平台令牌，应用侧无法区分人。
        // 门在 Grafana 那边（匿名已关闭，未登录只拿到登录页）。测试环境里 Grafana 不可达，
        // 所以断言的是"没被应用挡成 401/403，而是走到了转发这一步"（502）。
        assertThat(mvc.get().uri("/api/v1/ops/grafana/api/health"))
                .hasStatus(org.springframework.http.HttpStatus.BAD_GATEWAY);
        assertThat(mvc.get().uri("/api/v1/ops/grafana/api/health").header("Authorization", tokenFor("learner")))
                .hasStatus(org.springframework.http.HttpStatus.BAD_GATEWAY);
    }

    private String tokenFor(String role) {
        return "Bearer " + authPort.issue(new AuthPort.Subject("test-" + role, role)).value();
    }
}
