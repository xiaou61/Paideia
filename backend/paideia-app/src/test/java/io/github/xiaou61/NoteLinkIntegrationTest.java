package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 双向链接（WORK-087 TASK-005，AC-005）。
 *
 * <p>验证：{@code [[标题]]} 与 {@code [[id:N]]} 都能解析为出链、未知标题保留为悬空、
 * 自引用被忽略、入链能看到来源、他人访问 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class NoteLinkIntegrationTest {

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
        userA = "65" + suffix;
        userB = "66" + suffix;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM note_links");
        jdbcTemplate.update("DELETE FROM notes");
        jdbcTemplate.update("DELETE FROM note_books");
    }

    @Test
    @DisplayName("标题/id 解析出链、悬空保留、自引用忽略、入链、隔离")
    void linksRoundTrip() throws Exception {
        long bookId = createBook(userA, "双链库");
        long docB = createDoc(userA, bookId, "第二篇", "被引用的文档");
        long docA = createDoc(userA, bookId, "第一篇",
                "参见 [[第二篇]] 与 [[id:" + docB + "]] 以及 [[幽灵篇]]");

        // 出链：第二篇（标题命中）+ 幽灵篇（悬空）；id 引用与标题引用同目标去重
        List<Object> outbound = links(userA, docA, "$.data.outbound");
        assertThat(outbound).as("出链应为 2 条（去重+悬空）").hasSize(2);
        Map<String, Object> first = asMap(outbound.get(0));
        assertThat(((Number) first.get("noteId")).longValue()).isEqualTo(docB);
        Map<String, Object> second = asMap(outbound.get(1));
        assertThat(second.get("noteId")).isNull();
        assertThat(second.get("title")).isEqualTo("幽灵篇");

        // 入链：docB 被第一篇引用
        List<Object> inbound = links(userA, docB, "$.data.inbound");
        assertThat(inbound).hasSize(1);
        assertThat(asMap(inbound.get(0)).get("title")).isEqualTo("第一篇");

        // 改成自引用后出链为空、docB 入链也为空
        updateDoc(userA, docA, "第一篇", "只引用自己 [[第一篇]]");
        assertThat(links(userA, docA, "$.data.outbound")).isEmpty();
        assertThat(links(userA, docB, "$.data.inbound")).isEmpty();

        // 他人访问 404
        assertThat(mockMvc.perform(get("/api/v1/notes/" + docA + "/links")
                .header("Authorization", token(userB))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- helpers

    private List<Object> links(String subject, long id, String path) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/notes/" + id + "/links")
                        .header("Authorization", token(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
        return JsonPath.read(body(result), path);
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

    private long createDoc(String subject, long bookId, String title, String bodyText) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":" + bookId + ",\"parentId\":null,\"kind\":\"doc\",\"title\":"
                                + quote(title) + ",\"body\":" + quote(bodyText) + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private void updateDoc(String subject, long id, String title, String bodyText) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/notes/" + id)
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":" + quote(title) + ",\"body\":" + quote(bodyText) + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(200);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private String token(String subject) {
        return TestTokens.bearer(authPort, subject);
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
