package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.ai.AssistantConversation;
import io.github.xiaou61.ai.Harness;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.conversation.HarnessPrompts;
import io.github.xiaou61.ai.internal.memory.MemoryStore;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.AuthPort;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 助手对话的端到端集成测试（AC-001、AC-002、AC-003，并覆盖 TASK-001 的 SQL 层）。
 *
 * <p>用完整上下文 + 真实 MySQL + MockMvc，**把 {@link AssistantConversation} 端口换成桩**：
 * 绝不真连上游。桩同时负责"制造"思考／正文／空正文／失败四种上游行为——那些在真实上游里
 * 很难按需重现，而它们恰好是界面分支的来源。
 *
 * <p>本类取代了"在 paideia-ai 里另起一套 DB 装配"的做法：这里跑的是真实的 {@code V5} 迁移
 * 与真实 mapper，顺序也对（paideia-app 在反应堆最后）。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}；未配置时整类跳过并计入 Skipped。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AssistantConversationIntegrationTest {

    /** 公网地址：凭据保存前要过 SSRF 校验，内网会被拒。 */
    private static final String PUBLIC_BASE_URL = "https://1.1.1.1/v1";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MemoryStore memories;

    @MockitoBean
    AssistantConversation assistant;

    /**
     * 让 SSE 的产出在**请求线程里同步跑完**，而不是丢到线程池。
     *
     * <p>为什么必须这么做（2026-10-17，改用本地库之后从偶发变成必然）：`MockHttpServletResponse`
     * 的头是 `LinkedCaseInsensitiveMap`，**不是线程安全的**。控制器在 `send()` 里先
     * `executor.execute(runTurn)` 再返回，于是池线程写 SSE 头的同时，请求线程还在
     * 过滤器链的 `doHeadersAfter` 里写安全头——两边抢同一个 map，抛
     * `ConcurrentModificationException`（早先还表现为 `getStringValues` 的 NPE）。
     * 库越慢窗口越小，库一快就次次复现。
     *
     * <p>本类的 12 条用例验的是业务行为（归属、上限、限流、空正文、用量），**没有一条**在验
     * 并发流；真正的并发由 `MemoryAutoExtractionIntegrationTest` 的抢游标用例覆盖。
     * 所以这里用同步执行换确定性，是测试基建的取舍，不是放松断言。
     */
    @MockitoBean(name = "applicationTaskExecutor")
    TaskExecutor executor;

    @BeforeEach
    void runTurnInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any());
    }

    /**
     * 平台凭据（WORK-009 起由平台持有，不再是每个用户一份）。
     *
     * <p>正常用例靠 {@code application-test.yml} 里配好的那一份；只有"平台尚未配置"那条分支需要
     * 临时把它清空——所以这里直接注入可变的配置 Bean，而不是再起一个测试上下文
     * （{@code application-test.yml} 里写明了：多一个配置键就多一个上下文，实测 15–25 秒）。
     */
    @Autowired
    io.github.xiaou61.ai.internal.billing.BillingProperties billing;

    /** WORK-009 起 AI 调用要过积分闸，所以用例得先把"这一条要花的钱"放进账户。 */
    @Autowired
    io.github.xiaou61.ai.internal.billing.CreditService creditService;

    private final List<Long> createdConversations = new ArrayList<>();
    private String userA;
    private String userB;

    /**
     * 每类用例一组新用户 id：限流与凭据都按用户算，共用固定 id 会让用例互相影响。
     *
     * <p>用任意数字身份即可——本模块的表**不建跨 users 外键**（见 design 的纠正），
     * 这也是本项目集成测试的既有做法。
     */
    @BeforeEach
    void setUp() {
        createdConversations.clear();
        // 这两张表只有本模块在写，自己清干净就能做到用例间互不影响。
        // **注意要先删消息**：两张表之间没有外键（同样是有意为之），不会自动级联。
        jdbcTemplate.update("DELETE FROM ai_messages");
        jdbcTemplate.update("DELETE FROM ai_conversations");
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");

        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        userA = "71" + suffix;
        userB = "72" + suffix;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_messages");
        jdbcTemplate.update("DELETE FROM ai_conversations");
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
    }

    @Test
    @DisplayName("未登录：建会话、列会话、发消息三条路径一律 401")
    void requiresAuthentication() throws Exception {
        assertThat(mockMvc.perform(post("/api/v1/ai/conversations")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/ai/conversations")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(post("/api/v1/ai/conversations/1/messages")
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("建会话并能在自己的列表里看到；他人的列表看不到")
    void createsAndListsOwnConversations() throws Exception {
        long conversationId = createConversation(userA, null);

        MvcResult mine = mockMvc.perform(get("/api/v1/ai/conversations").header("Authorization", token(userA)))
                .andReturn();
        // 断言 data 数组而不是整段响应体：响应的 traceId 是随机十六进制串，
        // 拿"整段不含某个数字"当判据会随机翻车（第一版就是这么挂的）
        assertThat(JsonPath.<List<Object>>read(body(mine), "$.data")).hasSize(1);

        MvcResult others = mockMvc.perform(get("/api/v1/ai/conversations").header("Authorization", token(userB)))
                .andReturn();
        assertThat(JsonPath.<List<Object>>read(body(others), "$.data")).isEmpty();
    }

    @Test
    @DisplayName("一轮对话：思考与正文分成两类事件，结束事件带用量，助手消息与用量都落库")
    void streamsThinkingAndContentSeparately() throws Exception {
        configureCredential(userA);
        long conversationId = createConversation(userA, null);
        stubStream("先想一想这个概念", "这是正文答案", new TokenUsage(11, 22, 33));

        MvcResult result = sendMessage(userA, conversationId, "解释一下");
        String stream = body(result);

        assertThat(stream).contains("event:thinking").contains("event:content").contains("event:done");
        // 两类事件在协议层面就是分开的：思考的文本不该出现在正文事件里（AC-002）
        assertThat(stream).contains("先想一想这个概念").contains("这是正文答案");
        assertThat(stream).contains("\"total\":33");

        // 用户消息先写、助手消息完成后写，两条都在；
        // 思考**不落库**：库里只应有这两条文本，不含思考过程
        List<String> stored = jdbcTemplate.queryForList(
                "SELECT content FROM ai_messages WHERE conversation_id = ? ORDER BY id", String.class, conversationId);
        assertThat(stored).containsExactly("解释一下", "这是正文答案");
        assertThat(String.join("|", stored)).doesNotContain("先想一想这个概念");

        String usageJson = jdbcTemplate.queryForObject(
                "SELECT token_usage FROM ai_messages WHERE conversation_id = ? AND role = 'assistant'",
                String.class, conversationId);
        // 按 JSON 取值而不是比字符串：MySQL 的 JSON 列会**规范化**存储
        // （键重排、冒号后加空格），紧凑串读回来就变了形。
        assertThat(JsonPath.<Integer>read(usageJson, "$.total")).isEqualTo(33);
    }

    @Test
    @DisplayName("这一轮把提示词版本写进了 ai_runs：改提示词前后才分得出来")
    void recordsPromptVersion() throws Exception {
        // 版本号只有落库才有用。缺了它，改了 tutor/面试的提示词之后，
        // 前后两批行长得一模一样，再也说不出"涨的那两个点来自哪一版"——而且是补不回来的。
        configureCredential(userA);
        long conversationId = createConversation(userA, null);
        stubStream(null, "答案", new TokenUsage(1, 1, 2));
        sendMessage(userA, conversationId, "问题");

        // 写队列是异步的：轮次行可能在 dispatch 返回之后才落库，等到出现为止
        String version = awaitPromptVersion(conversationId);
        assertThat(version).isEqualTo(HarnessPrompts.version(Harness.ASSISTANT));
        assertThat(version).isNotBlank();
    }

    /** 等这一轮的 prompt_version 落库（写线程异步）。 */
    private String awaitPromptVersion(long conversationId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT prompt_version FROM ai_runs WHERE conversation_id = ? AND kind = 'chat'",
                    String.class, conversationId);
            if (!rows.isEmpty() && rows.get(0) != null) {
                return rows.get(0);
            }
            Thread.sleep(50);
        }
        return null;
    }

    @Test
    @DisplayName("线程池队列满：429 而不是 500，且这一轮的预扣全额退回")
    void rejectedExecutionIsRefundedAndReportedAsBusy() throws Exception {
        // 2026-10-19 起执行器队列有界：并发超限时 execute 直接抛 RejectedExecutionException。
        // 两条断言各守一件事——状态码要是"等会儿再来"（500 会让人以为服务崩了），
        // 钱要退回去（预扣发生在派发之前，任务没跑起来就没人能结算）。
        configureCredential(userA);
        long userId = Long.parseLong(userA);
        long balanceBefore = creditService.balance(userId);
        long conversationId = createConversation(userA, null);
        doThrow(new RejectedExecutionException("queue full"))
                .when(executor).execute(any());

        MvcResult rejected = mockMvc.perform(post("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(userA))
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"现在很忙吗\"}"))
                .andReturn();

        assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(rejected)).contains("请稍后再试");
        assertThat(creditService.balance(userId)).isEqualTo(balanceBefore);
    }

    @Test
    @DisplayName("他人的会话对自己不可见，且与“不存在”返回同一种结果")
    void othersConversationIsInvisible() throws Exception {
        configureCredential(userB);
        long conversationId = createConversation(userA, null);

        MvcResult result = mockMvc.perform(post("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(userB))
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"偷看\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(result)).contains("会话不存在或已过期");
        // 越权请求不该在别人的会话里留下任何消息
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ?", Long.class, conversationId)).isZero();
    }

    @Test
    @DisplayName("平台未配置 AI 服务：400 且说清原因，不给 500")
    void missingCredentialIsVisible() throws Exception {
        long conversationId = createConversation(userA, null);
        // 临时清空平台凭据来验"没配"这条分支。用 finally 复原：上下文在测试类之间是缓存的，
        // 留一个空凭据会让后面所有类都莫名其妙地失败。
        var credential = billing.getPlatformCredential();
        String savedKey = credential.getApiKey();
        credential.setApiKey(null);
        try {
            MvcResult result = mockMvc.perform(post("/api/v1/ai/conversations/" + conversationId + "/messages")
                            .header("Authorization", token(userA))
                            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"你好\"}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(result)).contains("平台尚未配置 AI 服务");
        } finally {
            credential.setApiKey(savedKey);
        }
    }

    @Test
    @DisplayName("只有思考、正文为空：结束事件标 empty，落库的是可见提示而不是空串")
    void emptyContentGetsVisibleHint() throws Exception {
        configureCredential(userA);
        long conversationId = createConversation(userA, null);
        stubStream("想了很久但没写出正文", "", new TokenUsage(50, 40, 90));

        MvcResult result = sendMessage(userA, conversationId, "难题");
        assertThat(body(result)).contains("\"empty\":true");

        String stored = jdbcTemplate.queryForObject(
                "SELECT content FROM ai_messages WHERE conversation_id = ? AND role = 'assistant'",
                String.class, conversationId);
        assertThat(stored).isNotBlank().contains("只产生了思考过程");
    }

    @Test
    @DisplayName("已过期的会话视为不存在：404，且不再往里面写消息")
    void expiredConversationIsGone() throws Exception {
        configureCredential(userA);
        long conversationId = createConversation(userA, null);
        jdbcTemplate.update("UPDATE ai_conversations SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), conversationId);

        MvcResult result = mockMvc.perform(post("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(userA))
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"还在吗\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ?", Long.class, conversationId)).isZero();
    }

    @Test
    @DisplayName("上游失败：error 事件给出可见消息，且失败轮次不落库")
    void upstreamFailureIsVisibleAndNotPersisted() throws Exception {
        configureCredential(userA);
        long conversationId = createConversation(userA, null);
        doAnswer(invocation -> {
            AssistantConversation.Listener listener = invocation.getArgument(3);
            listener.onFailed(new BizException(ErrorCode.INTERNAL, "上游模型服务暂时不可用，请稍后再试"));
            return null;
        }).when(assistant).stream(any(), any(), any(), any());

        MvcResult result = sendMessage(userA, conversationId, "会失败的问题");
        assertThat(body(result)).contains("event:error").contains("上游模型服务暂时不可用");

        // 用户那条要留下（他确实说过），助手那条不该有
        List<String> roles = jdbcTemplate.queryForList(
                "SELECT role FROM ai_messages WHERE conversation_id = ? ORDER BY id", String.class, conversationId);
        assertThat(roles).containsExactly("user");
    }

    @Test
    @DisplayName("存储上限：超出后删掉最旧的，总量回到上限")
    void storageCapDropsOldestMessages() throws Exception {
        configureCredential(userA);
        long conversationId = createConversation(userA, null);
        // 上限 200：先垫 199 条，再走一轮（用户 1 条 + 助手 1 条）→ 201 条 → 应删掉最旧的 1 条
        List<Object[]> filler = new ArrayList<>();
        for (int index = 0; index < 199; index++) {
            filler.add(new Object[]{conversationId, "user", "垫底 " + index});
        }
        jdbcTemplate.batchUpdate("INSERT INTO ai_messages (conversation_id, role, content) VALUES (?, ?, ?)", filler);
        stubStream("", "回答", null);

        sendMessage(userA, conversationId, "最后一条");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ?", Long.class, conversationId))
                .isEqualTo(200);
        // 删的必须是最旧的："垫底 0" 走了，"垫底 1" 还在
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ? AND content = '垫底 0'",
                Long.class, conversationId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ? AND content = '垫底 1'",
                Long.class, conversationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("召回的记忆会随这一轮进到模型上下文，并在提示词里标出来源")
    void recalledMemoryReachesTheModel() throws Exception {
        configureCredential(userA);
        long conversationId = createConversation(userA, null);
        // 写入端点属 TASK-009（助手写本人私有数据那条确认路径），这里直接落一条
        memories.rememberStructured(Long.parseLong(userA), "entry_understanding", null, "第 9 条：还不懂");
        memories.rememberText(Long.parseLong(userA), "他说过想先学并发");

        List<AssistantConversation.Request> seen = new ArrayList<>();
        doAnswer(invocation -> {
            seen.add(invocation.getArgument(0));
            AssistantConversation.Listener listener = invocation.getArgument(3);
            listener.onContent("好的");
            listener.onUsage(null);
            listener.onCompleted();
            return null;
        }).when(assistant).stream(any(), any(), any(), any());

        // 消息里带上关键词：全文那一路是**按当前这轮的话**检索的（第 2 条裁决取舍——
        // 用全文检索而不是语义检索，代价就是字面对不上就召不回）
        sendMessage(userA, conversationId, "并发我该先看什么");

        assertThat(seen).hasSize(1);
        // 两路都在，且各自带了来源标注——不标的话模型会把"随口一句"当成事实
        assertThat(seen.getFirst().memorySnippets()).containsExactly(
                "[学习者事实] 第 9 条：还不懂",
                "[他说过] 他说过想先学并发");
    }

    @Test
    @DisplayName("一轮的用量返还给用户；触发限流时给 429 而不是把上游的错误抛出来")
    void usageIsReturnedAndRateLimitIsEnforced() throws Exception {
        configureCredential(userA);
        long conversationId = createConversation(userA, null);
        stubStream("", "回答", new TokenUsage(5, 7, 12));

        // 用量随 done 事件回去（BYOK 下用户自己付费，看得见才透明）
        String stream = body(sendMessage(userA, conversationId, "第一轮"));
        assertThat(stream).contains("\"total\":12");

        // 默认每分钟 10 轮：把这一分钟用光，第 11 轮应该被限流
        for (int round = 1; round < 10; round++) {
            sendMessage(userA, conversationId, "第 " + round + " 轮");
        }
        MvcResult limited = mockMvc.perform(post("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(userA))
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"超限的一轮\"}"))
                .andReturn();

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(limited)).contains("发送消息");
        // 被限流的那一轮**什么都没写**：用户那句话不该落库（它没有产生任何调用）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ? AND content = '超限的一轮'",
                Long.class, conversationId)).isZero();
    }

    @Test
    @DisplayName("WORK-011：读会话消息只给本人；他人/不存在/未登录一律同一条错误")
    void readMessagesIsOwnershipChecked() throws Exception {
        configureCredential(userA);
        long conversationId = createConversation(userA, null);
        stubStream("", "回答", new TokenUsage(5, 7, 12));
        sendMessage(userA, conversationId, "第一句");

        // 本人：读回两条（user + assistant），**旧 → 新**，并且带上那次的用量
        MvcResult mine = mockMvc.perform(get("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(userA)))
                .andReturn();
        assertThat(mine.getResponse().getStatus()).as("响应：%s", body(mine)).isEqualTo(200);
        String text = body(mine);
        assertThat(text).contains("第一句").contains("回答").contains("\"totalTokens\":12");
        assertThat(text.indexOf("第一句")).as("必须旧 → 新").isLessThan(text.indexOf("回答"));

        // 他人：404——与"不存在"是**同一条**错误，区分开就等于确认"某个会话存在"
        assertThat(mockMvc.perform(get("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(userB)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/v1/ai/conversations/999999999/messages")
                        .header("Authorization", token(userA)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/v1/ai/conversations/" + conversationId + "/messages"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    /** 桩：按顺序回调思考、正文、结束。失败路径由各用例自己 doAnswer。 */    private void stubStream(String thinking, String content, TokenUsage usage) {
        doAnswer(invocation -> {
            AssistantConversation.Listener listener = invocation.getArgument(3);
            if (!thinking.isEmpty()) {
                listener.onThinking(thinking);
            }
            if (!content.isEmpty()) {
                listener.onContent(content);
            }
            listener.onUsage(usage);
            listener.onCompleted();
            return null;
        }).when(assistant).stream(any(), any(), any(), any());
    }

    private long createConversation(String subject, Long entryId) throws Exception {
        String payload = entryId == null ? "{}" : "{\"entryId\":" + entryId + "}";
        MvcResult created = mockMvc.perform(post("/api/v1/ai/conversations")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as("响应：%s", body(created)).isEqualTo(200);
        long id = ((Number) JsonPath.read(body(created), "$.data.id")).longValue();
        createdConversations.add(id);
        return id;
    }

    /**
     * 把这一条用例要花的钱放进账户。
     *
     * <p>名字保留（原意是"给这个用户配一份凭据"）：WORK-009 起凭据由平台统一持有，用户侧要准备的
     * 变成了**余额**。调用点因此仍然有意义——它让每条用例显式声明"我需要 AI 可用"。
     *
     * <p>**不给就会 402**（预留失败），那正是积分闸生效的样子。
     */
    private void configureCredential(String subject) {
        creditService.adjust(Long.parseLong(subject), 1_000_000L, 1L, "集成测试发放");
    }

    /** 发一轮消息并把 SSE 流读完。异步派发会一直等到 emitter 结束（端口是桩，不会真的等网络）。 */
    private MvcResult sendMessage(String subject, long conversationId, String message) throws Exception {
        return MockMvcSse.exchange(mockMvc, post("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(subject))
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"));
    }

    private String token(String subject) {
        return TestTokens.bearer(authPort, subject);
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
