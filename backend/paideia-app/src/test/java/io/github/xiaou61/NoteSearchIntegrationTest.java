package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 笔记全文检索（WORK-087 TASK-004，AC-004）。
 *
 * <p>验证 AC-004：多字中文走 ngram 全文索引命中；单字回退 LIKE 仍能命中；
 * 他人内容搜不到；空白关键词返回空；匿名 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class NoteSearchIntegrationTest {

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
        userA = "63" + suffix;
        userB = "64" + suffix;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM note_links");
        jdbcTemplate.update("DELETE FROM notes");
        jdbcTemplate.update("DELETE FROM note_books");
    }

    @Test
    @DisplayName("多字中文命中 / 单字回退 LIKE / 无关词为空 / 隔离 / 匿名 401")
    void searchHitsAndIsolation() throws Exception {
        long bookId = createBook(userA, "检索库");
        createDoc(userA, bookId, "倒排索引笔记", "这是关于倒排索引与向量检索的说明，MySQL 全文检索使用 ngram 分词。");
        createDoc(userA, bookId, "另一篇", "完全不相干的内容，讲的是做菜。");

        assertThat(searchIds(userA, "倒排索引")).as("多字应命中全文索引").hasSize(1);
        assertThat(searchIds(userA, "索")).as("单字应回退 LIKE 命中").hasSize(1);
        assertThat(searchIds(userA, "做菜")).hasSize(1);
        assertThat(searchIds(userA, "量子纠缠xyz")).as("无关词应为空").isEmpty();
        assertThat(searchIds(userA, "")).as("空白返回空").isEmpty();

        // 他人搜同一关键词，搜不到我的笔记
        assertThat(searchIds(userB, "倒排索引")).isEmpty();

        // 匿名 401
        assertThat(mockMvc.perform(get("/api/v1/notes/search").param("q", "倒排")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- helpers

    private List<Object> searchIds(String subject, String q) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/notes/search")
                        .param("q", q)
                        .header("Authorization", token(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
        return JsonPath.read(body(result), "$.data");
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

    private void createDoc(String subject, long bookId, String title, String bodyText) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":" + bookId + ",\"parentId\":null,\"kind\":\"doc\",\"title\":"
                                + quote(title) + ",\"body\":" + quote(bodyText) + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
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
