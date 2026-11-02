package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.security.AuthPort;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 举报与管理员下架（AC-013）。
 *
 * <p>重点是**三态**与**下架之后的效果**：学习者不能看举报列表（403），管理员能看且能下架，
 * 下架后那条免鉴权链接立刻读不到。这是免鉴权分享面唯一的治理手段。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class NoteReportIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long userA;
    private long userB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM note_reports");
        jdbcTemplate.update("DELETE FROM note_shares");
        jdbcTemplate.update("DELETE FROM notes");
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        userA = Long.parseLong("31" + suffix);
        userB = Long.parseLong("32" + suffix);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM note_reports");
        jdbcTemplate.update("DELETE FROM note_shares");
        jdbcTemplate.update("DELETE FROM notes");
    }

    @Test
    @DisplayName("举报：本人可提交、重复提交只留一条；匿名 401、空理由 400、不存在的分享 404")
    void reportIsAcceptedOnce() throws Exception {
        String shareId = shareNote();

        assertThat(reportStatus(null, shareId, "不合适的内容")).isEqualTo(401);
        assertThat(reportStatus(userB, shareId, "   ")).isEqualTo(400);
        assertThat(reportStatus(userB, "11111111-1111-1111-1111-111111111111", "不存在的"))
                .isEqualTo(404);

        assertThat(reportStatus(userB, shareId, "不合适的内容")).isEqualTo(200);
        // 同一个人再提一次：只留一条（重复提交是"再用一次"，不该堆成一屏重复记录）
        assertThat(reportStatus(userB, shareId, "改个理由")).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM note_reports", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT reason FROM note_reports", String.class))
                .isEqualTo("改个理由");
    }

    @Test
    @DisplayName("管理端列表三态：匿名 401、学习者 403、管理员 200")
    void adminListRequiresAdminRole() throws Exception {
        String shareId = shareNote();
        reportStatus(userB, shareId, "有问题");

        assertThat(mockMvc.perform(get("/api/v1/notes/admin/reports")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/notes/admin/reports").header("Authorization", token(userA)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        MvcResult admin = mockMvc.perform(get("/api/v1/notes/admin/reports").header("Authorization", adminToken()))
                .andReturn();
        assertThat(admin.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(admin)).contains("有问题").contains(shareId).contains("pending");
    }

    @Test
    @DisplayName("下架：管理员一点，免鉴权链接立刻读不到，举报标为已处理")
    void takedownMakesShareUnreadable() throws Exception {
        String shareId = shareNote();
        reportStatus(userB, shareId, "有问题");
        long reportId = jdbcTemplate.queryForObject("SELECT id FROM note_reports", Long.class);

        // 下架前：链接可读
        assertThat(mockMvc.perform(get("/api/v1/public/shares/" + shareId))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        // 学习者下架不了（403）
        assertThat(mockMvc.perform(post("/api/v1/notes/admin/reports/" + reportId + "/takedown")
                        .header("Authorization", token(userA)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        assertThat(mockMvc.perform(post("/api/v1/notes/admin/reports/" + reportId + "/takedown")
                        .header("Authorization", adminToken()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        // 下架后：同一个链接立刻读不到（免鉴权端点返回 404）
        assertThat(mockMvc.perform(get("/api/v1/public/shares/" + shareId))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM note_reports", String.class))
                .isEqualTo("taken_down");
    }

    @Test
    @DisplayName("下架不存在的举报：404，而不是静默成功")
    void takedownUnknownReportIsNotFound() throws Exception {
        assertThat(mockMvc.perform(post("/api/v1/notes/admin/reports/999999/takedown")
                        .header("Authorization", adminToken()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    private String shareNote() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryId\":null,\"title\":\"会被举报的笔记\",\"body\":\"正文\"}"))
                .andReturn();
        long noteId = ((Number) JsonPath.read(body(created), "$.data.id")).longValue();
        MvcResult shared = mockMvc.perform(post("/api/v1/notes/" + noteId + "/share")
                        .header("Authorization", token(userA)))
                .andReturn();
        return JsonPath.read(body(shared), "$.data");
    }

    private int reportStatus(Long reporter, String shareId, String reason) throws Exception {
        var request = post("/api/v1/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shareId\":\"" + shareId + "\",\"reason\":\"" + reason + "\"}");
        if (reporter != null) {
            request = request.header("Authorization", token(reporter));
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String token(long userId) {
        return TestTokens.bearer(authPort, Long.toString(userId));
    }

    private String adminToken() {
        return TestTokens.bearer(authPort, "1", AuthPort.ROLE_ADMIN);
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
