package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.security.AuthPort;
import java.util.List;
import java.util.Map;
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
 * 知识库与文档树（WORK-087 TASK-002/003，AC-001/002/003）。
 *
 * <p>重点验证：建库→建目录/文档→树查询→移动防成环→软删子树→跨用户隔离。
 * 需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class NoteTreeIntegrationTest {

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
        userA = "61" + suffix;
        userB = "62" + suffix;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM note_links");
        jdbcTemplate.update("DELETE FROM notes");
        jdbcTemplate.update("DELETE FROM note_books");
    }

    @Test
    @DisplayName("建库→建目录/文档→树→读改→删目录连带子树")
    void bookAndTreeRoundTrip() throws Exception {
        long bookId = createBook(userA, "技术笔记");
        assertThat(JsonPath.<List<Object>>read(body(mockMvc.perform(get("/api/v1/note-books")
                .header("Authorization", token(userA))).andReturn()), "$.data")).hasSize(1);
        assertThat(tree(userA, bookId)).isEmpty();

        long folderId = createNode(userA, bookId, null, "folder", "第一章", "");
        long docId = createNode(userA, bookId, folderId, "doc", "第一节", "正文内容");
        assertThat(tree(userA, bookId)).hasSize(2);
        assertThat(JsonPath.<String>read(body(mockMvc.perform(get("/api/v1/notes/" + docId)
                .header("Authorization", token(userA))).andReturn()), "$.data.body")).isEqualTo("正文内容");

        // 改文档
        MvcResult updated = mockMvc.perform(put("/api/v1/notes/" + docId)
                        .header("Authorization", token(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"第一节（修订）\",\"body\":\"新正文\"}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).as("响应：%s", body(updated)).isEqualTo(200);
        assertThat(JsonPath.<String>read(body(updated), "$.data.title")).isEqualTo("第一节（修订）");

        // 删目录 → 目录与子文档都软删（树清空、直读 404）
        assertThat(mockMvc.perform(delete("/api/v1/notes/" + folderId)
                .header("Authorization", token(userA))).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(userA, bookId)).isEmpty();
        assertThat(mockMvc.perform(get("/api/v1/notes/" + docId)
                .header("Authorization", token(userA))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("教程与查漏补缺按 pairKey 配对：树返回 docType/pairKey，目录/非法值被拒")
    void pairFieldsRoundTrip() throws Exception {
        long bookId = createBook(userA, "配对库");

        long tutorial = createNodeFull(userA, bookId, null, "doc", "A1 教程", "", "tutorial", "A1");
        long gap = createNodeFull(userA, bookId, null, "doc", "查漏补缺", "", "gap_fill", "A1");

        List<Map<String, Object>> nodes = JsonPath.read(body(mockMvc.perform(
                get("/api/v1/note-books/" + bookId + "/tree")
                        .header("Authorization", token(userA))).andReturn()), "$.data");
        assertThat(nodes).hasSize(2);
        Map<String, Object> tutorialRow = nodes.stream()
                .filter(row -> ((Number) row.get("id")).longValue() == tutorial).findFirst().orElseThrow();
        assertThat(tutorialRow.get("docType")).isEqualTo("tutorial");
        assertThat(tutorialRow.get("pairKey")).isEqualTo("A1");
        Map<String, Object> gapRow = nodes.stream()
                .filter(row -> ((Number) row.get("id")).longValue() == gap).findFirst().orElseThrow();
        assertThat(gapRow.get("docType")).isEqualTo("gap_fill");
        assertThat(gapRow.get("pairKey")).isEqualTo("A1");

        // 目录不能带文档类型
        assertThat(postNode(userA, nodeBodyFull(bookId, null, "folder", "坏目录", "", "tutorial", "A1")))
                .isEqualTo(400);
        // 非法 doc_type
        assertThat(postNode(userA, nodeBodyFull(bookId, null, "doc", "坏类型", "", "essay", null)))
                .isEqualTo(400);
        // pairKey 超 64
        assertThat(postNode(userA, nodeBodyFull(bookId, null, "doc", "超长标识", "", "tutorial", "x".repeat(65))))
                .isEqualTo(400);
        // 普通文档仍可建（docType/pairKey 都为 null）
        long plain = createNode(userA, bookId, null, "doc", "普通文档", "内容");
        assertThat(plain).isPositive();
    }

    @Test
    @DisplayName("移动防成环：到自身 / 到子孙被拒；跨库目标当不存在")
    void moveRejectsCycle() throws Exception {
        long bookId = createBook(userA, "移动测试");
        long folder1 = createNode(userA, bookId, null, "folder", "A", "");
        long folder2 = createNode(userA, bookId, folder1, "folder", "B", "");
        long doc = createNode(userA, bookId, folder1, "doc", "D", "x");

        assertThat(move(userA, folder1, folder1).getResponse().getStatus()).isEqualTo(400);
        assertThat(move(userA, folder1, folder2).getResponse().getStatus()).isEqualTo(400);

        long otherBook = createBook(userA, "另一个库");
        long foreignFolder = createNode(userA, otherBook, null, "folder", "别库目录", "");
        assertThat(move(userA, doc, foreignFolder).getResponse().getStatus()).isEqualTo(404);

        // 移到库根可以
        assertThat(move(userA, doc, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("删库连带节点；库/树只本人可见")
    void bookIsolationAndCascade() throws Exception {
        long bookId = createBook(userA, "甲的库");
        createNode(userA, bookId, null, "doc", "甲文档", "x");

        // 乙看不到甲的库与树，也建不进去
        assertThat(mockMvc.perform(get("/api/v1/note-books").header("Authorization", token(userB)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(body(mockMvc.perform(get("/api/v1/note-books")
                .header("Authorization", token(userB))).andReturn()), "$.data")).isEmpty();
        assertThat(mockMvc.perform(get("/api/v1/note-books/" + bookId + "/tree")
                .header("Authorization", token(userB))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(post("/api/v1/notes")
                .header("Authorization", token(userB))
                .contentType(MediaType.APPLICATION_JSON)
                .content(nodeBody(bookId, null, "doc", "偷建", "x"))).andReturn().getResponse().getStatus()).isEqualTo(404);
        // 匿名 401
        assertThat(mockMvc.perform(get("/api/v1/note-books/" + bookId + "/tree"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        // 删库后树 404
        assertThat(mockMvc.perform(delete("/api/v1/note-books/" + bookId)
                .header("Authorization", token(userA))).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get("/api/v1/note-books/" + bookId + "/tree")
                .header("Authorization", token(userA))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- helpers

    private long createBook(String subject, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/note-books")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":" + quote(name) + ",\"description\":null}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private long createNode(String subject, long bookId, Long parentId, String kind, String title, String bodyText) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nodeBody(bookId, parentId, kind, title, bodyText)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private long createNodeFull(String subject, long bookId, Long parentId, String kind, String title,
                                String bodyText, String docType, String pairKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nodeBodyFull(bookId, parentId, kind, title, bodyText, docType, pairKey)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private int postNode(String subject, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getStatus();
    }

    private MvcResult move(String subject, long id, Long parentId) throws Exception {
        return mockMvc.perform(put("/api/v1/notes/" + id + "/move")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + (parentId == null ? "null" : parentId) + ",\"sortOrder\":0}"))
                .andReturn();
    }

    private List<Object> tree(String subject, long bookId) throws Exception {
        return JsonPath.read(body(mockMvc.perform(get("/api/v1/note-books/" + bookId + "/tree")
                .header("Authorization", token(subject))).andReturn()), "$.data");
    }

    private static String nodeBody(long bookId, Long parentId, String kind, String title, String bodyText) {
        return "{\"bookId\":" + bookId
                + ",\"parentId\":" + (parentId == null ? "null" : parentId)
                + ",\"kind\":" + quote(kind)
                + ",\"title\":" + quote(title)
                + ",\"body\":" + quote(bodyText) + "}";
    }

    private static String nodeBodyFull(long bookId, Long parentId, String kind, String title, String bodyText,
                                       String docType, String pairKey) {
        return "{\"bookId\":" + bookId
                + ",\"parentId\":" + (parentId == null ? "null" : parentId)
                + ",\"kind\":" + quote(kind)
                + ",\"title\":" + quote(title)
                + ",\"body\":" + quote(bodyText)
                + ",\"docType\":" + (docType == null ? "null" : quote(docType))
                + ",\"pairKey\":" + (pairKey == null ? "null" : quote(pairKey)) + "}";
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
