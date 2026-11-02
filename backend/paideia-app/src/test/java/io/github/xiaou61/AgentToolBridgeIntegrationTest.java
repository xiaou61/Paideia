package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.ai.Harness;
import io.github.xiaou61.ai.internal.tools.AgentToolBridge;
import io.github.xiaou61.security.AuthPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 助手只读工具（AC-007）。
 *
 * <p>测的是**工具本身**：检索只给已发布、自评标记只给本人、参数越界被夹住、未知工具名不炸。
 * **工具循环本身（模型要工具→执行→回喂）没有自动化覆盖**：那需要一个真实上游，本环境没有，
 * 也不该花谁的 Key。这与 WORK-005 留下的缺口同性质，已记在验证报告里。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}，未配置时整类跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AgentToolBridgeIntegrationTest {

    @Autowired
    AgentToolBridge tools;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final List<Long> createdEntries = new ArrayList<>();
    private String userA;
    private String userB;

    @BeforeEach
    void setUp() {
        createdEntries.clear();
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        userA = "61" + suffix;
        userB = "62" + suffix;
    }

    @AfterEach
    void cleanUp() {
        // 自己建的条目自己删：测试库是共享的，留下已发布条目会把知识库那边的列表断言弄红
        for (long id : createdEntries) {
            try {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/knowledge/admin/entries/" + id).header("Authorization", adminToken()));
            } catch (Exception ignored) {
                // 收尾失败不该让用例结论变得含糊；id 已记在日志里
            }
        }
    }

    @Test
    @DisplayName("knowledge_search 只返回已发布条目——草稿不能经助手漏出去")
    void searchOnlyReturnsPublished() throws Exception {
        String marker = "工具检索" + System.nanoTime();
        long published = createEntry(marker + "甲", "published");
        createEntry(marker + "乙", "draft");

        AgentToolBridge.Outcome outcome = tools.run(Long.parseLong(userA), Harness.ASSISTANT, "knowledge_search", Map.of("keyword", marker));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.text()).contains("id=" + published + " 标题=" + marker + "甲");
        assertThat(outcome.text()).doesNotContain(marker + "乙");
    }

    @Test
    @DisplayName("检索不到时给明确说法，而不是空串")
    void searchSaysSoWhenNothingFound() {
        // **取词必须是不与任何语料共享 bigram 的**（2026-10-18 修）：ngram 全文检索在自然语言
        // 模式下是「任一 bigram 命中即算命中」，所以"绝不可能存在的词"这种长短语会命中任何含
        // "可能/存在"的正文（实测命中了 React 那篇）——它测的就不再是"检索不到"了。
        // 生僻字重复串不参与任何已有 bigram，且长度 ≥2 会走全文检索分支。
        AgentToolBridge.Outcome outcome = tools.run(Long.parseLong(userA), Harness.ASSISTANT, "knowledge_search",
                Map.of("keyword", "瞐瞐瞐瞐瞐"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.text()).contains("没有检索到");
    }

    @Test
    @DisplayName("knowledge_list_categories 给出 id／名称／上级，层级可判")
    void listCategoriesReturnsShapedRows() {
        AgentToolBridge.Outcome outcome = tools.run(Long.parseLong(userA), Harness.ASSISTANT, "knowledge_list_categories", Map.of());

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.text()).isNotBlank();
        assertThat(outcome.text()).containsPattern("id=\\d+ 名称=.+ 上级=(无（顶级）|\\d+)");
    }

    @Test
    @DisplayName("knowledge_my_marks 只给**调用者自己**的标记，别人的拿不到")
    void marksBelongToTheCallerOnly() throws Exception {
        long mineEntry = createEntry("甲的标记条目" + System.nanoTime(), "published");
        long theirsEntry = createEntry("乙的标记条目" + System.nanoTime(), "published");
        assertThat(mockMvc.perform(put("/api/v1/knowledge/entries/" + mineEntry + "/self-assessment")
                        .header("Authorization", token(userA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"unsure\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(put("/api/v1/knowledge/entries/" + theirsEntry + "/self-assessment")
                        .header("Authorization", token(userB))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"unsure\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        AgentToolBridge.Outcome outcome = tools.run(Long.parseLong(userA), Harness.ASSISTANT, "knowledge_my_marks", Map.of());

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.text()).contains("id=" + mineEntry);
        assertThat(outcome.text()).doesNotContain("id=" + theirsEntry);
    }

    @Test
    @DisplayName("工具清单里**没有会写库的工具**：draft_note 只拟草稿，写入只能经用户点击的确认端点")
    void toolsCannotWrite() {
        // 这条是防回归。将来有人往工具集里加一个真会写库的工具，就等于绕过了
        // "自主只建议不动作"（用户裁决 7）——断了它，加的人必须先想清楚这件事。
        // draft_note 在清单里是**因为**它只返回草稿（下面的用例证明它一行都没写）。
        assertThat(tools.core(Harness.ASSISTANT)).extracting(io.agentscope.core.model.ToolSchema::getName)
                .containsExactlyInAnyOrder("account_me", "assessment_list_marks", "assistant_draft_note",
                        "knowledge_get_entry", "knowledge_list_categories", "knowledge_my_marks",
                        "knowledge_search", "knowledge_weak_concepts", "note_get", "note_list",
                        // WORK-035 的只读工具：给数据集 key 返回表/列/类型，**不返回数据行**，
                        // 声明的也是 ToolScope.READ。它出现在清单里不违反"自主只建议不动作"。
                        "sql_describe_dataset",
                        // WORK-060：改现有数据集时读完整 SQL（含数据行）——只读，且只在用户点「加」时调用。
                        "sql_read_dataset",
                        // WORK-060：只拼 SQL、不落库；保存由用户在页面上点按钮。
                        "planner_build_dataset");
    }

    @Test
    @DisplayName("assistant_draft_note 只拟草稿不写库；正文为空时明确失败")
    void draftNoteStagesWithoutWriting() {
        int before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notes", Integer.class);

        AgentToolBridge.Outcome outcome = tools.run(Long.parseLong(userA), Harness.ASSISTANT, "assistant_draft_note",
                Map.of("title", "草稿标题", "body", "草稿正文"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.draft()).isNotNull();
        assertThat(outcome.draft().title()).isEqualTo("草稿标题");
        assertThat(outcome.text()).contains("等待用户确认");
        // **关键**：拟草稿不落库。落库只能经 POST /api/v1/notes（用户点击后调）
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).isEqualTo(before);

        // 空正文的草稿没有意义：失败回给模型，让它补上再提
        AgentToolBridge.Outcome empty = tools.run(Long.parseLong(userA), Harness.ASSISTANT, "assistant_draft_note", Map.of("body", "   "));
        assertThat(empty.error()).isTrue();
        assertThat(empty.draft()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("assistant_draft_note 没给标题时用兜底标题，而不是生成一条列表里显示空白的笔记")
    void draftNoteFallsBackToDefaultTitle() {
        AgentToolBridge.Outcome outcome = tools.run(Long.parseLong(userA), Harness.ASSISTANT, "assistant_draft_note", Map.of("body", "只有正文"));
        assertThat(outcome.draft()).isNotNull();
        assertThat(outcome.draft().title()).isEqualTo("来自助手的笔记");
    }

    @Test
    @DisplayName("未知工具名返回错误结果而不是抛异常——工具失败不该炸掉整轮对话")
    void unknownToolReturnsErrorOutcome() {
        AgentToolBridge.Outcome outcome = tools.run(Long.parseLong(userA), Harness.ASSISTANT, "delete_everything", Map.of());

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.text()).contains("没有名为");
    }

    @Test
    @DisplayName("条数参数不可信：给 1000 夹到上限，给 0／负数／字符串都退回默认而不是崩")
    void limitIsClamped() throws Exception {
        String marker = "上限" + System.nanoTime();
        for (int index = 0; index < 3; index++) {
            createEntry(marker + index, "published");
        }

        // 三种坏输入都必须安全返回
        for (Object limit : List.of(1000, 0, -1, "不是数字")) {
            AgentToolBridge.Outcome outcome = tools.run(Long.parseLong(userA), Harness.ASSISTANT,
                            "knowledge_search", Map.of("keyword", marker, "limit", limit));
            assertThat(outcome.error()).as("limit=%s", limit).isFalse();
            assertThat(outcome.text()).contains(marker + "0");
        }

        // 上限 20：即便要 1000 条也不会把上下文塞爆（这里只有 3 条，验的是"不报错且不超上限"）
        AgentToolBridge.Outcome capped = tools.run(Long.parseLong(userA), Harness.ASSISTANT, "knowledge_search",
                Map.of("keyword", marker, "limit", 1000));
        assertThat(capped.text().lines().count()).isLessThanOrEqualTo(20);
    }

    private long createEntry(String title, String status) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/knowledge/admin/entries")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"body\":\"正文\",\"categoryId\":null,"
                                + "\"status\":\"" + status + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as("建房：%s", body(created)).isEqualTo(200);
        long id = ((Number) JsonPath.read(body(created), "$.data")).longValue();
        createdEntries.add(id);
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
