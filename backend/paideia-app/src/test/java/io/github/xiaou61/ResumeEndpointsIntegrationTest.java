package io.github.xiaou61;


import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.ResumeAnalyzer;
import io.github.xiaou61.ai.ResumeData;
import io.github.xiaou61.ai.ResumeParser;
import io.github.xiaou61.ai.ResumeProposal;
import io.github.xiaou61.ai.ResumeProposer;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.security.AuthPort;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 简历端点的归属与失败语义（WORK-018 AC-007 / AC-008 / AC-010）。
 *
 * <p>三个模型端口都被桩替换：本类验的是**编排与安全边界**，不是模型质量。桩还顺便
 * 让"余额为 0 时到底有没有发起上游调用"变成一条可断言的计数，而不是一句承诺。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResumeEndpointsIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuthPort authPort;

    @MockitoBean
    ResumeParser parser;

    @MockitoBean
    ResumeAnalyzer analyzer;

    @MockitoBean
    ResumeProposer proposer;

    /** 桩被调用了几次——用来断言"余额不足时一个上游请求都不发"。 */
    private final AtomicInteger parserCalls = new AtomicInteger();

    private Long userA;
    private Long userB;

    @AfterEach
    void cleanUp() {
        for (Long userId : new Long[]{userA, userB}) {
            if (userId == null) {
                continue;
            }
            // 逐表清理由 SqlCleanup 统一（顺序 = 参数顺序，子表在前）；users 走 id 列，单独删
            SqlCleanup.deleteByUser(jdbcTemplate, userId, "resume_proposals", "resume_versions",
                    "resume_edit_history", "resume_documents", "ai_credit_ledger", "ai_credit_accounts");
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        userA = null;
        userB = null;
    }

    @Test
    @DisplayName("归属隔离：B 读不到 A 的简历、版本与提案，一律 404（不是 403）")
    void ownerScoped404() throws Exception {
        userA = insertUser("res-a");
        userB = insertUser("res-b");
        long documentId = createDocument(userA);

        assertThat(status(get("/api/v1/ai/resumes/" + documentId), userA)).isEqualTo(200);
        assertThat(status(get("/api/v1/ai/resumes/" + documentId), userB)).isEqualTo(404);
        // 同一个 id 让 B 改名、设默认、删除、看历史：全 404
        // 改名**必须带 body**：不带时请求体解析先失败会返回 400，那验不到归属
        assertThat(status(put("/api/v1/ai/resumes/" + documentId + "/title"), userB,
                "{\"title\":\"x\"}")).isEqualTo(404);
        assertThat(status(put("/api/v1/ai/resumes/" + documentId + "/default"), userB)).isEqualTo(404);
        assertThat(status(get("/api/v1/ai/resumes/" + documentId + "/history"), userB)).isEqualTo(404);
        assertThat(status(delete("/api/v1/ai/resumes/" + documentId), userB)).isEqualTo(404);
        // A 自己仍然能读
        assertThat(status(get("/api/v1/ai/resumes/" + documentId), userA)).isEqualTo(200);
    }

    @Test
    @DisplayName("匿名一律 401：没有任何免鉴权的简历读端点")
    void anonymousIsRejected() throws Exception {
        userA = insertUser("res-anon");
        long documentId = createDocument(userA);

        assertThat(status(get("/api/v1/ai/resumes"), null)).isEqualTo(401);
        assertThat(status(get("/api/v1/ai/resumes/" + documentId), null)).isEqualTo(401);
        assertThat(status(get("/api/v1/ai/resumes/" + documentId + "/checkup"), null)).isEqualTo(401);
    }

    @Test
    @DisplayName("乐观锁：旧 revision 保存返回 409，且不覆盖新内容")
    void staleRevisionConflicts() throws Exception {
        userA = insertUser("res-lock");
        long documentId = createDocument(userA);

        // 第一次保存：revision 1 → 2
        assertThat(status(put("/api/v1/ai/resumes/" + documentId), userA, saveBody(1, "第一次"))).isEqualTo(200);
        // 拿已经被推进的 revision 再保存一次 → 409
        assertThat(status(put("/api/v1/ai/resumes/" + documentId), userA, saveBody(1, "第二次"))).isEqualTo(409);
        // 库里仍是第一次的内容
        String json = jdbcTemplate.queryForObject(
                "SELECT data_json FROM resume_documents WHERE id = ?", String.class, documentId);
        assertThat(json).contains("第一次").doesNotContain("第二次");
    }

    @Test
    @DisplayName("零积分：解析不发上游请求，返回 402")
    void zeroBalanceNeverCallsUpstream() throws Exception {
        userA = insertUser("res-poor");
        // 不建积分账户 = 余额 0
        parserCalls.set(0);
        org.mockito.Mockito.when(parser.parse(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(ModelAccess.class)))
                .thenAnswer(invocation -> {
                    parserCalls.incrementAndGet();
                    return new ResumeParser.Parsed(ResumeData.empty(), 0, 0, new TokenUsage(1, 1, 2));
                });

        byte[] docx = minimalDocx();
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/ai/resumes/parse")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "cv.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                docx))
                        .header("Authorization", token(userA)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(402);
        assertThat(parserCalls.get()).as("余额不足仍然发起了上游调用").isZero();
    }

    // ── 助手 ────────────────────────────────────────────────────────

    private long createDocument(long userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ai/resumes")
                        .header("Authorization", token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"主简历",
                                 "data":{"basics":{"name":"张三","phone":"","email":"","city":"","links":[]},
                                         "intent":{"role":"后端","city":"","availability":""},
                                         "education":[],"work":[],"projects":[],"skills":[],
                                         "awards":[],"selfEvaluation":""},
                                 "plainText":"张三 后端"}
                                """))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private static String saveBody(int revision, String name) {
        return """
                {"title":"主简历","revision":%d,
                 "data":{"basics":{"name":"%s","phone":"","email":"","city":"","links":[]},
                         "intent":{"role":"后端","city":"","availability":""},
                         "education":[],"work":[],"projects":[],"skills":[],
                         "awards":[],"selfEvaluation":""}}
                """.formatted(revision, name);
    }

    private Long insertUser(String suffix) {
        String username = suffix + "-" + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO users (username, email, password_hash, role, email_verified_at)
                VALUES (?, ?, '$2a$10$abcdefghijklmnopqrstuv', 'learner', CURRENT_TIMESTAMP(3))
                """, username, username + "@example.test");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                       Long userId) throws Exception {
        if (userId != null) {
            builder.header("Authorization", token(userId));
        }
        builder.contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                       Long userId, String body) throws Exception {
        if (userId != null) {
            builder.header("Authorization", token(userId));
        }
        builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private String token(long userId) {
        return TestTokens.bearer(authPort, Long.toString(userId));
    }

    /**
     * 造一个最小可读的 docx。
     *
     * <p>用 DOCX 而不是 PDF：PDFBox 是 `paideia-ai` 的依赖，app 的测试类路径上没有它
     * （也不该为了一个测试把它加进来）。DOCX 只需 JDK 自带的 zip，而抽取器对两者的
     * 下游行为完全一致——本类要验的是"余额不足时不发上游调用"，不是抽取格式。
     */
    private static byte[] minimalDocx() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Zhang San  Java backend  zhangsan@example.com</w:t></w:r></w:p>
                    <w:p><w:r><w:t>University  Company  order system  800ms to 200ms</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        return out.toByteArray();
    }
}
