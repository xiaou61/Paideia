package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import io.github.xiaou61.ai.AssistantConversation;
import io.github.xiaou61.ai.MemoryCandidate;
import io.github.xiaou61.ai.MemoryExtractor;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.conversation.ConversationService;
import io.github.xiaou61.ai.internal.memory.MemoryExtractionService;
import io.github.xiaou61.ai.internal.memory.MemoryStore;
import io.github.xiaou61.ai.internal.settings.AiSettingsStore;
import io.github.xiaou61.security.AuthPort;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 记忆的自动提取与控制面（WORK-013）。
 *
 * <p>完整上下文 + 真实 MySQL + MockMvc，**把 {@link MemoryExtractor} 这个端口换成桩**：
 * 用例要验的是"我们这边"的行为——什么时候触发、写进去长什么样、失败与欠费怎么收场；
 * 而"模型提炼得好不好"不是这里能确定的（那是提示词与实测的事）。真上游永不参与。
 *
 * <p>每个"一轮"都用 {@code complete()} 直接落一条助手消息：攒轮次数的是**助手消息**，
 * 而 {@code prepare()} 那条路还要凭据与限流，与主题无关。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class MemoryAutoExtractionIntegrationTest {

    /** 与应用默认一致（{@code paideia.ai.memory.rounds-per-extraction}）：攒够 6 轮才整理。 */
    private static final int ROUNDS = 6;

    @Autowired
    MemoryStore memories;

    @Autowired
    AiSettingsStore settings;

    @Autowired
    MemoryExtractionService extraction;

    @Autowired
    ConversationService conversations;

    @Autowired
    CreditService credits;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @MockitoBean
    MemoryExtractor extractor;

    @MockitoBean
    AssistantConversation assistant;

    private long user;

    /** 桩收到的请求：用来断言"给了模型什么上下文"（AC-006）。 */
    private final List<AssistantConversation.Request> assistantsSeen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clearAll();
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        user = Long.parseLong("83" + suffix);
        // 整理要花钱：余额给足，否则它会被"余额不足即跳过"那条路挡掉（那条路由单独的用例验）
        credits.adjust(user, 100_000, 0L, "test");
    }

    @AfterEach
    void cleanUp() {
        clearAll();
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM ai_memories_structured");
        jdbcTemplate.update("DELETE FROM ai_memories_text");
        jdbcTemplate.update("DELETE FROM ai_user_settings");
        jdbcTemplate.update("DELETE FROM ai_memory_candidates");
        jdbcTemplate.update("DELETE FROM ai_credit_ledger");
        jdbcTemplate.update("DELETE FROM ai_credit_accounts");
    }

    @Test
    @DisplayName("AC-001/008：攒够 N 轮后自动整理，写进去的带来源、理由与出自哪段对话")
    void extractionWritesMemoryWithProvenance() {
        long conversationId = newConversationWithTurns(ROUNDS);
        when(extractor.extract(any(), any())).thenReturn(new MemoryExtractor.MemoryExtraction(
                List.of(new MemoryCandidate(MemoryCandidate.Action.ADD, MemoryCandidate.Store.TEXT, null, null,
                        null, "他准备秋招", "提到求职")),
                new TokenUsage(900, 120, 1020)));

        extraction.scheduleIfDue(user, conversationId);
        awaitMemoryCount(1);

        MemoryStore.MemoryItem item = memories.list(user, 0, 0).getFirst();
        assertThat(item.text()).isEqualTo("他准备秋招");
        assertThat(item.origin()).isEqualTo(MemoryStore.SOURCE_AUTO);
        assertThat(item.reason()).isEqualTo("提到求职");
        assertThat(item.conversationId()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("WORK-050 AC-006：PROPOSE 只进候选表，正式表一行不多")
    void proposeWritesOnlyCandidates() {
        long conversationId = newConversationWithTurns(ROUNDS);
        when(extractor.extract(any(), any())).thenReturn(new MemoryExtractor.MemoryExtraction(
                List.of(new MemoryCandidate(MemoryCandidate.Action.PROPOSE, MemoryCandidate.Store.TEXT, null,
                        null, null, "他最近在翻 DDIA", "动机不明")),
                new TokenUsage(900, 120, 1020)));

        extraction.scheduleIfDue(user, conversationId);
        awaitCandidateCount(1);

        assertThat(memories.list(user, 0, 0)).as("提议不是事实，不能进正式表").isEmpty();
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memory_candidates WHERE user_id = ? AND status = 'pending'",
                Integer.class, user);
        assertThat(pending).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-002：同一件事说两次不新增（UPDATE 覆盖原来那条）")
    void updatingDoesNotDuplicate() {
        long conversationId = newConversationWithTurns(ROUNDS);
        when(extractor.extract(any(), any())).thenReturn(new MemoryExtractor.MemoryExtraction(
                List.of(new MemoryCandidate(MemoryCandidate.Action.ADD, MemoryCandidate.Store.TEXT, null, null,
                        null, "他在准备秋招", "求职")), null));
        extraction.scheduleIfDue(user, conversationId);
        awaitMemoryCount(1);
        long firstId = memories.list(user, 0, 0).getFirst().id();

        // 第二次：模型判定"同一件事、说法更完整" → UPDATE 那条
        long next = newConversationWithTurns(ROUNDS);
        when(extractor.extract(any(), any())).thenReturn(new MemoryExtractor.MemoryExtraction(
                List.of(new MemoryCandidate(MemoryCandidate.Action.UPDATE, MemoryCandidate.Store.TEXT, firstId,
                        null, null, "他在准备 2026 届秋招", "更具体")), null));
        extraction.scheduleIfDue(user, next);
        awaitMemoryText("他在准备 2026 届秋招");

        assertThat(memories.list(user, 0, 0)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_memories_text", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-009：整理不占用户时间——回答已经发完，整理还卡在模型调用上")
    void extractionDoesNotBlockTheAnswer() throws Exception {
        long conversationId = newConversationWithTurns(ROUNDS - 1);
        CountDownLatch insideExtractor = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(extractor.extract(any(), any())).thenAnswer(invocation -> {
            insideExtractor.countDown();
            // 卡住：模拟"上游很慢"。若整理与回答在同一条路上，下面的 SSE 请求就回不来
            release.await(10, TimeUnit.SECONDS);
            return new MemoryExtractor.MemoryExtraction(List.of(), null);
        });
        stubStream("好的");

        MvcResult answer = sendMessage(conversationId, "再问一句");

        // 回答完整回来了（含 done），而整理仍卡在模型调用里
        assertThat(body(answer)).contains("好的").contains("done");
        assertThat(insideExtractor.await(5, TimeUnit.SECONDS))
                .as("第 " + ROUNDS + " 轮应当触发一次整理").isTrue();
        assertThat(release.getCount()).isEqualTo(1L);

        release.countDown();
    }

    @Test
    @DisplayName("T-06：同一会话并发触发只跑一次（游标是抢出来的）")
    void concurrentTriggersRunOnce() {
        long conversationId = newConversationWithTurns(ROUNDS);
        AtomicInteger calls = new AtomicInteger();
        when(extractor.extract(any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return new MemoryExtractor.MemoryExtraction(List.of(), null);
        });

        // 两个标签页各问一句 → 两次 scheduleIfDue。第一次抢到游标并把 upTo 推到位，
        // 第二次读到的游标已经是新的（但这一轮又攒不够 N），于是退出。
        extraction.scheduleIfDue(user, conversationId);
        extraction.scheduleIfDue(user, conversationId);
        awaitExtractorCalls(calls, 1);

        assertThat(calls.get()).as("并发触发只能整理一次").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-004：模型调用失败不写记忆、不影响下一次，且不重跑（游标已推进）")
    void extractorFailureIsSwallowed() {
        long conversationId = newConversationWithTurns(ROUNDS);
        when(extractor.extract(any(), any())).thenThrow(new IllegalStateException("上游炸了"));

        extraction.scheduleIfDue(user, conversationId);
        awaitExtractorCalls(user, 1);

        assertThat(memories.list(user, 0, 0)).isEmpty();
        // 游标已推进 → 再触发一次不会重跑（这是登记过的取舍：失败不重试）
        extraction.scheduleIfDue(user, conversationId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memories_text WHERE user_id = ?", Long.class, user)).isZero();
    }

    @Test
    @DisplayName("AC-005：关掉总开关后不再整理，手动写入被明确拒绝（不是静默写进去）")
    void disabledMemoryStopsExtractionAndManualWrite() throws Exception {
        long conversationId = newConversationWithTurns(ROUNDS);
        assertThat(saveSettings(false, true, true)).isEqualTo(200);

        extraction.scheduleIfDue(user, conversationId);
        Thread.sleep(200);
        verify(extractor, times(0)).extract(any(), any());

        MvcResult rejected = mockMvc.perform(post("/api/v1/ai/memories")
                        .header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"text\",\"text\":\"这句应该记不下来\"}"))
                .andReturn();
        // 409 + 业务码 40910：界面据此说"是你自己关掉的"，而不是一句"参数不合法"
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(rejected)).contains("40910");
        assertThat(memories.list(user, 0, 0)).isEmpty();
    }

    @Test
    @DisplayName("AC-006：关掉「参考当前对话的历史消息」后，模型只看到本轮这一句")
    void historyAxisIsIndependent() throws Exception {
        long conversationId = newConversationWithTurns(3);
        assertThat(saveSettings(true, true, false)).isEqualTo(200);

        // 用 stubStream：它会把请求记进 assistantsSeen，这里要断言的正是"给了模型什么上下文"
        stubStream("好的");

        sendMessage(conversationId, "这一轮的话");

        assertThat(assistantsSeen).hasSize(1);
        assertThat(assistantsSeen.getFirst().history())
                .as("关掉历史轴之后，进模型的上下文里不该有前面几轮")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-007：「只关不删」与「清空并关闭」是两件事")
    void clearingIsSeparateFromDisabling() throws Exception {
        memories.rememberText(user, "第一句");
        memories.rememberText(user, "第二句");
        // 只关不删：记忆还在（用户想"先关掉看看"时不该被清空）
        assertThat(saveSettings(false, true, true)).isEqualTo(200);
        assertThat(memories.list(user, 0, 0)).hasSize(2);

        // 打开再清空并关闭：两路为空 + 总闸为关
        assertThat(saveSettings(true, true, true)).isEqualTo(200);
        MvcResult cleared = mockMvc.perform(delete("/api/v1/ai/memories").header("Authorization", token(user)))
                .andReturn();
        assertThat(body(cleared)).contains("\"cleared\":2").contains("\"memoryEnabled\":false");
        assertThat(memories.list(user, 0, 0)).isEmpty();
        assertThat(settings.get(user).memoryEnabled()).isFalse();
    }

    @Test
    @DisplayName("AC-003：判定「这条已不成立」时真的把它删掉")
    void deleteActionRemovesTheRow() {
        long existing = memories.rememberText(user, "他当时说要考公");
        long conversationId = newConversationWithTurns(ROUNDS);
        when(extractor.extract(any(), any())).thenReturn(new MemoryExtractor.MemoryExtraction(
                List.of(new MemoryCandidate(MemoryCandidate.Action.DELETE, MemoryCandidate.Store.TEXT,
                        existing, null, null, null, "他改主意了")), null));

        extraction.scheduleIfDue(user, conversationId);
        awaitExtractorCalls(user, 1);
        awaitMemoryCount(0);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_memories_text WHERE user_id = ?", Long.class, user)).isZero();
    }

    @Test
    @DisplayName("AC-004：余额不足时跳过整理，且**一个上游请求都不发**")
    void insufficientCreditsSkipsExtraction() {
        // 把余额花光：整理也要花钱，而"钱不够就别发请求"是 AC-004 的另一半
        long broke = user + 1;
        credits.adjust(broke, 100_000, 0L, "test");
        credits.adjust(broke, -100_000, 0L, "清零");
        long conversationId = conversations.create(broke, null);
        for (int i = 0; i < ROUNDS; i++) {
            conversations.complete(broke, conversationId, "第 " + i + " 轮回答", null);
        }

        extraction.scheduleIfDue(broke, conversationId);
        awaitExtractorCalls(broke, 1);

        verify(extractor, times(0)).extract(any(), any());
        assertThat(memories.list(broke, 0, 0)).isEmpty();
    }

    @Test
    @DisplayName("AC-010：自动整理的消耗单独记一行流水，与对话的消耗分得开")
    void extractionIsRecordedInLedger() {
        long conversationId = newConversationWithTurns(ROUNDS);
        when(extractor.extract(any(), any())).thenReturn(new MemoryExtractor.MemoryExtraction(
                List.of(), new TokenUsage(1000, 200, 1200)));

        extraction.scheduleIfDue(user, conversationId);
        awaitLedgerKind("MEMORY_EXTRACT");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT input_tokens FROM ai_credit_ledger WHERE user_id = ? AND kind = 'MEMORY_EXTRACT'",
                Integer.class, user)).isEqualTo(1000);
    }

    @Test
    @DisplayName("AC-008：列表带分页，来源与理由都回给界面")
    void listSupportsPagingAndProvenance() throws Exception {
        memories.rememberText(user, "手动那句");
        long conversationId = newConversationWithTurns(ROUNDS);
        when(extractor.extract(any(), any())).thenReturn(new MemoryExtractor.MemoryExtraction(
                List.of(new MemoryCandidate(MemoryCandidate.Action.ADD, MemoryCandidate.Store.TEXT, null, null,
                        null, "自动那句", "顺手记的")), null));
        extraction.scheduleIfDue(user, conversationId);
        awaitMemoryCount(2);

        MvcResult first = mockMvc.perform(get("/api/v1/ai/memories?limit=1&offset=0")
                        .header("Authorization", token(user)))
                .andReturn();
        MvcResult second = mockMvc.perform(get("/api/v1/ai/memories?limit=1&offset=1")
                        .header("Authorization", token(user)))
                .andReturn();

        assertThat(body(first)).contains("\"origin\"").contains("\"reason\"");
        assertThat(body(first)).contains("自动那句").contains("顺手记的").contains("\"origin\":\"auto\"");
        assertThat(body(second)).doesNotContain("自动那句");
    }

    // ---- 辅助 ----

    /** 造一个会话并落 {@code rounds} 轮助手消息。 */
    private long newConversationWithTurns(int rounds) {
        long conversationId = conversations.create(user, null);
        for (int i = 0; i < rounds; i++) {
            conversations.complete(user, conversationId, "第 " + i + " 轮回答", null);
        }
        return conversationId;
    }

    /**
     * 发一轮消息并把 SSE 流读完。
     *
     * <p>必须走 {@code asyncStarted} + {@code asyncDispatch}（本目录既有约定）：SSE 的正文是在
     * **另一个线程**里写的，直接读响应体只会读到空串——那会让断言看着像"功能坏了"。
     */
    private MvcResult sendMessage(long conversationId, String message) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(user))
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn();
    }

    private void stubStream(String content) {
        doAnswer(invocation -> {
            AssistantConversation.Request request = invocation.getArgument(0);
            assistantsSeen.add(request);
            AssistantConversation.Listener listener = invocation.getArgument(3);
            listener.onContent(content);
            listener.onUsage(new TokenUsage(10, 5, 15));
            listener.onCompleted();
            return null;
        }).when(assistant).stream(any(), any(), any(), any());
    }

    private int saveSettings(boolean memory, boolean saved, boolean history) throws Exception {
        return mockMvc.perform(put("/api/v1/ai/settings")
                        .header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memoryEnabled\":" + memory + ",\"referenceSavedMemories\":" + saved
                                + ",\"referenceChatHistory\":" + history + "}"))
                .andReturn().getResponse().getStatus();
    }

    /** 等异步那一步落地。超时即失败，不静默通过。 */
    private void awaitMemoryCount(int expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (memories.list(user, 0, 0).size() == expected) {
                return;
            }
            sleep();
        }
        assertThat(memories.list(user, 0, 0)).as("等记忆写入超时").hasSize(expected);
    }

    private void awaitMemoryText(String expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (memories.list(user, 0, 0).stream().anyMatch(it -> expected.equals(it.text()))) {
                return;
            }
            sleep();
        }
        assertThat(memories.list(user, 0, 0).stream().map(MemoryStore.MemoryItem::text)).contains(expected);
    }

    private void awaitCandidateCount(int expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_memory_candidates WHERE user_id = ?", Integer.class, user);
            if (count != null && count >= expected) {
                return;
            }
            sleep();
        }
        throw new AssertionError("等候选超时");
    }

    private void awaitLedgerKind(String kind) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_credit_ledger WHERE user_id = ? AND kind = ?", Long.class, user, kind);
            if (count != null && count > 0) {
                return;
            }
            sleep();
        }
        throw new AssertionError("等流水 " + kind + " 超时");
    }

    private void awaitExtractorCalls(AtomicInteger calls, int expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (calls.get() >= expected) {
                return;
            }
            sleep();
        }
        assertThat(calls.get()).isGreaterThanOrEqualTo(expected);
    }

    /** 失败用例没有计数器可用：等"游标已推进"这个可观测的事实。 */
    private void awaitExtractorCalls(long userId, int expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer claimed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_conversations WHERE user_id = ? "
                            + "AND last_memory_extract_message_id IS NOT NULL", Integer.class, userId);
            if (claimed != null && claimed >= expected) {
                // 游标推进只说明"抢到了"，真正跑完还要一点时间：给它一小会儿再断言"没写记忆"
                sleep();
                return;
            }
            sleep();
        }
        throw new AssertionError("等整理被触发超时");
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String token(long userId) {
        return "Bearer " + authPort.issue(new AuthPort.Subject(Long.toString(userId), AuthPort.ROLE_LEARNER)).value();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
