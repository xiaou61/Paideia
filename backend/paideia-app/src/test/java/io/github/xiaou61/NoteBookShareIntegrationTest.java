package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.security.AuthPort;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 分享扩展（WORK-087 TASK-007，AC-010）。
 *
 * <p>验证：整库分享能免鉴权浏览整棵树、且只含分享者该库文档；单篇分享回归；
 * 跨用户分享他人库 404；撤回后 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class NoteBookShareIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String userA;
    private String userB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM note_links");
        jdbcTemplate.update("DELETE FROM notes");
        jdbcTemplate.update("DELETE FROM note_books");
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        userA = "71" + suffix;
        userB = "72" + suffix;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM note_links");
        jdbcTemplate.update("DELETE FROM notes");
        jdbcTemplate.update("DELETE FROM note_books");
    }

    @Test
    @DisplayName("整库分享：免鉴权读到整棵树且只含分享者文档；撤回后 404")
    void shareBookExposesOwnTreeOnly() throws Exception {
        long bookA = createBook(userA, "A 的库");
        long folder = createNode(userA, bookA, null, "folder", "第一章", "");
        createNode(userA, bookA, folder, "doc", "第一节", "正文A");
        createNode(userA, bookA, null, "doc", "独立文档", "正文B");

        // 另一个人在自己的库里放一篇同名文档，确保不会被混入
        long bookB = createBook(userB, "B 的库");
        createNode(userB, bookB, null, "doc", "别人的秘密", "不该出现");

        String shareId = shareBook(userA, bookA);

        MvcResult read = mockMvc.perform(get("/api/v1/public/shares/" + shareId)).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        String json = body(read);
        assertThat(JsonPath.read(json, "$.data.type").toString()).isEqualTo("book");
        assertThat(JsonPath.read(json, "$.data.title").toString()).isEqualTo("A 的库");
        String documents = JsonPath.read(json, "$.data.documents").toString();
        assertThat(documents).contains("第一节").contains("独立文档").doesNotContain("别人的秘密");

        // 撤回后 404
        mockMvc.perform(delete("/api/v1/shares/" + shareId).header("Authorization", token(userA)))
                .andReturn();
        assertThat(mockMvc.perform(get("/api/v1/public/shares/" + shareId)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("单篇分享回归：type=note 带正文；跨用户分享他人库 404")
    void noteShareRegressionAndCrossUser() throws Exception {
        long bookA = createBook(userA, "A 的库");
        long doc = createNode(userA, bookA, null, "doc", "单篇", "单篇正文");

        MvcResult shared = mockMvc.perform(post("/api/v1/notes/" + doc + "/share")
                .header("Authorization", token(userA))).andReturn();
        assertThat(shared.getResponse().getStatus()).isEqualTo(200);
        String shareId = (String) JsonPath.read(body(shared), "$.data");

        MvcResult read = mockMvc.perform(get("/api/v1/public/shares/" + shareId)).andReturn();
        String json = body(read);
        assertThat(JsonPath.read(json, "$.data.type").toString()).isEqualTo("note");
        assertThat(JsonPath.read(json, "$.data.title").toString()).isEqualTo("单篇");
        assertThat(JsonPath.read(json, "$.data.body").toString()).isEqualTo("单篇正文");

        // 另一个人不能分享 A 的库
        MvcResult cross = mockMvc.perform(post("/api/v1/note-books/" + bookA + "/share")
                .header("Authorization", token(userB))).andReturn();
        assertThat(cross.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("整库分享到期即 404")
    void expiredBookShareIsGone() throws Exception {
        long bookA = createBook(userA, "A 的库");
        createNode(userA, bookA, null, "doc", "会过期的", "x");
        String shareId = shareBook(userA, bookA);

        jdbcTemplate.update("UPDATE note_shares SET expires_at = ? WHERE share_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), shareId);

        assertThat(mockMvc.perform(get("/api/v1/public/shares/" + shareId)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- helpers

    private String shareBook(String subject, long bookId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/note-books/" + bookId + "/share")
                .header("Authorization", token(subject))).andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return (String) JsonPath.read(body(result), "$.data");
    }

    private long createBook(String subject, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/note-books")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":" + quote(name) + ",\"description\":null}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private long createNode(String subject, long bookId, Long parentId, String kind, String title, String text)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":" + bookId + ",\"parentId\":" + (parentId == null ? "null" : parentId)
                                + ",\"kind\":\"" + kind + "\",\"title\":" + quote(title)
                                + ",\"body\":" + quote(text) + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String token(String subject) {
        return TestTokens.bearer(authPort, subject);
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
