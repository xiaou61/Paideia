package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.security.AuthPort;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Test
    @DisplayName("未分类的已发布条目也出现在「我在学什么」里")
    void uncategorizedPublishedEntryStillAppears() throws Exception {
        // category_id 允许 NULL（V3「NULL = 未分类」）：分类用内连接会把这类条目整行丢掉
        jdbcTemplate.update("""
                INSERT INTO knowledge_entries (category_id, title, body, status, published_at)
                VALUES (NULL, '未分类自查条目', '正文', 'published', NOW(3))
                """);
        Long entryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE title = '未分类自查条目'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO learning_progress (user_id, entry_id, challenges_json, quizzes_json)
                VALUES (111, ?, '{}', '{}')
                """, entryId);
        try {
            mockMvc.perform(get("/api/v1/learning/progress").header("Authorization", token("111")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.entryId==" + entryId + ")].entryId").exists());
        } finally {
            jdbcTemplate.update("DELETE FROM learning_progress WHERE entry_id = ?", entryId);
            jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
        }
    }

    /**
     * 并发写**不同块**不能互相覆盖。
     *
     * <p>旧实现是「读旧快照 → 各自合并 → 整行 upsert」：两个并发请求都从同一旧值出发，
     * 后写整体覆盖先写，先写的那块丢失。现在 {@code update()} 先 {@code ensureRow} 再
     * {@code SELECT … FOR UPDATE} 锁住最新行再合并，第二个请求必须等第一个提交后才读，
     * 因此两块的分数都要保留。这条用例在旧实现下会稳定丢掉一个块，属真实回归。
     */
    @Test
    @DisplayName("并发上报不同块不丢更新：两个请求的分数都保留")
    void concurrentDisjointBlocksBothSurvive() throws Exception {
        long entry = courseEntry();
        String subject = "113";
        for (int i = 0; i < 5; i++) {
            reset(subject, entry);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> {
                    awaitQuietly(start);
                    reportBlock(subject, entry, "c1", 2, 3);
                });
                Future<?> second = pool.submit(() -> {
                    awaitQuietly(start);
                    reportBlock(subject, entry, "c2", 3, 3);
                });
                start.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            mockMvc.perform(get("/api/v1/learning/entries/" + entry).header("Authorization", token(subject)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.challenges.c1").exists())
                    .andExpect(jsonPath("$.data.challenges.c2").exists());
        }
        reset(subject, entry);
    }

    /** 只上报一个练习块（用于并发断开块）。 */
    private void reportBlock(String subject, long entryId, String block, int score, int total) {
        try {
            mockMvc.perform(put("/api/v1/learning/entries/" + entryId)
                            .header("Authorization", token(subject))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"challenges\":{\"" + block + "\":{\"score\":" + score
                                    + ",\"total\":" + total + "}}}"))
                    .andExpect(status().isOk());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
