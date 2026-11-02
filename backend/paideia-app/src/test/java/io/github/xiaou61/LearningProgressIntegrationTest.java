package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.security.AuthPort;
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

/**
 * 学习记录与学习线的集成测试（WORK-020）。
 *
 * <p>钉住四件事：
 *
 * <ul>
 *   <li><b>通过由服务端按分数算</b>——客户端只发分数，没有 status 字段可发；
 *       80% 是线，缺任一类型都不算。</li>
 *   <li><b>隔离</b>——A 的记录 B 看不到、也改不了。</li>
 *   <li><b>重置回到初态</b>，且重复重置不报错。</li>
 *   <li><b>线清单来自分类标记</b>——只有 line_kind 非空的分类出现。</li>
 * </ul>
 *
 * <p>用 111／112 两个令牌主体：本表与自评一样刻意不对 accounts 建外键。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class LearningProgressIntegrationTest {

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

    private long courseEntry() {
        return seeds.entry("什么是依赖注入");
    }

    private void reset(String subject, long entryId) throws Exception {
        mockMvc.perform(delete("/api/v1/learning/entries/" + entryId).header("Authorization", token(subject)));
    }

    /** 一次上报：练习 3/3、小测 3/3 = 全对。 */
    private void reportAllCorrect(String subject, long entryId) throws Exception {
        mockMvc.perform(put("/api/v1/learning/entries/" + entryId)
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tourStep":4,
                                 "challenges":{"c1":{"score":3,"total":3}},
                                 "quizzes":{"q1":{"score":3,"total":3}}}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("没有记录时读到初态：200 且 status=in_progress，不是 404")
    void missingRecordReturnsInitialState() throws Exception {
        long entry = courseEntry();
        reset("111", entry);

        mockMvc.perform(get("/api/v1/learning/entries/" + entry).header("Authorization", token("111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("in_progress"))
                .andExpect(jsonPath("$.data.tourStep").value(0))
                .andExpect(jsonPath("$.data.passedAt").doesNotExist());
    }

    @Test
    @DisplayName("通过判定只看分数：全对变 passed 并排出第一次复习")
    void passingIsComputedFromScores() throws Exception {
        long entry = courseEntry();
        reset("111", entry);
        reportAllCorrect("111", entry);

        mockMvc.perform(get("/api/v1/learning/entries/" + entry).header("Authorization", token("111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("passed"))
                .andExpect(jsonPath("$.data.passedAt").exists())
                .andExpect(jsonPath("$.data.reviewStage").value(0))
                .andExpect(jsonPath("$.data.reviewDueAt").exists());

        reset("111", entry);
    }

    @Test
    @DisplayName("只答一半仍是 in_progress；并且不排复习")
    void halfCorrectStaysInProgress() throws Exception {
        long entry = courseEntry();
        reset("111", entry);

        mockMvc.perform(put("/api/v1/learning/entries/" + entry)
                        .header("Authorization", token("111"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challenges":{"c1":{"score":1,"total":3}},
                                 "quizzes":{"q1":{"score":1,"total":3}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("in_progress"))
                .andExpect(jsonPath("$.data.passedAt").doesNotExist())
                .andExpect(jsonPath("$.data.reviewDueAt").doesNotExist());

        reset("111", entry);
    }

    @Test
    @DisplayName("分数越界被拒：score 大于 total、total 为 0、负数")
    void invalidScoresAreRejected() throws Exception {
        long entry = courseEntry();
        for (String body : new String[]{
                "{\"quizzes\":{\"q1\":{\"score\":5,\"total\":3}}}",
                "{\"quizzes\":{\"q1\":{\"score\":0,\"total\":0}}}",
                "{\"quizzes\":{\"q1\":{\"score\":-1,\"total\":3}}}",
                "{\"tourStep\":-1}"}) {
            mockMvc.perform(put("/api/v1/learning/entries/" + entry)
                            .header("Authorization", token("111"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("隔离：B 看不到也改不了 A 的记录")
    void otherUsersProgressIsInvisible() throws Exception {
        long entry = courseEntry();
        reset("111", entry);
        reset("112", entry);
        reportAllCorrect("111", entry);

        // B 读同一站：看到的是自己的初态，不是 A 的 passed
        mockMvc.perform(get("/api/v1/learning/entries/" + entry).header("Authorization", token("112")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("in_progress"));

        // B 的重置不会删掉 A 的记录
        reset("112", entry);
        mockMvc.perform(get("/api/v1/learning/entries/" + entry).header("Authorization", token("111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("passed"));

        reset("111", entry);
    }

    @Test
    @DisplayName("重置回到初态，且重复重置不报错")
    void resetReturnsToInitialState() throws Exception {
        long entry = courseEntry();
        reset("111", entry);
        reportAllCorrect("111", entry);

        reset("111", entry);
        reset("111", entry);

        mockMvc.perform(get("/api/v1/learning/entries/" + entry).header("Authorization", token("111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("in_progress"))
                .andExpect(jsonPath("$.data.passedAt").doesNotExist())
                .andExpect(jsonPath("$.data.challenges").isEmpty());
    }

    @Test
    @DisplayName("未发布的条目按不存在处理（不泄露草稿存在）")
    void draftEntryIsNotFound() throws Exception {
        Long draftId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE status = 'draft' LIMIT 1", Long.class);
        assertThat(draftId).as("种子数据里应当有至少一条草稿").isNotNull();

        mockMvc.perform(get("/api/v1/learning/entries/" + draftId).header("Authorization", token("111")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("匿名既读不到线清单也写不了记录")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/learning/lines")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/learning/progress")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("线清单来自分类标记：标了 line_kind 的分类才出现，且站数 = 已发布条目数")
    void linesComeFromCategoryMarks() throws Exception {
        // 种子里 source-reading 已被迁移标成 reading；临时把「后端基础」标成 course 再撤回
        long categoryId = seeds.category("backend-basics");
        String original = jdbcTemplate.queryForObject(
                "SELECT line_kind FROM knowledge_categories WHERE id = ?", String.class, categoryId);
        jdbcTemplate.update("UPDATE knowledge_categories SET line_kind = 'course' WHERE id = ?", categoryId);
        try {
            long published = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_entries WHERE category_id = ? AND status = 'published'",
                    Long.class, categoryId);

            mockMvc.perform(get("/api/v1/learning/lines").header("Authorization", token("111")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lines[?(@.slug=='backend-basics')]").exists())
                    .andExpect(jsonPath("$.data.lines[?(@.slug=='backend-basics')].stationCount")
                            .value((int) published))
                    .andExpect(jsonPath("$.data.lines[?(@.slug=='source-reading')]").exists())
                    // 「架构」这个分类没有标 line_kind，不应出现
                    .andExpect(jsonPath("$.data.lines[?(@.slug=='architecture')]").doesNotExist());
        } finally {
            jdbcTemplate.update("UPDATE knowledge_categories SET line_kind = ? WHERE id = ?", original, categoryId);
        }
    }
}
