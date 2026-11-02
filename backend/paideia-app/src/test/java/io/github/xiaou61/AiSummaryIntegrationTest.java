package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.ai.EntrySummarizer;
import io.github.xiaou61.ai.GeneratedSummary;
import io.github.xiaou61.ai.SummaryCard;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.AuthPort;
import java.util.ArrayList;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * AI 精简的端到端集成测试（AC-002、AC-003、AC-004、AC-005、AC-007、AC-009、AC-010）。
 *
 * <p>用完整上下文 + 真实 MySQL + MockMvc，但**把端口换成桩**：测试绝不能真连上游
 * （没有网络、也没有谁的 Key 可以花）。桩同时承担"调用计数"的职责——"命中缓存不再调模型"
 * 这条只能靠它证明。
 *
 * <p>用户身份用 {@code AuthPort} 直接签令牌造出来：本模块的表对 users 没有外键，
 * 两个不同的数字主体就足以表达"两个用户"。管理员令牌用真管理员角色，只为建一条测试内容。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}；未配置时整个类被跳过并计入 Skipped。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AiSummaryIntegrationTest {

    private static final String PUBLIC_BASE_URL = "https://1.1.1.1/v1";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** 端口换成桩：既避免真连上游，也用来数调用次数。 */
    @MockitoBean
    EntrySummarizer summarizer;

    /** WORK-009 起 AI 调用要过积分闸，所以用例得先把"这一条要花的钱"放进账户。 */
    @Autowired
    io.github.xiaou61.ai.internal.billing.CreditService creditService;

    private long entryId;

    /**
     * 本用例建出来的条目 id。**必须在收尾时删掉**：测试库是整个 XML 测试集共享的，
     * 留下的已发布条目会让知识库那边"列表只含种子内容"的断言失败——那是测试污染，
     * 不是产品缺陷（第一版就是这么把 KnowledgeReadIntegrationTest 弄红的）。
     */
    private final List<Long> createdEntries = new ArrayList<>();

    /**
     * 每个用例一组全新的用户 id：限流计数在进程内、**按用户累计**，若共用固定 id，
     * 同一批用例跑下来会互相把额度用掉，后面的用例就变成 429——那是测试自身的耦合，不是产品行为。
     */
    private String userA;
    private String userB;

    @BeforeEach
    void setUp() throws Exception {
        createdEntries.clear();
        clearInvocations(summarizer);
        // 这张表只有本模块在写，测试自己清干净就能做到用例间互不影响。
        jdbcTemplate.update("DELETE FROM ai_entry_summaries");
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        userA = "91" + suffix;
        userB = "92" + suffix;
        when(summarizer.summarize(anyString(), anyString(), any())).thenReturn(card("首次生成"));

        // 每个用例用自己新建的内容：缓存键含正文哈希，用共享种子会互相污染
        entryId = createPublishedEntry("AI 集成测试条目 " + System.nanoTime());
    }

    @Test
    @DisplayName("未登录一律 401；已登录时服务状态视图可见，且响应里没有 Key 的任何片段")
    void requiresAuthentication() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/ai/credentials")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/ai/summaries/" + entryId)).andReturn().getResponse().getStatus())
                .isEqualTo(401);

        MvcResult result = mockMvc.perform(get("/api/v1/ai/credentials").header("Authorization", token(userA)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // WORK-009 起凭据由平台持有，测试配置里是配好的（"没配"那条分支由
        // AssistantConversationIntegrationTest#missingCredentialIsVisible 临时清空来验）
        assertThat(JsonPath.<Boolean>read(body(result), "$.data.configured")).isTrue();
        // **连末四位都不给**：那是平台的 Key，不是用户的
        assertThat(body(result)).doesNotContain("apiKey");
        assertThat(body(result)).doesNotContain("test-only-platform-key-never-used");
    }

    // 「凭据加密落库 / 只回 last4」与「保存凭据时就被 SSRF 拒」两条用例已随 WORK-009 删除：
    // **用户自带的 Key 这条路已撤销**（上游凭据由平台在部署配置里持有），于是加密、last4、
    // "保存时会打上游"这三件事都不再存在——留着它们只会去断言一个已经下线的接口。
    // SSRF 规则本身仍有覆盖：`UpstreamGuardTest`（规则单测）+ `CredentialService` 构造期的启动校验
    // + `GuardedHttpTransport`（每次出站看最终地址）。

    @Test
    @DisplayName("生成返回结构化卡片与用量；重复请求命中缓存且不再调模型")
    void generatesThenServesFromCache() throws Exception {
        saveCredential(userA);

        MvcResult first = generate(userA, "public");
        assertThat(first.getResponse().getStatus()).as("generate 响应：%s", body(first)).isEqualTo(200);
        assertThat(JsonPath.<String>read(body(first), "$.data.card.headline")).isEqualTo("首次生成");
        assertThat(JsonPath.<Boolean>read(body(first), "$.data.cached")).isFalse();
        assertThat(JsonPath.<String>read(body(first), "$.data.source")).isEqualTo("mine");
        assertThat(JsonPath.<Integer>read(body(first), "$.data.usage.totalTokens")).isEqualTo(42);
        verify(summarizer, times(1)).summarize(anyString(), anyString(), any());

        MvcResult second = generate(userA, "public");
        assertThat(JsonPath.<Boolean>read(body(second), "$.data.cached")).isTrue();
        assertThat(JsonPath.<Object>read(body(second), "$.data.usage")).isNull();
        // 关键断言：命中缓存**不再调模型**
        verify(summarizer, times(1)).summarize(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("私有结果别人读不到；改成公开后别人能读到且同样不触发调用")
    void visibilityIsEnforced() throws Exception {
        saveCredential(userA);
        generate(userA, "private");

        MvcResult other = mockMvc.perform(get("/api/v1/ai/summaries/" + entryId)
                        .header("Authorization", token(userB)))
                .andReturn();
        assertThat(JsonPath.<Object>read(body(other), "$.data")).isNull();

        MvcResult owner = mockMvc.perform(get("/api/v1/ai/summaries/" + entryId)
                        .header("Authorization", token(userA)))
                .andReturn();
        assertThat(JsonPath.<String>read(body(owner), "$.data.card.headline")).isEqualTo("首次生成");
        assertThat(JsonPath.<String>read(body(owner), "$.data.source")).isEqualTo("mine");

        // 另一个用户自己生成一份公开的：他自己是 owner
        saveCredential(userB);
        when(summarizer.summarize(anyString(), anyString(), any())).thenReturn(card("B 生成的"));
        generate(userB, "public");

        MvcResult nowVisible = mockMvc.perform(get("/api/v1/ai/summaries/" + entryId)
                        .header("Authorization", token(userA)))
                .andReturn();
        assertThat(JsonPath.<String>read(body(nowVisible), "$.data.source")).isEqualTo("others");
    }

    @Test
    @DisplayName("别人生成的公开结果，本人也能读到（source 标 others）")
    void publicSummaryIsReadableByOthers() throws Exception {
        saveCredential(userA);
        generate(userA, "public");

        MvcResult read = mockMvc.perform(get("/api/v1/ai/summaries/" + entryId)
                        .header("Authorization", token(userB)))
                .andReturn();
        assertThat(JsonPath.<String>read(body(read), "$.data.card.headline")).isEqualTo("首次生成");
        assertThat(JsonPath.<String>read(body(read), "$.data.source")).isEqualTo("others");
        // 原用例还有一半是"没凭据的用户点生成会被 409 拒绝"——WORK-009 之后**每个用户都能生成**
        // （凭据是平台的），那一半已不成立，故随本项删除；跨用户可见性这一半保留在这里。
    }

    @Test
    @DisplayName("正文改动后旧结果不再命中")
    void contentChangeInvalidatesCache() throws Exception {
        saveCredential(userA);
        generate(userA, "public");

        mockMvc.perform(put("/api/v1/knowledge/admin/entries/" + entryId)
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"改过的标题\",\"body\":\"改过的正文\",\"categoryId\":null,"
                                + "\"status\":\"published\"}"))
                .andReturn();

        MvcResult afterChange = mockMvc.perform(get("/api/v1/ai/summaries/" + entryId)
                        .header("Authorization", token(userA)))
                .andReturn();
        assertThat(JsonPath.<Object>read(body(afterChange), "$.data")).isNull();
    }

    @Test
    @DisplayName("上游失败不写缓存：下一次请求还会再试")
    void failureIsNotCached() throws Exception {
        saveCredential(userA);
        when(summarizer.summarize(anyString(), anyString(), any())).thenThrow(
                new BizException(ErrorCode.INTERNAL, "模型没有返回可用的精简结果（模型没有返回任何内容）"));

        MvcResult failed = generate(userA, "public");
        assertThat(failed.getResponse().getStatus()).isEqualTo(500);
        // 错误信息里不得出现上游原始报文
        assertThat(body(failed)).doesNotContain("评测");

        MvcResult readAfterFailure = mockMvc.perform(get("/api/v1/ai/summaries/" + entryId)
                        .header("Authorization", token(userA)))
                .andReturn();
        assertThat(JsonPath.<Object>read(body(readAfterFailure), "$.data")).isNull();
    }

    @Test
    @DisplayName("未发布条目不能生成")
    void draftEntryIsNotFound() throws Exception {
        saveCredential(userA);
        long draftId = createEntry("草稿 " + System.nanoTime(), "内容", "draft");

        MvcResult result = generate(userA, draftId, "public");
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    /**
     * 把这一条用例要花的钱放进账户。
     *
     * <p>名字保留（原意是"给这个用户存一份凭据"）：WORK-009 起凭据由平台统一持有，用户侧要准备的
     * 变成了**余额**。**不给就会 402**——那正是积分闸生效的样子。
     */
    private void saveCredential(String user) {
        creditService.adjust(Long.parseLong(user), 1_000_000L, 1L, "集成测试发放");
    }

    private MvcResult generate(String user, String visibility) throws Exception {
        return generate(user, entryId, visibility);
    }

    private MvcResult generate(String user, long id, String visibility) throws Exception {
        return mockMvc.perform(post("/api/v1/ai/summaries")
                        .header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryId\":" + id + ",\"visibility\":\"" + visibility + "\"}"))
                .andReturn();
    }

    private long createPublishedEntry(String title) throws Exception {
        return createEntry(title, "正文内容，用于 AI 精简测试。", "published");
    }

    private long createEntry(String title, String content, String status) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/knowledge/admin/entries")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"body\":\"" + content + "\","
                                + "\"categoryId\":null,\"status\":\"" + status + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        long id = ((Number) JsonPath.read(body(created), "$.data")).longValue();
        createdEntries.add(id);
        return id;
    }

    @AfterEach
    void tearDown() throws Exception {
        for (long id : createdEntries) {
            mockMvc.perform(delete("/api/v1/knowledge/admin/entries/" + id)
                    .header("Authorization", adminToken()));
        }
        createdEntries.clear();
    }

    private String token(String subject) {
        return TestTokens.bearer(authPort, subject);
    }

    private String adminToken() {
        return TestTokens.bearer(authPort, "1", AuthPort.ROLE_ADMIN);
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }

    private static GeneratedSummary card(String headline) {
        return new GeneratedSummary(
                new SummaryCard(headline, List.of(new SummaryCard.SummarySection("小节", "小节正文"))),
                new TokenUsage(30, 12, 42),
                false);
    }
}
