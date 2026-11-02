package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.xiaou61.ai.internal.memory.MemoryStore;
import io.github.xiaou61.platform.BizException;
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
 * 记忆两路的存取与召回（AC-004）。
 *
 * <p>直接驱动 {@code MemoryStore} 而不是走 HTTP：写入端点属于 TASK-009（"助手写本人私有数据"
 * 那条确认路径），本片要证明的是**两条路各自记得住、各自取得回**，以及"严格私有"这条边界。
 * 走 HTTP 只会多一层与主题无关的装配。
 *
 * <p>用户身份用任意数字：本模块的表不建跨 users 外键（见 design 的纠正），与本项目其它
 * 集成测试一致。需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class LearnerMemoryIntegrationTest {

    @Autowired
    MemoryStore memories;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    private long userA;
    private long userB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        userA = Long.parseLong("81" + suffix);
        userB = Long.parseLong("82" + suffix);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
    }

    @Test
    @DisplayName("结构化记忆：写入后被召回，kind 与 ref 都留着")
    void structuredMemoryIsWrittenAndRecalled() {
        memories.rememberStructured(userA, "entry_understanding", 42L, "第 42 条：还不懂");

        List<MemoryStore.MemoryItem> recalled = memories.recall(userA, null);

        assertThat(recalled).hasSize(1);
        assertThat(recalled.getFirst().source()).isEqualTo(MemoryStore.MemoryItem.Source.STRUCTURED);
        assertThat(recalled.getFirst().text()).isEqualTo("第 42 条：还不懂");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT kind FROM ai_memories_structured WHERE user_id = ?", String.class, userA))
                .isEqualTo("entry_understanding");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ref_id FROM ai_memories_structured WHERE user_id = ?", Long.class, userA)).isEqualTo(42L);
    }

    @Test
    @DisplayName("全文记忆：多字关键词走全文索引能召回")
    void textMemoryIsRecalledByKeyword() {
        memories.rememberText(userA, "我觉得并发那块一直没搞明白");
        memories.rememberText(userA, "今天想先看排序");

        List<String> hits = memories.recall(userA, "并发").stream().map(MemoryStore.MemoryItem::text).toList();

        assertThat(hits).containsExactly("我觉得并发那块一直没搞明白");
    }

    @Test
    @DisplayName("单字关键词退回 LIKE 也能召回——ngram 索引对单字永远召回为零")
    void singleCharacterKeywordFallsBackToLike() {
        memories.rememberText(userA, "锁这个概念我老是记混");

        // "锁" 只有一个字：走全文索引必然是 0 条（这个坑知识库那边已经踩过一次）
        List<String> hits = memories.recall(userA, "锁").stream().map(MemoryStore.MemoryItem::text).toList();

        assertThat(hits).containsExactly("锁这个概念我老是记混");
    }

    @Test
    @DisplayName("严格私有：召回到的只有本人的记忆，删除也不能删别人的")
    void memoryIsStrictlyPrivate() {
        long mine = memories.rememberText(userA, "这是甲说过的话");
        long theirs = memories.rememberText(userB, "这是乙说过的话");

        assertThat(memories.recall(userB, null).stream().map(MemoryStore.MemoryItem::text))
                .containsExactly("这是乙说过的话");

        // 拿别人的 id 去删：删不掉，也不能因此知道它存在（返回 false 而不是抛错）
        assertThat(memories.delete(userB, MemoryStore.MemoryItem.Source.TEXT, mine)).isFalse();
        assertThat(memories.recall(userA, null)).hasSize(1);

        assertThat(memories.delete(userA, MemoryStore.MemoryItem.Source.TEXT, mine)).isTrue();
        assertThat(memories.recall(userA, null)).isEmpty();
        assertThat(memories.delete(userB, MemoryStore.MemoryItem.Source.TEXT, theirs)).isTrue();
    }

    @Test
    @DisplayName("两路合并召回时，结构化在前（事实比原话更稳）")
    void structuredComesBeforeText() {
        memories.rememberText(userA, "我随口说过排序很难");
        memories.rememberStructured(userA, "entry_understanding", 7L, "第 7 条：已掌握");

        assertThat(memories.recall(userA, null).stream().map(MemoryStore.MemoryItem::source))
                .containsExactly(
                        MemoryStore.MemoryItem.Source.STRUCTURED,
                        MemoryStore.MemoryItem.Source.TEXT);
    }

    @Test
    @DisplayName("空白与超长都被拒，且不静默截断")
    void rejectsBlankAndOverlongContent() {
        assertThatThrownBy(() -> memories.rememberText(userA, "   ")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> memories.rememberStructured(userA, "entry_understanding", 1L, ""))
                .isInstanceOf(BizException.class);
        // 结构化列宽 512：超长要报错而不是被悄悄砍掉（记忆会被后续对话反复读取）
        assertThatThrownBy(() -> memories.rememberStructured(userA, "entry_understanding", 1L, "x".repeat(513)))
                .isInstanceOf(BizException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_memories_text", Long.class)).isZero();
    }

    @Test
    @DisplayName("记忆端点三态：匿名 401、他人看不到、本人可列出并逐条删除")
    void memoryEndpointsEnforceOwnership() throws Exception {
        long mine = memories.rememberText(userA, "甲说过的一句话");
        memories.rememberStructured(userA, "entry_understanding", 3L, "第 3 条：还不懂");

        // 匿名：两个动作都拒绝
        assertThat(mockMvc.perform(get("/api/v1/ai/memories")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(delete("/api/v1/ai/memories/text/" + mine)).andReturn().getResponse().getStatus())
                .isEqualTo(401);

        // 他人：列表里没有甲的记忆（严格私有，用户裁决 3）
        MvcResult others = mockMvc.perform(get("/api/v1/ai/memories").header("Authorization", token(userB)))
                .andReturn();
        assertThat(body(others)).doesNotContain("甲说过的一句话");

        // 本人：能看到两条，且 source 回给了前端（删的时候要说明删哪一路）
        MvcResult listed = mockMvc.perform(get("/api/v1/ai/memories").header("Authorization", token(userA)))
                .andReturn();
        assertThat(body(listed)).contains("甲说过的一句话").contains("structured").contains("text");

        // 他人拿甲的 id 删：删不掉，且返回 false（不区分"不存在"与"不是你的"）
        MvcResult theirs = mockMvc.perform(delete("/api/v1/ai/memories/text/" + mine)
                        .header("Authorization", token(userB)))
                .andReturn();
        assertThat(body(theirs)).contains("false");
        assertThat(memories.list(userA, 0, 0)).hasSize(2);

        // 本人删：删掉后列表只剩结构化那条
        MvcResult deleted = mockMvc.perform(delete("/api/v1/ai/memories/text/" + mine)
                        .header("Authorization", token(userA)))
                .andReturn();
        assertThat(body(deleted)).contains("true");
        assertThat(memories.list(userA, 0, 0)).hasSize(1);
        assertThat(memories.list(userA, 0, 0).getFirst().source()).isEqualTo(MemoryStore.MemoryItem.Source.STRUCTURED);
    }

    @Test
    @DisplayName("非法的来源段被拒，而不是猜着删某一路")
    void rejectsUnknownSource() throws Exception {
        assertThat(mockMvc.perform(delete("/api/v1/ai/memories/whatever/1")
                        .header("Authorization", token(userA)))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("记忆写入口：本人可写两路，匿名 401，非法类型／空内容／缺 kind 都被拒")
    void memoryWriteEndpointIsOwnerScoped() throws Exception {
        assertThat(mockMvc.perform(post("/api/v1/ai/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"text\",\"text\":\"x\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        assertThat(writeStatus(userA, "{\"type\":\"whatever\",\"text\":\"x\"}")).isEqualTo(400);
        assertThat(writeStatus(userA, "{\"type\":\"text\",\"text\":\"   \"}")).isEqualTo(400);
        // 结构化那路必须说明 kind：没有种类的"事实"没法用
        assertThat(writeStatus(userA, "{\"type\":\"structured\",\"text\":\"x\"}")).isEqualTo(400);

        assertThat(writeStatus(userA, "{\"type\":\"text\",\"text\":\"甲说过的原话\"}")).isEqualTo(200);
        assertThat(writeStatus(userA, "{\"type\":\"structured\",\"text\":\"第 5 条：还不懂\","
                + "\"kind\":\"entry_understanding\",\"refId\":5}")).isEqualTo(200);

        assertThat(memories.list(userA, 0, 0)).hasSize(2);
        // 归属取的是令牌，请求体里没有 userId 这个字段——传不了别人的
        assertThat(memories.list(userB, 0, 0)).isEmpty();
    }

    private int writeStatus(long userId, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/ai/memories")
                        .header("Authorization", token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getStatus();
    }

    private String token(long userId) {
        return "Bearer " + authPort.issue(new AuthPort.Subject(Long.toString(userId), AuthPort.ROLE_LEARNER)).value();
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
