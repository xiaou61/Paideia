package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.ai.AssistantConversation;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.security.AuthPort;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * 积分的端到端集成测试（WORK-009 AC-003 / AC-004 / AC-005 / AC-006 / AC-010）。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}；未配置时整类跳过并计入 Skipped。
 *
 * <p>价格口径来自 {@code application-test.yml}：input 100/千、output 200/千；单次上限 5000、
 * 预留系数 0.2 → 预留 1000。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AiCreditIntegrationTest {

    /** 预留额（= maxCreditsPerCall 5000 × reserveFactor 0.2）。 */
    private static final long RESERVE = 1000;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CreditService credits;

    @MockitoBean
    AssistantConversation assistant;

    private final List<Long> createdConversations = new ArrayList<>();
    private String userA;
    private String userB;

    @BeforeEach
    void setUp() {
        createdConversations.clear();
        jdbcTemplate.update("DELETE FROM ai_messages");
        jdbcTemplate.update("DELETE FROM ai_conversations");
        jdbcTemplate.update("DELETE FROM ai_credit_reservations");
        jdbcTemplate.update("DELETE FROM ai_credit_ledger");
        jdbcTemplate.update("DELETE FROM ai_credit_accounts");
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        userA = "81" + suffix;
        userB = "82" + suffix;
    }

    @AfterEach
    void cleanUp() {
        setUp();
    }

    @Test
    @DisplayName("AC-003：余额不足 → 402，且**一个上游请求都没发出去**")
    void insufficientCreditsBlocksBeforeAnyUpstreamCall() throws Exception {
        // 不发放任何积分：余额 0
        long conversationId = createConversation(userA);

        MvcResult result = mockMvc.perform(post("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(userA))
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("响应：%s", body(result)).isEqualTo(402);
        assertThat(body(result)).contains("积分不足");
        // **这是本项最要紧的一条断言**：钱不够就不该产生任何上游调用
        verify(assistant, never()).stream(any(), any(), any(), any());
        // 被拒的那一轮**留下用户消息、不留助手消息**：这句话用户确实说过，界面也已经画出来了，
        // 抹掉它就是"刷新即丢"。AC-003 只管"上游没有收到任何请求"，不要求丢用户输入。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ? AND role = 'user'",
                Long.class, conversationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ? AND role = 'assistant'",
                Long.class, conversationId)).isZero();
    }

    @Test
    @DisplayName("AC-004：并发预留不超扣（余额永远不为负）")
    void concurrentReserveNeverOverdraws() throws Exception {
        long userId = Long.parseLong(userA);
        credits.adjust(userId, RESERVE * 5, 1L, "并发用例初始发放");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                jobs.add(() -> {
                    try {
                        credits.reserve(userId, RESERVE);
                        ok.incrementAndGet();
                    } catch (RuntimeException expected) {
                        // 余额不够就是这条路径：预期之内
                    }
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(jobs)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ok.get()).as("只可能有 5 个成功（余额正好够 5 次）").isEqualTo(5);
        assertThat(credits.balance(userId)).as("余额不为负").isZero();
    }

    @Test
    @DisplayName("AC-006：结算只写一行净额，能对上那条助手消息；预留不进流水")
    void settleIsTraceableToTheMessage() throws Exception {
        long userId = Long.parseLong(userA);
        credits.adjust(userId, 10_000, 1L, "用例发放");
        long conversationId = createConversation(userA);
        stubStream("回答", new TokenUsage(500, 500, 1000));

        sendMessage(userA, conversationId, "问一句");

        long messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_messages WHERE conversation_id = ? AND role = 'assistant'",
                Long.class, conversationId);
        // 价格：500 input → 500×100/1000 = 50；500 output → 500×200/1000 = 100；合计 150
        Long delta = jdbcTemplate.queryForObject(
                "SELECT delta FROM ai_credit_ledger WHERE kind = 'SETTLE' AND ref_message_id = ?",
                Long.class, messageId);
        assertThat(delta).as("一次调用只写一行净额：−150").isEqualTo(-150L);
        // **预留与退还都不进流水**（2026-10-16 用户要求）：它们是实现细节，不是用户看得懂的交易。
        // 于是"一次调用在历史里就是一行"，而不是预扣−1000、退还+998 两行。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_credit_ledger WHERE kind IN ('RESERVE', 'REFUND')", Long.class))
                .as("预扣与退还不写流水").isZero();
        assertThat(credits.balance(userId)).isEqualTo(10_000 - 150);
    }

    @Test
    @DisplayName("AC-005 / AC-010：管理员调整留痕（操作人+原因），学习者能看到自己的余额与明细")
    void adminAdjustIsAuditedAndVisibleToTheLearner() throws Exception {
        MvcResult adjusted = mockMvc.perform(post("/api/v1/ai/admin/credits/" + userA + "/adjust")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":500,\"note\":\"秋招活动发放\"}"))
                .andReturn();
        assertThat(adjusted.getResponse().getStatus()).as("响应：%s", body(adjusted)).isEqualTo(200);

        MvcResult audit = mockMvc.perform(get("/api/v1/ai/admin/credits/audit").header("Authorization", adminToken()))
                .andReturn();
        assertThat(body(audit)).contains("秋招活动发放");
        // 操作人进流水
        assertThat(jdbcTemplate.queryForObject(
                "SELECT operator_user_id FROM ai_credit_ledger WHERE kind = 'ADJUST'", Long.class)).isNotNull();

        MvcResult mine = mockMvc.perform(get("/api/v1/ai/credits").header("Authorization", token(userA)))
                .andReturn();
        assertThat(JsonPath.<Integer>read(body(mine), "$.data.balance")).isEqualTo(500);

        // 别人看不到：userB 的余额是 0，明细为空
        MvcResult theirs = mockMvc.perform(get("/api/v1/ai/credits").header("Authorization", token(userB)))
                .andReturn();
        assertThat(JsonPath.<Integer>read(body(theirs), "$.data.balance")).isZero();
        assertThat(body(theirs)).doesNotContain("秋招活动发放");
    }

    @Test
    @DisplayName("越权回归：学习者访问管理端前缀一律 403")
    void learnerCannotReachAdminPrefix() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/ai/admin/credits/audit").header("Authorization", token(userA)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(post("/api/v1/ai/admin/credits/" + userA + "/adjust")
                        .header("Authorization", token(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":100000,\"note\":\"给自己发\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(credits.balance(Long.parseLong(userA))).as("越权的那次没有改动任何余额").isZero();
    }

    /** 桩：按顺序回调正文、用量、结束。 */
    private void stubStream(String content, TokenUsage usage) {
        doAnswer(invocation -> {
            AssistantConversation.Listener listener = invocation.getArgument(3);
            listener.onContent(content);
            listener.onUsage(usage);
            listener.onCompleted();
            return null;
        }).when(assistant).stream(any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-005：预约结算按 round_id 幂等，重复结算不再多扣")
    void settleRoundIsIdempotent() {
        long userId = Long.parseLong(userA);
        credits.adjust(userId, 10_000, 1L, "用例发放");
        CreditService.Reservation reserved = credits.reserveRound(userId, RESERVE);
        CreditService.Reservation round =
                new CreditService.Reservation(reserved.roundId(), userId, reserved.amount());

        int first = credits.settleRound(round, "deepseek-flash", 500, 500, null);
        int second = credits.settleRound(round, "deepseek-flash", 500, 500, null);

        assertThat(first).as("500 input→50，500 output→100，合计 150").isEqualTo(150);
        assertThat(second).as("同一 round 第二次结算应幂等返回 0").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_credit_ledger WHERE kind = 'SETTLE'", Long.class))
                .as("只写一行净额，不会重复记账").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_credit_reservations WHERE round_id = ?", String.class, reserved.roundId()))
                .isEqualTo("SETTLED");
        assertThat(credits.balance(userId)).isEqualTo(10_000 - 150);
    }

    @Test
    @DisplayName("AC-005：预约退回按 round_id 幂等，余额只恢复一次")
    void refundRoundIsIdempotent() {
        long userId = Long.parseLong(userA);
        credits.adjust(userId, 10_000, 1L, "用例发放");
        CreditService.Reservation reserved = credits.reserveRound(userId, RESERVE);
        CreditService.Reservation round =
                new CreditService.Reservation(reserved.roundId(), userId, reserved.amount());
        assertThat(credits.balance(userId)).as("预留先扣掉 1000").isEqualTo(10_000 - RESERVE);

        credits.refundRound(round, null, "上游未返回用量");
        credits.refundRound(round, null, "重复退回");

        assertThat(credits.balance(userId)).as("只退回一次，不会退两次").isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_credit_reservations WHERE round_id = ?", String.class, reserved.roundId()))
                .isEqualTo("REFUNDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_credit_ledger WHERE kind IN ('RESERVE','REFUND')", Long.class))
                .as("预留与退还不写流水").isZero();
    }

    @Test
    @DisplayName("AC-005：中断的预约超过阈值会被恢复任务整笔退回，重复恢复不再变动")
    void staleReservationIsRecovered() {
        long userId = Long.parseLong(userA);
        credits.adjust(userId, 10_000, 1L, "用例发放");
        CreditService.Reservation reserved = credits.reserveRound(userId, RESERVE);
        // 造一笔"卡住"的预约：把 created_at 拨回超过超时阈值
        jdbcTemplate.update("UPDATE ai_credit_reservations SET created_at = ? WHERE round_id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), reserved.roundId());

        List<CreditService.Reservation> stale = credits.staleReservations(Duration.ofMinutes(10), 200);
        assertThat(stale).extracting(CreditService.Reservation::roundId).contains(reserved.roundId());
        for (CreditService.Reservation staleReservation : stale) {
            credits.refundRound(staleReservation, null, "恢复：结算前中断");
        }
        assertThat(credits.balance(userId)).as("恢复把预留整笔退回").isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_credit_reservations WHERE round_id = ?", String.class, reserved.roundId()))
                .isEqualTo("REFUNDED");

        // 再跑一次恢复：已 REFUNDED 的行不再被扫到，余额不变
        for (CreditService.Reservation staleReservation : credits.staleReservations(Duration.ofMinutes(10), 200)) {
            credits.refundRound(staleReservation, null, "重复恢复");
        }
        assertThat(credits.balance(userId)).as("重复恢复不改动余额").isEqualTo(10_000);
    }

    private MvcResult sendMessage(String subject, long conversationId, String message) throws Exception {
        return MockMvcSse.exchange(mockMvc, post("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token(subject))
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"));
    }

    private long createConversation(String subject) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/ai/conversations")
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as("建房：%s", body(created)).isEqualTo(200);
        long id = ((Number) JsonPath.read(body(created), "$.data.id")).longValue();
        createdConversations.add(id);
        return id;
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
}
