package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.ai.DistilledDocument;
import io.github.xiaou61.ai.DistilledEntry;
import io.github.xiaou61.ai.DocumentDistiller;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.knowledge.EntryContent;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.security.AuthPort;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * WORK-076 文档入库全链路集成测试（真实 MySQL；只把「模型蒸馏」换成桩）。
 *
 * <p>覆盖 AC-101（上传 PDF→抽文本→蒸馏成 draft 条目，正文 Markdown + 来源记录）、
 * AC-103（同内容重复导入按指纹去重）、AC-104（相对路径生成分类树）、
 * AC-102 的可用代理（发布后条目进入 {@code KnowledgeApi.publishedContents()}，即 RAG 的索引语料）。
 *
 * <p>唯一被替换的是 {@link DocumentDistiller}——真实环境由 AgentScope+平台凭据承担；本机测试
 * 不打真实上游（与全仓其它 AI 测试同一纪律），其余全是真实上传、真实抽取、真实落库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 把导入 relay 的首次触发推迟到 1 小时后：测试自己用反射调用 processPending，避免定时任务并发抢锁
@TestPropertySource(properties = "paideia.ai.knowledge-import.initial-delay-ms=3600000")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class KnowledgeImportFlowIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        DocumentDistiller stubDistiller() {
            return (fileName, text, access) -> new DistilledDocument(
                    new DistilledEntry("W076 蒸馏条目", text, List.of("概念A")),
                    new TokenUsage(1, 1, 2), false);
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    AuthPort authPort;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ApplicationContext context;
    @Autowired
    KnowledgeApi knowledge;

    private String admin() {
        return TestTokens.bearer(authPort, "1", AuthPort.ROLE_ADMIN);
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    /** 造一个带文字层（≥50 个字母数字）的最小 PDF。 */
    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private String upload(byte[] bytes, String name) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/knowledge/admin/files")
                        .file(new MockMultipartFile("file", name, "application/pdf", bytes))
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private long enqueue(String fileId, String relativePath) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ai/admin/knowledge-imports")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":null,\"items\":[{\"fileId\":\"" + fileId
                                + "\",\"relativePath\":\"" + relativePath + "\"}]}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.importIds[0]")).longValue();
    }

    /** 直接触发 relay 的 processPending（包内方法，反射调用；不依赖 30s 定时）。 */
    private void runPending() throws Exception {
        Object service = context.getBean("knowledgeImportService");
        Method method = service.getClass().getDeclaredMethod("processPending");
        method.setAccessible(true);
        method.invoke(service);
    }

    @Test
    @DisplayName("PDF→抽取→蒸馏→draft（来源+指纹+目录树）：AC-101 / AC-104")
    void importsPdfIntoDraftWithSourceAndCategory() throws Exception {
        String text = repeat("W076 import flow integration text 1234567890 ", 3);
        byte[] pdf = pdf(text);
        String fileId = upload(pdf, "w076.pdf");
        long importId = enqueue(fileId, "导入测试/子目录/a.pdf");

        runPending();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, title, body, status, source_url, fingerprint, category_id " +
                        "FROM knowledge_entries WHERE source_url = ?", "/api/v1/knowledge/files/" + fileId);
        assertThat(row.get("status")).isEqualTo("draft");
        assertThat((String) row.get("title")).isEqualTo("W076 蒸馏条目");
        assertThat((String) row.get("body")).contains("W076 import flow");
        assertThat((String) row.get("fingerprint")).hasSize(64);

        // 任务本身成功
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_knowledge_imports WHERE id = ?", String.class, importId))
                .isEqualTo("succeeded");

        // AC-104：相对路径生成两层分类
        Long childCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_categories c2 JOIN knowledge_categories c1 ON c2.parent_id = c1.id "
                        + "WHERE c1.name = '导入测试' AND c2.name = '子目录'", Long.class);
        assertThat(childCount).isEqualTo(1L);

        long entryId = ((Number) row.get("id")).longValue();
        // 清理（本次运行独占库，仍显式删，避免同类其它用例干扰）
        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
    }

    @Test
    @DisplayName("重复导入同内容：按指纹去重，只保留一条（AC-103）")
    void deduplicatesByFingerprint() throws Exception {
        String text = repeat("W076 dedupe body content 9876543210 unique-marker ", 3);
        byte[] pdf = pdf(text);

        String file1 = upload(pdf, "dup1.pdf");
        enqueue(file1, "去重测试/a.pdf");
        runPending();

        String file2 = upload(pdf, "dup2.pdf");
        long secondImport = enqueue(file2, "去重测试/b.pdf");
        runPending();

        String url1 = "/api/v1/knowledge/files/" + file1;
        String url2 = "/api/v1/knowledge/files/" + file2;
        // 去重按指纹：第二次导入命中已存在条目，不会再建一条——所以两个来源 URL 合计只有 1 条条目
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entries WHERE source_url IN (?, ?)", Long.class, url1, url2);
        assertThat(count).isEqualTo(1L);
        // 第二次导入也应成功（指向已存在的条目），而不是失败
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_knowledge_imports WHERE id = ?", String.class, secondImport))
                .isEqualTo("succeeded");

        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE source_url IN (?, ?)", url1, url2);
    }

    @Test
    @DisplayName("发布后进入 RAG 索引语料（publishedContents）：AC-102 代理")
    void publishedEntryEntersRagCorpus() throws Exception {
        String text = repeat("W076 published corpus text 5555555555 ", 3);
        byte[] pdf = pdf(text);
        String fileId = upload(pdf, "pub.pdf");
        enqueue(fileId, "发布测试/pub.pdf");
        runPending();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, title, body, category_id FROM knowledge_entries WHERE source_url = ?",
                "/api/v1/knowledge/files/" + fileId);
        long entryId = ((Number) row.get("id")).longValue();
        Long categoryId = row.get("category_id") == null ? null : ((Number) row.get("category_id")).longValue();

        // 发布（PUT 一次把 status 置 published）
        var payload = new java.util.HashMap<String, Object>();
        payload.put("title", row.get("title"));
        payload.put("body", row.get("body"));
        payload.put("categoryId", categoryId);
        payload.put("status", "published");
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        mockMvc.perform(put("/api/v1/knowledge/admin/entries/" + entryId)
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        List<EntryContent> corpus = knowledge.publishedContents();
        assertThat(corpus).anyMatch(entry -> entry.id() == entryId);

        jdbcTemplate.update("DELETE FROM knowledge_entries WHERE id = ?", entryId);
    }
}
