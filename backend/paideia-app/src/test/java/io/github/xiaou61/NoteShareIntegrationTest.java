package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.security.AuthPort;
import java.time.Instant;
import java.util.UUID;
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
 * 只读分享（AC-011、AC-012）。
 *
 * <p>这是全项目第二处免鉴权读端点，所以用例的重点不是"能读到"，而是**边界**：
 * 不带令牌能读、id 不可枚举、产物不含作者与学习痕迹、撤回与到期后读不到、别人撤不了我的。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class NoteShareIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM note_shares");
        jdbcTemplate.update("DELETE FROM notes");
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        userA = Long.parseLong("41" + suffix);
        userB = Long.parseLong("42" + suffix);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM note_shares");
        jdbcTemplate.update("DELETE FROM notes");
    }

    @Test
    @DisplayName("免登录可读；产物只有标题、正文与所挂条目，没有作者与任何学习痕迹")
    void shareIsReadableWithoutLoginAndCarriesNothingElse() throws Exception {
        long noteId = createNote(userA, 7L, "分享出去的一条", "正文内容");
        String shareId = share(userA, noteId);

        // **不带 Authorization**：这是免鉴权端点存在的意义
        MvcResult read = mockMvc.perform(get("/api/v1/public/shares/" + shareId)).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        String body = body(read);
        assertThat(JsonPath.<String>read(body, "$.data.title")).isEqualTo("分享出去的一条");
        assertThat(JsonPath.<String>read(body, "$.data.body")).isEqualTo("正文内容");
        assertThat(JsonPath.<Integer>read(body, "$.data.entryId")).isEqualTo(7);
        // 产物里不该有任何身份信息
        assertThat(body).doesNotContain("userId").doesNotContain("user_id")
                .doesNotContain(String.valueOf(userA));
    }

    @Test
    @DisplayName("share_id 是 UUID（不可枚举）；随手编一个 id 读不到")
    void shareIdIsNotEnumerable() throws Exception {
        String shareId = share(userA, createNote(userA, null, "标题", "正文"));

        // UUID 形状，不是自增数字——自增能被顺序猜出来，那等于把所有人的分享摊开
        assertThat(shareId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        assertThat(mockMvc.perform(get("/api/v1/public/shares/" + UUID.randomUUID()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/v1/public/shares/1"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("撤回后立刻读不到；别人撤不了我的（返回 404 且我的仍可读）")
    void revokeInvalidatesAndIsOwnerOnly() throws Exception {
        long noteId = createNote(userA, null, "待撤回", "正文");
        String shareId = share(userA, noteId);

        // 别人撤：撤不动，且不确认它存在
        assertThat(mockMvc.perform(delete("/api/v1/shares/" + shareId).header("Authorization", token(userB)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/v1/public/shares/" + shareId))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        // 本人撤：立刻失效
        assertThat(mockMvc.perform(delete("/api/v1/shares/" + shareId).header("Authorization", token(userA)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get("/api/v1/public/shares/" + shareId))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("到期后读不到——读取时判定，不靠定时任务")
    void expiredShareIsGone() throws Exception {
        String shareId = share(userA, createNote(userA, null, "会过期", "正文"));
        jdbcTemplate.update("UPDATE note_shares SET expires_at = ? WHERE share_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), shareId);

        assertThat(mockMvc.perform(get("/api/v1/public/shares/" + shareId))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("不能分享别人的笔记；匿名不能生成分享（401）")
    void shareRequiresOwnership() throws Exception {
        long mine = createNote(userA, null, "甲的笔记", "正文");

        assertThat(mockMvc.perform(post("/api/v1/notes/" + mine + "/share"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post("/api/v1/notes/" + mine + "/share")
                        .header("Authorization", token(userB)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM note_shares", Long.class)).isZero();
    }

    @Test
    @DisplayName("能找回自己已发出的分享（否则离开页面就撤不回来），且只看得到自己的")
    void activeSharesAreListedForOwnerOnly() throws Exception {
        long noteId = createNote(userA, null, "已分享", "正文");
        String shareId = share(userA, noteId);

        MvcResult mine = mockMvc.perform(get("/api/v1/notes/" + noteId + "/shares")
                        .header("Authorization", token(userA)))
                .andReturn();
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(mine)).contains(shareId);

        // 别人连"这条笔记有没有被分享过"都不该答（笔记本身就不是他的）
        assertThat(mockMvc.perform(get("/api/v1/notes/" + noteId + "/shares")
                        .header("Authorization", token(userB)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        // 撤回之后列表里就没有了
        mockMvc.perform(delete("/api/v1/shares/" + shareId).header("Authorization", token(userA)));
        assertThat(body(mockMvc.perform(get("/api/v1/notes/" + noteId + "/shares")
                .header("Authorization", token(userA))).andReturn())).doesNotContain(shareId);
    }

    private long createNote(long userId, Long entryId, String title, String body) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryId\":" + (entryId == null ? "null" : entryId)
                                + ",\"title\":\"" + title + "\",\"body\":\"" + body + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as("响应：%s", body(created)).isEqualTo(200);
        return ((Number) JsonPath.read(body(created), "$.data.id")).longValue();
    }

    private String share(long userId, long noteId) throws Exception {
        MvcResult shared = mockMvc.perform(post("/api/v1/notes/" + noteId + "/share")
                        .header("Authorization", token(userId)))
                .andReturn();
        assertThat(shared.getResponse().getStatus()).as("响应：%s", body(shared)).isEqualTo(200);
        return JsonPath.read(body(shared), "$.data");
    }

    private String token(long userId) {
        return "Bearer " + authPort.issue(new AuthPort.Subject(Long.toString(userId), AuthPort.ROLE_LEARNER)).value();
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
