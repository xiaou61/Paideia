package io.github.xiaou61;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.security.AuthPort;
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

/**
 * WORK-076 的端到端集成测试（真实 MySQL，经 HTTP 走完 controller → service → mapper → DB）。
 *
 * <p>验两块最容易“只在真库上才暴露”的 SQL：
 * <ul>
 *   <li>导入任务列表（{@code ai_knowledge_imports} 的 read）；</li>
 *   <li>检索切片的编辑/历史/回滚（{@code ai_knowledge_chunk_edits} 的 insert / 当前版本 / 历史）。</li>
 * </ul>
 *
 * <p>test profile 关闭了 embedding 与 RAG：编辑切片会走“降级存内容、待补嵌”的分支，不触网。
 * 真库由 {@code scripts/test-db.sh} 每次运行分配独占库，种子 e2e 之外本类自己建一条临时条目。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class KnowledgeChunkImportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthPort authPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long entryId;

    @BeforeEach
    void seedEntry() {
        String title = "w076-it-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO knowledge_entries (title, body, status, published_at) VALUES (?, ?, 'published', CURRENT_TIMESTAMP(3))",
                title, "用于 WORK-076 切片编辑集成测试的正文。");
        entryId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_entries WHERE title = ?", Long.class, title);
    }

    @AfterEach
    void cleanEntry() {
        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
    }

    private String admin() {
        return TestTokens.bearer(authPort, "1", AuthPort.ROLE_ADMIN);
    }

    private String learner() {
        return TestTokens.bearer(authPort, "2", AuthPort.ROLE_LEARNER);
    }

    @Test
    @DisplayName("切片：初始为空 → 编辑单块 → 历史 → 回滚（真实 mapper SQL）")
    void chunkEditHistoryRollback() throws Exception {
        String base = "/api/v1/ai/admin/knowledge-entries/" + entryId + "/chunks";

        mockMvc.perform(get(base).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(put(base + "/0").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"手工块一\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(get(base).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].origin").value("manual"))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].orphaned").value(true));

        mockMvc.perform(get(base + "/0/revisions").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].content").value("手工块一"));

        mockMvc.perform(post(base + "/0/rollback").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(get(base + "/0/revisions").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("导入：列表可读；登记空批次返回空 id 列表")
    void importListAndEmptyCreate() throws Exception {
        mockMvc.perform(get("/api/v1/ai/admin/knowledge-imports").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/api/v1/ai/admin/knowledge-imports").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":null,\"items\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importIds").isArray())
                .andExpect(jsonPath("$.data.importIds.length()").value(0));
    }

    @Test
    @DisplayName("非管理员访问切片/导入接口被拒为 403")
    void learnerForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/ai/admin/knowledge-entries/" + entryId + "/chunks")
                        .header("Authorization", learner()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ai/admin/knowledge-imports").header("Authorization", learner()))
                .andExpect(status().isForbidden());
    }
}
