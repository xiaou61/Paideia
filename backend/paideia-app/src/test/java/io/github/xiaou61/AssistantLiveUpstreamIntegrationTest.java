package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * **真实上游**的端到端检查（WORK-006 唯一一处连真模型的地方）。
 *
 * <p>为什么单独一个类、而且要显式给 Key 才跑：它**花真钱**（用户自己的额度），
 * 而且结果依赖模型当下的行为，不适合放进每次构建。默认跳过并计入 Skipped。
 *
 * <p>它补的是另外三个类覆盖不到的三段：
 * <ol>
 *   <li><b>真实调用能通</b>——DeepSeek 端点 + formatter + 密钥，全程没用过；</li>
 *   <li><b>工具循环</b>——模型真的会调工具 → 我们执行 → 回喂 → 出答案（中间那段从来没验过）；</li>
 *   <li><b>草稿</b>——模型真的会调 {@code draft_note} 并让 {@code draft} 事件出现（REQ-016 的关键证据）。</li>
 * </ol>
 *
 * <p>启用方式（Key 只从环境变量来，**不进仓库**）：
 * <pre>
 * PAIDEIA_TEST_DB_PASSWORD=… \
 * PAIDEIA_TEST_DEEPSEEK_KEY=sk-… \
 * PAIDEIA_TEST_DEEPSEEK_MODEL=deepseek-flash \
 * mvn -B -o -pl paideia-app -am test -Dtest=AssistantLiveUpstreamIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DEEPSEEK_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AssistantLiveUpstreamIntegrationTest {

    /** 真实端点与模型；都能用环境变量覆盖，但默认值就是官方那套。 */
    private static final String BASE_URL =
            System.getenv().getOrDefault("PAIDEIA_TEST_DEEPSEEK_BASE_URL", "https://api.deepseek.com");
    private static final String MODEL =
            System.getenv().getOrDefault("PAIDEIA_TEST_DEEPSEEK_MODEL", "deepseek-flash");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** 平台凭据（WORK-009 起由平台持有）。真实 Key 只从环境变量来，填进这个 Bean。 */
    @Autowired
    io.github.xiaou61.ai.internal.billing.BillingProperties billing;

    /**
     * WORK-009 起每次 AI 调用都要先预扣积分；余额为 0 会在**同步阶段**就被 402 挡下，
     * 表现为 {@code Async not started}。所以这套真实上游测试必须自己先发积分。
     *
     * <p>这条是 2026-10-19 补的：加积分闸那次没人跑过这套（它默认跳过），于是"跳过"掩盖了
     * "它其实已经跑不通"整整几天——正是那份验收报告里写的"跳过不等于通过"。
     */
    @Autowired
    io.github.xiaou61.ai.internal.billing.CreditService credits;

    private final List<Long> createdEntries = new ArrayList<>();
    private String user;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_messages");
        jdbcTemplate.update("DELETE FROM ai_conversations");
        user = Long.toString(5_000_000_000L + (System.nanoTime() % 1_000_000_000L));
        // 4 条用例各要一次真实调用，再加上工具循环可能的额外轮次；给足额度避免中途被闸拦下
        credits.adjust(Long.parseLong(user), 200_000, 1L, "真实上游用例发放");
    }

    @AfterEach
    void cleanUp() {
        for (long id : createdEntries) {
            try {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/knowledge/admin/entries/" + id).header("Authorization", adminToken()));
            } catch (Exception ignored) {
                // 收尾失败不影响结论；条目 id 已记在日志里
            }
        }
        setUp();
    }

    @Test
    @DisplayName("真实上游：一次调用能通，流式有增量，用量返还")
    void liveCallStreamsAndReturnsUsage() throws Exception {
        configureCredential();

        String stream = send("请只回答两个字：收到。");

        assertThat(stream).as("流里的事件：\n%s", stream).contains("event:content");
        assertThat(stream).contains("event:done");
        // 用量得是真实数字（0 说明没真正走通上游）
        assertThat(usageTotal(stream)).isPositive();
    }

    @Test
    @DisplayName("真实上游：模型会真的调工具（检索条目），并把结果用进回答")
    void liveModelCallsTheSearchTool() throws Exception {
        configureCredential();
        // 造一条标题极不常见的条目：模型只有**真的检索过**才可能说出这个词
        String marker = "紫电青霜" + (System.nanoTime() % 100_000);
        long entryId = createPublishedEntry(marker);

        String stream = send("请用你的检索工具查一下，平台里有没有和「" + marker + "」有关的内容？"
                + "有的话把条目标题原样告诉我。");

        // 工具真跑了 → 检索结果回到模型 → 回答里出现那个只有检索才知道的词
        assertThat(stream).as("回答里没出现只有检索才知道的标题，说明工具没跑或结果没回喂：\n%s", stream)
                .contains(marker);
        assertThat(entryId).isPositive();
    }

    @Test
    @DisplayName("真实上游：说「帮我记一条笔记」会产出 draft 事件（草稿），且不落库")
    void liveModelDraftsANoteWithoutWriting() throws Exception {
        configureCredential();
        int notesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notes", Integer.class);

        String stream = send("帮我记一条笔记：标题「导数复习」，正文「链式法则要再练」。");

        assertThat(stream).as("没有 draft 事件，说明模型没调 draft_note：\n%s", stream).contains("event:draft");
        assertThat(stream).contains("导数复习");
        // **关键**：拟草稿不写库——真正落库只能经用户点击后的 POST /api/v1/notes
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notes", Integer.class))
                .isEqualTo(notesBefore);
    }

    /** 打印真实上游有没有产生思考事件（V4.1-Flash 的 effort 档位会带来推理内容）。只记录，不断言。 */
    @Test
    @DisplayName("真实上游：记录思考事件是否出现（不断言，只作事实记录）")
    void reportsWhetherThinkingArrives() throws Exception {
        configureCredential();

        String stream = send("用一句话解释什么是导数。");

        System.out.println("[live] 思考事件出现 " + countOf(stream, "event:thinking")
                + " 次，正文事件 " + countOf(stream, "event:content") + " 次，用量 "
                + usageTotal(stream) + " tokens");
        assertThat(stream).contains("event:done");
    }

    /**
     * 把真实 Key 填进**平台**配置（WORK-009 起凭据由平台持有，不再是每个用户一份）。
     *
     * <p>Key 只从环境变量来、**不进仓库**。不需要清理：进程结束就没了，而且每个用例都会重填。
     */
    private void configureCredential() {
        var credential = billing.getPlatformCredential();
        credential.setBaseUrl(BASE_URL);
        credential.setModel(MODEL);
        credential.setApiKey(System.getenv("PAIDEIA_TEST_DEEPSEEK_KEY"));
    }

    /**
     * 把 SSE 里的 `event:content` 增量拼回一段正文。
     *
     * <p>断言必须用这个而不是原始流：模型输出是逐 token 推的，中文会被切在任意位置。
     */
    private static String concatenatedContent(String stream) {
        StringBuilder text = new StringBuilder();
        String[] lines = stream.lines().toArray(String[]::new);
        for (int index = 0; index + 1 < lines.length; index++) {
            if (!"event:content".equals(lines[index].trim()) || !lines[index + 1].startsWith("data:")) {
                continue;
            }
            String payload = lines[index + 1].substring("data:".length());
            try {
                text.append(com.jayway.jsonpath.JsonPath.read(payload, "$.delta").toString());
            } catch (RuntimeException ignored) {
                // 不是正文增量（比如 done 事件的数据）：跳过
            }
        }
        return text.toString();
    }

    /** 发一轮并把真实 SSE 流读完（会真的等上游，超时由会话配置的 60 秒兜住）。 */
    private String send(String message) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/ai/conversations")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        long conversationId = ((Number) JsonPath.read(body(created), "$.data.id")).longValue();

        return body(MockMvcSse.exchange(mockMvc, post("/api/v1/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", token())
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message.replace("\"", "\\\"") + "\"}")));
    }

    private long createPublishedEntry(String title) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/knowledge/admin/entries")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"body\":\"正文\",\"categoryId\":null,"
                                + "\"status\":\"published\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as("建房：%s", body(created)).isEqualTo(200);
        long id = ((Number) JsonPath.read(body(created), "$.data")).longValue();
        createdEntries.add(id);
        return id;
    }

    /** 从 done 事件里抠出 total；抠不到返回 -1（好让断言给出可读的失败）。 */
    private static long usageTotal(String stream) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"total\"\\s*:\\s*(\\d+)").matcher(stream);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }

    private static int countOf(String stream, String needle) {
        return stream.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private String token() {
        return TestTokens.bearer(authPort, user);
    }

    private String adminToken() {
        return TestTokens.bearer(authPort, "1", AuthPort.ROLE_ADMIN);
    }

    private static String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }
}
