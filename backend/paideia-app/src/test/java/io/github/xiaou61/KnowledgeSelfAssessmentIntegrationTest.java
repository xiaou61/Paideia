package io.github.xiaou61;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.security.AuthPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 学习者自评的集成测试，重点是**隔离**。
 *
 * <p>为什么单独一个类、为什么两个方向都查：自评是本项目第一条用户私有业务数据，隔离写错
 * 会串号。而"能标记、能看到自己的"这类断言**在串号时照样通过**——所以真正要钉住的是
 * "B 看不到 A 的标记"，且必须**同时覆盖列表与详情两条读路径**（它们是两条不同的查询）。
 *
 * <p>两个用户用 101／102 两个令牌主体即可：本表刻意不对 accounts 建外键（知识模块不拥有账号），
 * 归属只由令牌决定。
 *
 * <p><b>种子 id 现查，不写死</b>（2026-10-18 改）：写死 id 时，同库上别的测试删掉种子行就会让本类
 * 报"读不到 id=1"，而单类跑总是绿的。现在按标题查（见 {@link SeedIds}）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class KnowledgeSelfAssessmentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthPort authPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SeedIds seeds;

    @BeforeEach
    void resolveSeedIds() {
        seeds = new SeedIds(jdbcTemplate);
    }

    private String token(String subject) {
        return TestTokens.bearer(authPort, subject);
    }

    private void clear(String subject, long entryId) throws Exception {
        mockMvc.perform(delete("/api/v1/knowledge/entries/" + entryId + "/self-assessment")
                .header("Authorization", token(subject)));
    }

    private void mark(String subject, long entryId, String level) throws Exception {
        mockMvc.perform(put("/api/v1/knowledge/entries/" + entryId + "/self-assessment")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"" + level + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCannotMark() throws Exception {
        long entry = seeds.entry("什么是依赖注入");

        mockMvc.perform(put("/api/v1/knowledge/entries/" + entry + "/self-assessment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"understood\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void markingIsVisibleOnlyToTheMarkerOnBothReadPaths() throws Exception {
        long entry = seeds.entry("什么是依赖注入");
        // 从干净状态开始：这个类会真的写数据，重跑时不能让上一次的残留影响断言
        clear("101", entry);
        clear("102", entry);

        mark("101", entry, "understood");

        // 详情路径：A 看得到自己的标记
        mockMvc.perform(get("/api/v1/knowledge/entries/" + entry).header("Authorization", token("101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selfAssessment").value("understood"));

        // 详情路径：B 看到的是 null——这是最容易漏的一条（列表过滤了、详情忘了）
        mockMvc.perform(get("/api/v1/knowledge/entries/" + entry).header("Authorization", token("102")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selfAssessment").doesNotExist());

        // 列表路径：A 有、B 没有
        mockMvc.perform(get("/api/v1/knowledge/self-assessments").header("Authorization", token("101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.entryId==" + entry + ")]").exists());
        mockMvc.perform(get("/api/v1/knowledge/self-assessments").header("Authorization", token("102")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        clear("101", entry);
    }

    @Test
    void markingAgainUpdatesInsteadOfInsertingASecondRow() throws Exception {
        long entry = seeds.entry("线程与锁的基本认识");
        clear("101", entry);
        mark("101", entry, "understood");
        mark("101", entry, "unsure");

        mockMvc.perform(get("/api/v1/knowledge/entries/" + entry).header("Authorization", token("101")))
                .andExpect(jsonPath("$.data.selfAssessment").value("unsure"));
        // 唯一键 (user_id, entry_id) 让它是一次更新；列表里也只该出现一条。
        // 用 Jayway 读出命中项再数，而不是把 length() 写进 JsonPath 表达式——后者在这里
        // 返回的是整个数组的长度，语义对不上（实测会给出假绿/假红）。
        String body = mockMvc.perform(get("/api/v1/knowledge/self-assessments")
                        .header("Authorization", token("101")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        java.util.List<Integer> matched = com.jayway.jsonpath.JsonPath.read(
                body, "$.data[?(@.entryId==" + entry + ")].entryId");
        org.assertj.core.api.Assertions.assertThat(matched).hasSize(1);

        clear("101", entry);
    }

    @Test
    void clearingTwiceIsIdempotent() throws Exception {
        long entry = seeds.entry("什么是依赖注入");
        clear("101", entry);
        mark("101", entry, "unsure");

        // 第一次真的删掉了，第二次本来就没有——两次都该是成功（用户点两下不该看到报错）
        clear("101", entry);
        mockMvc.perform(delete("/api/v1/knowledge/entries/" + entry + "/self-assessment")
                        .header("Authorization", token("101")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/knowledge/entries/" + entry).header("Authorization", token("101")))
                .andExpect(jsonPath("$.data.selfAssessment").doesNotExist());
    }

    @Test
    void listingCanBeFilteredByLevel() throws Exception {
        long first = seeds.entry("什么是依赖注入");
        long second = seeds.entry("线程与锁的基本认识");
        clear("101", first);
        clear("101", second);
        mark("101", first, "understood");
        mark("101", second, "unsure");

        mockMvc.perform(get("/api/v1/knowledge/self-assessments?level=unsure")
                        .header("Authorization", token("101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.entryId==" + second + ")]").exists())
                .andExpect(jsonPath("$.data[?(@.entryId==" + first + ")]").doesNotExist());

        clear("101", first);
        clear("101", second);
    }

    @Test
    void draftCannotBeMarked() throws Exception {
        // 种子里的草稿。允许标记草稿等于告诉他"这里有未发布的内容"
        long draft = seeds.entry("虚拟线程的落地注意（草稿）");

        mockMvc.perform(put("/api/v1/knowledge/entries/" + draft + "/self-assessment")
                        .header("Authorization", token("101"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"understood\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidLevelIsRejected() throws Exception {
        long entry = seeds.entry("什么是依赖注入");

        mockMvc.perform(put("/api/v1/knowledge/entries/" + entry + "/self-assessment")
                        .header("Authorization", token("101"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"maybe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
