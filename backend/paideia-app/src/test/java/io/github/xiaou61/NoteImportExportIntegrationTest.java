package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.security.AuthPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
 * .md 导入导出（WORK-087 TASK-006，AC-007）。
 *
 * <p>验证：单篇导出含正文；整库导出为 zip 并可导入回去生成同样的目录树；
 * 含 {@code ../} 的 zip 被拒（400）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class NoteImportExportIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String user;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM note_links");
        jdbcTemplate.update("DELETE FROM notes");
        jdbcTemplate.update("DELETE FROM note_books");
        user = "67" + Long.toString(System.nanoTime() % 1_000_000L);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM note_links");
        jdbcTemplate.update("DELETE FROM notes");
        jdbcTemplate.update("DELETE FROM note_books");
    }

    @Test
    @DisplayName("单篇导出含正文 / 整库 zip 往返 / zip slip 被拒")
    void exportImportRoundTrip() throws Exception {
        long bookId = createBook(user, "导出库");
        long folderId = createFolder(bookId, null, "第一章");
        long docId = createDoc(bookId, folderId, "第一节", "正文第一段");

        // 单篇导出
        MvcResult docExport = mockMvc.perform(get("/api/v1/notes/" + docId + "/export")
                .header("Authorization", token(user))).andReturn();
        assertThat(docExport.getResponse().getStatus()).isEqualTo(200);
        String markdown = docExport.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(markdown).contains("第一节").contains("正文第一段");

        // 整库导出
        MvcResult bookExport = mockMvc.perform(get("/api/v1/note-books/" + bookId + "/export")
                .header("Authorization", token(user))).andReturn();
        assertThat(bookExport.getResponse().getStatus()).isEqualTo(200);
        byte[] zip = bookExport.getResponse().getContentAsByteArray();
        assertThat(zip).isNotEmpty();

        // 导入到新库
        long targetBook = createBook(user, "导入库");
        MvcResult imported = mockMvc.perform(post("/api/v1/note-books/" + targetBook + "/import/zip")
                        .header("Authorization", token(user))
                        .contentType("application/zip")
                        .content(zip)).andReturn();
        assertThat(imported.getResponse().getStatus()).as("响应：%s", body(imported)).isEqualTo(200);
        assertThat(((Number) JsonPath.read(body(imported), "$.data.documents")).intValue()).isGreaterThanOrEqualTo(1);

        List<Object> tree = JsonPath.read(body(mockMvc.perform(get("/api/v1/note-books/" + targetBook + "/tree")
                .header("Authorization", token(user))).andReturn()), "$.data");
        assertThat(tree).isNotEmpty();
        assertThat(tree.toString()).contains("第一节");

        // zip slip 被拒
        byte[] evil = zipOf(Map.of("../evil.md", "坏东西"));
        MvcResult rejected = mockMvc.perform(post("/api/v1/note-books/" + targetBook + "/import/zip")
                        .header("Authorization", token(user))
                        .contentType("application/zip")
                        .content(evil)).andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] zipOf(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private long createBook(String subject, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/note-books")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":" + quote(name) + ",\"description\":null}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private long createFolder(long bookId, Long parentId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":" + bookId + ",\"parentId\":" + (parentId == null ? "null" : parentId)
                                + ",\"kind\":\"folder\",\"title\":" + quote(name) + ",\"body\":\"\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private long createDoc(long bookId, Long parentId, String title, String bodyText) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":" + bookId + ",\"parentId\":" + (parentId == null ? "null" : parentId)
                                + ",\"kind\":\"doc\",\"title\":" + quote(title) + ",\"body\":" + quote(bodyText) + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
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
