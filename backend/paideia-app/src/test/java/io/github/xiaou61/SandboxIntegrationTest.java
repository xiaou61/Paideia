package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.sandbox.internal.run.CodeRun;
import io.github.xiaou61.sandbox.internal.run.CodeRunner;
import io.github.xiaou61.security.AuthPort;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 沙箱端点的集成测试（WORK-015）。
 *
 * <p><b>CodeRunner 是桩</b>：本机没有 Docker，真容器只能在服务器上验（见 testing/report.md）。
 * 这里要钉的是**控制器这一层**的行为：权限、校验、配额、审计、状态映射——这些与"容器能不能跑"
 * 是两件事，而它们恰好是最容易写错、也最不该靠真机才能发现的部分。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}；未配置时整类跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class SandboxIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CodeRunner runner;

    private String user;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM code_runs");
        user = "83" + (System.nanoTime() % 1_000_000L);
        // 默认"可用"，单条用例需要不可用时再覆盖
        when(runner.ready()).thenReturn(true);
        when(runner.unavailableReason()).thenReturn(null);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM code_runs");
    }

    @Test
    @DisplayName("未登录 → 401，且不产生任何执行")
    void requiresLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sandbox/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"java\",\"code\":\"class Main{}\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        verify(runner, never()).run(any());
    }

    @Test
    @DisplayName("沙箱不可用 → 503，且写一条 disabled 审计")
    void disabledWritesAuditAndReturns503() throws Exception {
        when(runner.ready()).thenReturn(false);
        when(runner.unavailableReason()).thenReturn("镜像尚未拉取");

        MvcResult result = mockMvc.perform(post("/api/v1/sandbox/run")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"java\",\"code\":\"class Main{}\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(body(result)).contains("镜像尚未拉取");
        assertThat(statuses()).containsExactly("DISABLED");
        verify(runner, never()).run(any());
    }

    @Test
    @DisplayName("语言不在白名单 → 400，且不落审计")
    void rejectsUnsupportedLanguage() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sandbox/run")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"ruby\",\"code\":\"puts 1\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(statuses()).isEmpty();
        verify(runner, never()).run(any());
    }

    @Test
    @DisplayName("代码超长 → 400")
    void rejectsOversizedCode() throws Exception {
        String code = "a".repeat(20_001);
        MvcResult result = mockMvc.perform(post("/api/v1/sandbox/run")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"" + code + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("正常运行 → 200，回实际输出；审计**不含源码**")
    void returnsOutputAndAuditsWithoutSource() throws Exception {
        when(runner.run(any())).thenReturn(new CodeRun.Result("42\n", "", 0, 120, false, false, false));

        MvcResult result = mockMvc.perform(post("/api/v1/sandbox/run")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"java\",\"code\":\"class Main{/* SECRET_MARKER */}\",\"entryId\":10,\"caseId\":\"c1\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String json = body(result);
        assertThat(JsonPath.<String>read(json, "$.data.stdout")).isEqualTo("42\n");
        assertThat(JsonPath.<Integer>read(json, "$.data.exitCode")).isEqualTo(0);

        // 审计行在，但**只有 hash 与长度**——这条是隐私口径的落点
        var rows = jdbcTemplate.queryForList(
                "SELECT status, language, code_length, code_hash, entry_id, case_id FROM code_runs WHERE user_id = ?",
                Long.parseLong(user));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("status")).isEqualTo("OK");
        assertThat(rows.getFirst().get("language")).isEqualTo("java");
        assertThat(rows.getFirst().get("code_length")).isEqualTo("class Main{/* SECRET_MARKER */}".length());
        assertThat(String.valueOf(rows.getFirst().get("code_hash"))).hasSize(64).doesNotContain("SECRET_MARKER");
        assertThat(rows.getFirst().get("case_id")).isEqualTo("c1");
    }

    @Test
    @DisplayName("SQL 运行同样写审计行：language='sql'，且不含源码（AC-013）")
    void sqlRunAuditsWithoutSource() throws Exception {
        when(runner.run(any())).thenReturn(new CodeRun.Result("", "", 0, 150, false, false, false, List.of()));

        String sql = "SELECT note FROM customers WHERE note IS NULL";
        MvcResult result = mockMvc.perform(post("/api/v1/sandbox/run")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"sql\",\"code\":\"" + sql + "\",\"dataset\":\"orders-public\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var rows = jdbcTemplate.queryForList(
                "SELECT language, code_length, code_hash FROM code_runs WHERE user_id = ?",
                Long.parseLong(user));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("language")).isEqualTo("sql");
        assertThat(rows.getFirst().get("code_length")).isEqualTo(sql.length());
        assertThat(String.valueOf(rows.getFirst().get("code_hash"))).hasSize(64).doesNotContain("customers");
    }

    @Test
    @DisplayName("超时与内存超限是**结果**不是错误：200 + 标志位")
    void timeoutIsAResultNotAnError() throws Exception {
        when(runner.run(any())).thenReturn(new CodeRun.Result("", "Killed", 137, 10_000, true, true, false));

        MvcResult result = mockMvc.perform(post("/api/v1/sandbox/run")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"java\",\"code\":\"while(true){}\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String json = body(result);
        assertThat(JsonPath.<Boolean>read(json, "$.data.timedOut")).isTrue();
        assertThat(JsonPath.<Boolean>read(json, "$.data.outOfMemory")).isTrue();
        assertThat(statuses()).containsExactly("OUT_OF_MEMORY");
    }

    @Test
    @DisplayName("额度用尽 → 429，且**被拒绝的不计入次数**")
    void quotaExhaustion() throws Exception {
        when(runner.run(any())).thenReturn(new CodeRun.Result("", "", 1, 5, false, false, false));
        String token = token();

        // 上限 50（application-test 用默认值）：跑到刚好用满
        for (int index = 0; index < 50; index += 1) {
            MvcResult ok = mockMvc.perform(post("/api/v1/sandbox/run")
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"language\":\"python\",\"code\":\"print(1)\"}"))
                    .andReturn();
            assertThat(ok.getResponse().getStatus()).as("第 %s 次应当放行", index + 1).isEqualTo(200);
        }

        MvcResult rejected = mockMvc.perform(post("/api/v1/sandbox/run")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"print(1)\"}"))
                .andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(rejected)).contains("明天再来");

        // 关键：被拒两次之后仍然只有 50 次有效计数——否则用户会被自己的重试永久锁死
        mockMvc.perform(post("/api/v1/sandbox/run")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"print(1)\"}"))
                .andReturn();
        Integer counted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM code_runs WHERE user_id = ? AND status NOT IN ('rejected','disabled')",
                Integer.class, Long.parseLong(user));
        assertThat(counted).isEqualTo(50);
        Integer rejectedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM code_runs WHERE user_id = ? AND status = 'rejected'",
                Integer.class, Long.parseLong(user));
        assertThat(rejectedRows).as("被拒的也要留审计").isEqualTo(2);
    }

    @Test
    @DisplayName("状态端点回能力与当天用量")
    void statusReportsCapability() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sandbox/status")
                        .header("Authorization", token()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String json = body(result);
        assertThat(JsonPath.<Boolean>read(json, "$.data.ready")).isTrue();
        assertThat(JsonPath.<Integer>read(json, "$.data.dailyLimit")).isEqualTo(50);
        // sql 是否出现取决于配置里有没有 sql-image（WORK-032）：语言列表按镜像可用性给出，
        // 不是写死的常量。所以只断言"包含 java 与 python"，sql 允许在配置了镜像时出现。
        assertThat(JsonPath.<java.util.List<String>>read(json, "$.data.languages"))
                .contains("java", "python");
        // WORK-035 T-17 把 SQL 日配额从 20 提到 500：500 次按每次 3 秒算也是 25 分钟
        // 不间断地跑，正常人碰不到，但脚本刷有个天花板。断言跟着实现走。
        assertThat(JsonPath.<Integer>read(json, "$.data.sqlDailyLimit")).isEqualTo(500);
        // 数据集清单在 WORK-035 从"字符串数组"变成"对象数组"（多了 name 与 source），
        // 因为选择器要显示来源。这里断言的是语义不变：公开集在、隐藏集不在。
        assertThat(JsonPath.<java.util.List<java.util.Map<String, Object>>>read(json, "$.data.datasets"))
                .as("只下发公开数据集；隐藏数据集由服务端持有（2026-10-23 起不再下发）")
                .extracting(entry -> entry.get("key"))
                .contains("orders-public")
                .doesNotContain("orders-hidden-a");
    }

    private String token() {
        return "Bearer " + authPort.issue(new AuthPort.Subject(user, AuthPort.ROLE_LEARNER)).value();
    }

    private java.util.List<String> statuses() {
        return jdbcTemplate.queryForList(
                "SELECT status FROM code_runs WHERE user_id = ? ORDER BY id", String.class, Long.parseLong(user));
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
