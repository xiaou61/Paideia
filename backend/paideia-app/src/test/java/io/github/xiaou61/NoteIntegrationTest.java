package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * 笔记 CRUD（AC-010，并覆盖三态归属）。
 *
 * <p>notes 是新模块的第一张表，所以要顺带证明它真的被建出来了、也真的只在本人范围内可见——
 * 这是"用户写的东西"最不能出错的地方。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class NoteIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM notes");
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        userA = "51" + suffix;
        userB = "52" + suffix;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM notes");
    }

    @Test
    @DisplayName("建档→列表→读→改→删走通；列表不带正文，读详情才带")
    void roundTrip() throws Exception {
        long id = create(userA, null, "我的第一条笔记", "正文第一版");

        // 列表：有标题、**没有正文**（一个几百条的列表不该把正文都拽回来）。
        // 断言"键不存在"而不是"值为 null"：后者会让 PathNotFound 与"字段被漏掉"混在一起。
        MvcResult listed = mockMvc.perform(get("/api/v1/notes").header("Authorization", token(userA)))
                .andReturn();
        assertThat(body(listed)).contains("我的第一条笔记");
        assertThat(body(listed)).doesNotContain("\"body\"");
        assertThat(JsonPath.<java.util.List<Object>>read(body(listed), "$.data")).hasSize(1);

        // 详情：带正文
        MvcResult detail = mockMvc.perform(get("/api/v1/notes/" + id).header("Authorization", token(userA)))
                .andReturn();
        assertThat(JsonPath.<String>read(body(detail), "$.data.body")).isEqualTo("正文第一版");

        // 改：标题与正文都换掉
        MvcResult updated = mockMvc.perform(put("/api/v1/notes/" + id)
                        .header("Authorization", token(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryId\":null,\"title\":\"改过的标题\",\"body\":\"正文第二版\"}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).as("响应：%s", body(updated)).isEqualTo(200);
        assertThat(JsonPath.<String>read(body(updated), "$.data.title")).isEqualTo("改过的标题");

        // 删：删掉后详情 404
        assertThat(mockMvc.perform(delete("/api/v1/notes/" + id).header("Authorization", token(userA)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get("/api/v1/notes/" + id).header("Authorization", token(userA)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("三态：匿名 401；他人读／改／删都 404（与“不存在”同一结果，不确认它存在）")
    void ownershipIsEnforced() throws Exception {
        long mine = create(userA, null, "甲的笔记", "只给甲看");

        assertThat(mockMvc.perform(get("/api/v1/notes")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/notes/" + mine)).andReturn().getResponse().getStatus()).isEqualTo(401);

        assertThat(mockMvc.perform(get("/api/v1/notes/" + mine).header("Authorization", token(userB)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(put("/api/v1/notes/" + mine)
                        .header("Authorization", token(userB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"抢改\",\"body\":\"x\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(delete("/api/v1/notes/" + mine).header("Authorization", token(userB)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        // 乙改不动，甲的笔记原样还在；乙的列表里也没有它
        assertThat(JsonPath.<String>read(body(mockMvc.perform(get("/api/v1/notes/" + mine)
                .header("Authorization", token(userA))).andReturn()), "$.data.title")).isEqualTo("甲的笔记");
        assertThat(JsonPath.<java.util.List<Object>>read(body(mockMvc.perform(get("/api/v1/notes")
                .header("Authorization", token(userB))).andReturn()), "$.data")).isEmpty();
    }

    @Test
    @DisplayName("标题不能为空、不能超长；正文可空但也不能超长")
    void validatesLengths() throws Exception {
        assertThat(createStatus(userA, "   ", "正文")).isEqualTo(400);
        assertThat(createStatus(userA, "x".repeat(201), "正文")).isEqualTo(400);
        assertThat(createStatus(userA, "标题", "x".repeat(20_001))).isEqualTo(400);
        // 正文空是允许的：先建个空壳再写内容
        assertThat(createStatus(userA, "标题", "")).isEqualTo(200);
    }

    @Test
    @DisplayName("可以挂靠条目，也可以不挂；挂的数字不会因为条目不存在而报错")
    void entryIdIsOptional() throws Exception {
        long noEntry = create(userA, null, "没挂条目", "x");
        long withEntry = create(userA, 999_999L, "挂了不存在的条目", "x");

        assertThat(JsonPath.<Object>read(body(mockMvc.perform(get("/api/v1/notes/" + noEntry)
                .header("Authorization", token(userA))).andReturn()), "$.data.entryId")).isNull();
        assertThat(JsonPath.<Integer>read(body(mockMvc.perform(get("/api/v1/notes/" + withEntry)
                .header("Authorization", token(userA))).andReturn()), "$.data.entryId")).isEqualTo(999_999);
    }

    private long create(String subject, Long entryId, String title, String body) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(entryId, title, body)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as("响应：%s", body(created)).isEqualTo(200);
        return ((Number) JsonPath.read(body(created), "$.data.id")).longValue();
    }

    private int createStatus(String subject, String title, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(null, title, body)))
                .andReturn().getResponse().getStatus();
    }

    private static String saveBody(Long entryId, String title, String body) {
        return "{\"entryId\":" + (entryId == null ? "null" : entryId)
                + ",\"title\":" + quote(title) + ",\"body\":" + quote(body) + "}";
    }

    /** 手写转义够了：这些用例的输入里只有引号与长串两种特殊情形。 */
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String token(String subject) {
        return "Bearer " + authPort.issue(new AuthPort.Subject(subject, AuthPort.ROLE_LEARNER)).value();
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
