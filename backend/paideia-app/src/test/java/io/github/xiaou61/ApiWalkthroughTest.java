package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.account.internal.credential.CaptchaImageRenderer;
import io.github.xiaou61.account.internal.mail.InMemoryMailPort;
import io.github.xiaou61.ai.AssistantConversation;
import io.github.xiaou61.ai.EntrySummarizer;
import io.github.xiaou61.ai.InterviewEvaluation;
import io.github.xiaou61.ai.InterviewReviewer;
import io.github.xiaou61.ai.ResumeAnalysis;
import io.github.xiaou61.ai.ResumeAnalyzer;
import io.github.xiaou61.ai.ResumeData;
import io.github.xiaou61.ai.ResumeParser;
import io.github.xiaou61.ai.ResumeProposal;
import io.github.xiaou61.ai.ResumeProposer;
import io.github.xiaou61.ai.SummaryCard;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.camp.CampItemGenerator;
import io.github.xiaou61.ai.internal.camp.ItemValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 全接口自查：**一个命令，把每一条对外接口都真的走一遍**。
 *
 * <p>它不是拒绝矩阵（那是 {@code ApiContractTest}），也不是业务单测（那是各模块的
 * {@code *IntegrationTest}）。它回答的是最朴素的一个问题：<b>现在这套接口，整体还通不通？</b>
 * 改完一批东西、部署前想心里有数，跑这一个类就够。
 *
 * <p>三条设计取向：
 * <ol>
 *   <li><b>真数据、真链路</b>：自己注册一个临时账号、真建分类与条目、真上传文件、真分享再撤回，
 *       最后把自己的账号注销掉。不靠种子数据，也不怕种子漂移。</li>
 *   <li><b>全部跑完再判</b>：某一步失败不会中断后面的步骤；最后一次性列出"哪几条没通"。
 *       跑一轮就看到全貌，而不是修一条再跑一轮。</li>
 *   <li><b>不碰上游、不碰真容器</b>：AI 三个端口用桩（与既有集成测试同一做法），
 *       沙箱只验"接口在、状态码合理"，真跑代码仍由 {@code SandboxIntegrationTest} 与线上验证。</li>
 * </ol>
 *
 * <p>跑完会在 {@code .agent/testing/runs/} 下留一份 Markdown 记录（时间戳命名），
 * 那就是这个项目的接口自查台账。
 *
 * <p>运行：
 * <pre>
 * cd backend &amp;&amp; source config/remote-db.local.env
 * mvn -B -pl paideia-app test -Dtest=ApiWalkthroughTest
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class ApiWalkthroughTest {

    private static final String CAPTCHA_CODE = "ABCD";
    private static final String PASSWORD = "walkthrough-horse-battery";

    /** 1×1 透明 PNG。附件接口按字节嗅探类型，用真字节才能过白名单。 */
    private static final byte[] ONE_PIXEL_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Autowired
    MockMvc mvc;

    @Autowired
    InMemoryMailPort mailbox;

    @Autowired
    CreditService credits;

    /** WORK-022：详情端点需要一个真实 traceId，所以直接查一条刚写下的记录。 */
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @MockitoBean
    CaptchaImageRenderer captchaImageRenderer;

    @MockitoBean
    EntrySummarizer summarizer;

    @MockitoBean
    AssistantConversation assistant;

    @MockitoBean
    InterviewReviewer reviewer;

    // 简历那三次调用（WORK-018）：全桩住，自查只验端点可达与契约形状，不连上游、不花积分
    @MockitoBean
    ResumeParser parser;

    @MockitoBean
    ResumeAnalyzer analyzer;

    @MockitoBean
    ResumeProposer proposer;

    /** 训练营出题（WORK-029）：同样桩住，自查只验端点可达与契约形状。 */
    @MockitoBean
    CampItemGenerator campGenerator;

    /** 让 SSE 的产出在请求线程里同步跑完（理由见 {@code AssistantConversationIntegrationTest}）。 */
    @MockitoBean(name = "applicationTaskExecutor")
    TaskExecutor executor;

    /** 一轮的全部结果。失败不中断，最后统一断言。 */
    private final List<Step> steps = new ArrayList<>();

    private record Step(String name, String method, String path, int status, boolean ok, String note) {
    }

    @Test
    @DisplayName("全接口自查：注册 → 全模块读写 → 注销")
    void walksEveryEndpoint() throws Exception {
        try {
        when(captchaImageRenderer.randomCode(anyInt())).thenReturn(CAPTCHA_CODE);
        when(captchaImageRenderer.renderPng(anyString())).thenReturn(new byte[] {1, 2, 3});
        when(summarizer.summarize(anyString(), anyString(), any()))
                .thenReturn(new io.github.xiaou61.ai.GeneratedSummary(
                        new SummaryCard("一句话", List.of(
                                new SummaryCard.SummarySection("要点一", "第一节正文"),
                                new SummaryCard.SummarySection("要点二", "第二节正文"))),
                        new TokenUsage(10, 20, 30), false));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any());

        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        String username = "walk-" + suffix;
        String email = "walk-" + suffix + "@example.test";

        // ---- 认证链路 ----
        String captchaId = stringAt(call("图形验证码", "POST", "/api/v1/auth/captcha", null, "{}"),
                "$.data.captchaId");
        stepOk("图形验证码返回 id");

        call("发邮箱验证码", "POST", "/api/v1/auth/email-code", null,
                json(Map.of("email", email, "captchaId", captchaId, "captchaAnswer", CAPTCHA_CODE)));
        String code = mailbox.latestCodeFor(email).orElseThrow(() -> new AssertionError("内存信箱没有收到验证码"));

        MvcResult registered = call("注册", "POST", "/api/v1/auth/register", null,
                json(Map.of("username", username, "email", email, "password", PASSWORD, "code", code)));
        String access = stringAt(registered, "$.data.accessToken");
        String refresh = stringAt(registered, "$.data.refreshToken");
        long userId = Long.parseLong(stringAt(registered, "$.data.user.id"));
        String bearer = "Bearer " + access;

        MvcResult loggedIn = call("登录", "POST", "/api/v1/auth/login", null,
                json(Map.of("identifier", username, "password", PASSWORD)));
        String refresh2 = stringAt(loggedIn, "$.data.refreshToken");

        MvcResult rotated = call("刷新令牌", "POST", "/api/v1/auth/refresh", null,
                json(Map.of("refreshToken", refresh2)));
        String refresh3 = stringAt(rotated, "$.data.refreshToken");

        call("当前身份", "GET", "/api/v1/me", bearer, null);

        // ---- 第三方登录（WORK-037）----
        // 平台未配置时 providers 是空列表、start 返回 409——**那仍然是有效覆盖**：
        // 端点存在、鉴权正确、错误语义清楚。真实授权往返由手验负责（见验证报告）。
        call("第三方平台列表", "GET", "/api/v1/auth/oauth/providers", null, null);
        bestEffort("POST", "/api/v1/auth/oauth/github/start", null);
        // 回调是匿名 GET，带一个必然无效的 state：应当 302 回登录页，而不是 500
        bestEffort("GET", "/api/v1/auth/oauth/github/callback?code=x&state=invalid-state", null);
        // 握手码交换：无效的码应当 400 而不是 500
        bestEffort("POST", "/api/v1/auth/oauth/exchange", null);
        call("我的登录方式", "GET", "/api/v1/account/oauth/identities", bearer, null);
        bestEffort("POST", "/api/v1/account/oauth/gitee/start", bearer);
        // 解绑：这个账号只有密码，没有任何第三方绑定；删一个不存在的绑定应当是幂等的
        bestEffort("DELETE", "/api/v1/account/oauth/gitee", bearer);
        call("读画像", "GET", "/api/v1/account/profile", bearer, null);
        call("写画像", "PUT", "/api/v1/account/profile", bearer,
                "{\"identity\":\"job_seeker\",\"targetRole\":\"后端开发\",\"targetCity\":\"北京\","
                        + "\"timeline\":\"三个月内\",\"stage\":\"在读\"}");
        stepOk("画像可读可写");

        // AI 调用要过积分闸，先给自己发足额积分（operator 取自己，避免依赖种子管理员）
        credits.adjust(userId, 1_000_000, userId, "walkthrough");

        // ---- 知识库：管理端 ----
        MvcResult category = call("建分类", "POST", "/api/v1/knowledge/admin/categories", adminToken(),
                json(Map.of("name", "自查分类 " + suffix, "slug", "walk-" + suffix, "sortOrder", 10)));
        long categoryId = idAt(category);

        call("管理端分类列表", "GET", "/api/v1/knowledge/admin/categories", adminToken(), null);
        call("改分类", "PUT", "/api/v1/knowledge/admin/categories/" + categoryId, adminToken(),
                json(Map.of("name", "自查分类改名 " + suffix, "slug", "walk-" + suffix, "sortOrder", 11)));

        MvcResult entry = call("建条目", "POST", "/api/v1/knowledge/admin/entries", adminToken(),
                json(Map.of("title", "自查条目 " + suffix, "body", "## 小标题\n\n自查正文。",
                        "categoryId", categoryId, "status", "published")));
        long entryId = idAt(entry);
        // **自检：走查建的条目绝不能是种子 id**（2026-10-18 加）。
        // 全量一起跑时出现过"本测试删掉了种子条目 1"（单跑时 entryId=11、删的也是 11）。
        // 这条断言把"它自己拿到了种子 id"变成当场可见的红，而不是让别的测试事后报"读不到条目"。
        org.assertj.core.api.Assertions.assertThat(entryId)
                .as("走查新建的条目拿到了种子 id（%s）——那意味着它随后会删掉一条种子数据", entryId)
                .isGreaterThan(10);

        MvcResult entry2 = call("再建一条（供关系用）", "POST", "/api/v1/knowledge/admin/entries", adminToken(),
                json(Map.of("title", "自查条目二 " + suffix, "body", "第二条正文。",
                        "categoryId", categoryId, "status", "published")));
        long entryId2 = idAt(entry2);

        call("管理端条目列表", "GET", "/api/v1/knowledge/admin/entries?size=5", adminToken(), null);
        call("管理端条目详情", "GET", "/api/v1/knowledge/admin/entries/" + entryId, adminToken(), null);
        call("改条目", "PUT", "/api/v1/knowledge/admin/entries/" + entryId, adminToken(),
                json(Map.of("title", "自查条目改名 " + suffix, "body", "改过的正文。",
                        "categoryId", categoryId, "status", "published")));

        MvcResult relation = call("建关系", "POST", "/api/v1/knowledge/admin/relations", adminToken(),
                json(Map.of("fromEntryId", entryId, "toEntryId", entryId2, "relationType", "prerequisite")));
        long relationId = idAt(relation);
        call("删关系", "DELETE", "/api/v1/knowledge/admin/relations/" + relationId, adminToken(), null);

        // ---- 概念与图谱（WORK-021）----
        MvcResult concept = call("建概念", "POST", "/api/v1/knowledge/admin/concepts", adminToken(),
                json(Map.of("name", "自查概念 " + suffix, "summary", "自查用")));
        long conceptId = idAt(concept);
        call("改概念", "PUT", "/api/v1/knowledge/admin/concepts/" + conceptId, adminToken(),
                json(Map.of("name", "自查概念改名 " + suffix, "summary", "改过的说明")));
        // **列表必须在建概念之后调**：空库上先调会返回 []，契约快照里这个端点的形状就变成空数组，
        // 换一台机器重跑必然对不上（2026-10-19 在隔离库上实测红过）。先建再列，形状才确定。
        call("概念列表", "GET", "/api/v1/knowledge/admin/concepts", adminToken(), null);
        call("挂条目", "POST", "/api/v1/knowledge/admin/concepts/" + conceptId + "/entries", adminToken(),
                json(Map.of("entryId", entryId)));
        call("解关联", "DELETE",
                "/api/v1/knowledge/admin/concepts/" + conceptId + "/entries/" + entryId, adminToken(), null);
        MvcResult concept2 = call("建概念二", "POST", "/api/v1/knowledge/admin/concepts", adminToken(),
                json(Map.of("name", "自查概念二 " + suffix)));
        long conceptId2 = idAt(concept2);
        MvcResult conceptEdge = call("建概念关系", "POST", "/api/v1/knowledge/admin/concept-edges",
                adminToken(), json(Map.of("fromConceptId", conceptId, "toConceptId", conceptId2,
                        "relationType", "prerequisite")));
        long conceptEdgeId = idAt(conceptEdge);
        call("删概念关系", "DELETE", "/api/v1/knowledge/admin/concept-edges/" + conceptEdgeId,
                adminToken(), null);
        call("删概念", "DELETE", "/api/v1/knowledge/admin/concepts/" + conceptId2, adminToken(), null);

        // 附件类型白名单是 png/jpeg/gif/webp/pdf，且服务端按**字节**嗅探而不是看扩展名 ——
        // 所以这里必须塞真的 PNG 头，不是随便一段文本（第一版就栽在这里）
        MockMultipartFile file = new MockMultipartFile("file", "walk.png", "image/png",
                ONE_PIXEL_PNG);
        MvcResult uploaded = mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/knowledge/admin/files")
                .file(file).header("Authorization", adminToken())).andReturn();
        record("上传附件", "POST", "/api/v1/knowledge/admin/files", uploaded.getResponse().getStatus(),
                200, null);
        noteTouched("POST", "/api/v1/knowledge/admin/files");
        String fileId = stringAt(uploaded, "$.data.id");

        // ---- 知识库：学习者侧 ----
        call("分类树", "GET", "/api/v1/knowledge/categories", bearer, null);
        call("条目列表", "GET", "/api/v1/knowledge/entries?size=5", bearer, null);
        call("条目详情", "GET", "/api/v1/knowledge/entries/" + entryId, bearer, null);
        call("附件读取（免鉴权）", "GET", "/api/v1/knowledge/files/" + fileId, null, null);
        call("标记自评", "PUT", "/api/v1/knowledge/entries/" + entryId + "/self-assessment", bearer,
                json(Map.of("level", "understood")));
        call("我标记过的", "GET", "/api/v1/knowledge/self-assessments", bearer, null);
        call("取消自评", "DELETE", "/api/v1/knowledge/entries/" + entryId + "/self-assessment", bearer, null);
        call("记一次浏览", "POST", "/api/v1/knowledge/entries/" + entryId + "/view", bearer, null);

        // ---- 学习线与学习记录（WORK-020）----
        call("学习线清单", "GET", "/api/v1/learning/lines", bearer, null);
        // 推荐理由（WORK-047）：它只做润色，失败也要 200 + 空理由。这里打一次不带 body 的请求让
        // 端点被覆盖到——那种情况是 400（参数非法），所以交给 bestEffort。
        bestEffort("POST", "/api/v1/learning/advice", bearer);
        call("我的学习记录", "GET", "/api/v1/learning/progress", bearer, null);
        call("单站记录", "GET", "/api/v1/learning/entries/" + entryId, bearer, null);
        call("上报得分", "PUT", "/api/v1/learning/entries/" + entryId, bearer,
                json(Map.of("challenges", Map.of("smoke", Map.of("score", 1, "total", 1)),
                        "quizzes", Map.of("smoke", Map.of("score", 1, "total", 1)))));
        call("我的知识图谱", "GET", "/api/v1/learning/graph", bearer, null);
        call("重置本站", "DELETE", "/api/v1/learning/entries/" + entryId, bearer, null);
        // WORK-034 S1：到期复习列表（没有到期项时返回空列表，是正常结果）
        call("到期复习列表", "GET", "/api/v1/learning/reviews", bearer, null);
        call("开始复习会话", "POST", "/api/v1/learning/reviews/sessions", bearer, null);
        call("交卷（会话不存在 → 404）", "POST",
                "/api/v1/learning/reviews/sessions/999999999/answers", bearer,
                json(Map.of("answers", List.of(Map.of("itemId", 0, "choice", 1)))), 404);

        // ---- 题目知识点关联与掌握诊断（WORK-033）----
        // 作答成功路径由 QuestionAttemptIntegrationTest 覆盖；这里只要求每个端点被真实打到。
        call("我的掌握状态", "GET", "/api/v1/learning/mastery", bearer, null);
        call("补课建议", "GET", "/api/v1/learning/diagnosis?conceptId=" + conceptId, bearer, null);
        call("管理端题目清单", "GET", "/api/v1/learning/admin/questions?entryId=" + entryId,
                adminToken(), null);
        call("管理端替换标注（题目不存在 → 400）", "PUT",
                "/api/v1/learning/admin/question-concepts", adminToken(),
                json(Map.of("entryId", entryId, "blockKey", "smoke-missing",
                        "questionKey", "smoke-missing#0", "concepts", List.of(),
                        "source", "author")), 400);
        call("概念候选（题目不存在 → 400）", "GET",
                "/api/v1/learning/admin/question-concepts/suggestions?entryId=" + entryId
                        + "&blockKey=smoke-missing&questionKey=smoke-missing%230",
                adminToken(), null, 400);
        call("逐题作答（题目不存在 → 400）", "POST", "/api/v1/learning/attempts", bearer,
                json(Map.of("entryId", entryId, "blockKey", "smoke-missing",
                        "questionKey", "smoke-missing#0", "choice", 0)), 400);
        call("练习选题", "GET", "/api/v1/learning/practice/next?conceptIds=" + conceptId,
                bearer, null);
        call("AI 概念候选", "POST",
                "/api/v1/learning/admin/question-concepts/suggest-ai?entryId=" + entryId,
                adminToken(), null);
        call("评估导出", "GET", "/api/v1/learning/admin/evaluation/export", adminToken(), null);

        // ---- 学习轨道（WORK-024）----
        // 轨道只有一个写入口：导入脚本用它 upsert。自查也走它，然后读列表与地图。
        call("导入学习轨道", "POST", "/api/v1/knowledge/admin/tracks", adminToken(),
                json(Map.of("slug", "walk-track-" + suffix, "name", "自查轨道 " + suffix,
                        "summary", "自查用", "kind", "course", "sortOrder", 900,
                        "stages", java.util.List.of(
                                Map.of("entryId", entryId, "seq", 1, "chapter", "第一章",
                                        "level", "beginner")))));
        call("轨道清单", "GET", "/api/v1/learn/tracks", bearer, null);
        call("轨道关卡地图", "GET", "/api/v1/learn/tracks/walk-track-" + suffix, bearer, null);

        // ---- 笔记与分享 ----
        MvcResult note = call("建笔记", "POST", "/api/v1/notes", bearer,
                json(Map.of("title", "自查笔记 " + suffix, "body", "笔记正文。")));
        long noteId = idAt(note);
        call("笔记列表", "GET", "/api/v1/notes", bearer, null);
        call("笔记详情", "GET", "/api/v1/notes/" + noteId, bearer, null);
        call("改笔记", "PUT", "/api/v1/notes/" + noteId, bearer,
                json(Map.of("title", "自查笔记改名 " + suffix, "body", "改过的笔记正文。")));

        MvcResult share = call("分享笔记", "POST", "/api/v1/notes/" + noteId + "/share", bearer, null);
        String shareId = stringAt(share, "$.data");
        call("我的分享", "GET", "/api/v1/notes/" + noteId + "/shares", bearer, null);
        call("公开读分享（免鉴权）", "GET", "/api/v1/public/shares/" + shareId, null, null);
        call("举报分享", "POST", "/api/v1/reports", bearer,
                json(Map.of("shareId", shareId, "reason", "自查举报")));
        call("管理端举报列表", "GET", "/api/v1/notes/admin/reports", adminToken(), null);
        MvcResult reports = mvc.perform(MockMvcRequestBuilders.get("/api/v1/notes/admin/reports")
                .header("Authorization", adminToken())).andReturn();
        long reportId = firstReportId(reports, shareId);
        call("下架（处理举报）", "POST", "/api/v1/notes/admin/reports/" + reportId + "/takedown", adminToken(), null);

        MvcResult share2 = call("再分享一次（供撤回用）", "POST", "/api/v1/notes/" + noteId + "/share", bearer, null);
        call("撤回分享", "DELETE", "/api/v1/shares/" + stringAt(share2, "$.data"), bearer, null);
        call("删笔记", "DELETE", "/api/v1/notes/" + noteId, bearer, null);

        // ---- AI ----
        call("AI 凭据状态", "GET", "/api/v1/ai/credentials", bearer, null);
        call("我的积分", "GET", "/api/v1/ai/credits", bearer, null);
        // MCP 接入凭据（WORK-019）：创建 → 列表 → 吊销，三条都要走到
        MvcResult mcpToken = call("新建 MCP 凭据", "POST", "/api/v1/ai/mcp-tokens", bearer,
                json(Map.of("name", "自查用", "scopes", java.util.List.of("read"))));
        long mcpTokenId = idAt(mcpToken);
        call("MCP 凭据列表", "GET", "/api/v1/ai/mcp-tokens", bearer, null);
        call("吊销 MCP 凭据", "DELETE", "/api/v1/ai/mcp-tokens/" + mcpTokenId, bearer, null);
        call("读设置", "GET", "/api/v1/ai/settings", bearer, null);
        call("写设置", "PUT", "/api/v1/ai/settings", bearer,
                json(Map.of("memoryEnabled", true, "referenceSavedMemories", true, "referenceChatHistory", true)));

        MvcResult memory = call("写记忆", "POST", "/api/v1/ai/memories", bearer,
                json(Map.of("type", "text", "text", "自查用的一条记忆")));
        long memoryId = idAt(memory);
        call("读记忆", "GET", "/api/v1/ai/memories", bearer, null);
        call("删单条记忆", "DELETE", "/api/v1/ai/memories/text/" + memoryId, bearer, null);
        call("写第二条记忆", "POST", "/api/v1/ai/memories", bearer,
                json(Map.of("type", "text", "text", "自查用的第二条记忆")));
        call("清空并关闭记忆", "DELETE", "/api/v1/ai/memories", bearer, null);
        call("重新打开记忆", "PUT", "/api/v1/ai/settings", bearer,
                json(Map.of("memoryEnabled", true, "referenceSavedMemories", true, "referenceChatHistory", true)));

        call("读精简（可能为空）", "GET", "/api/v1/ai/summaries/" + entryId, bearer, null);
        call("生成精简（桩）", "POST", "/api/v1/ai/summaries", bearer,
                json(Map.of("entryId", entryId, "visibility", "private")));

        MvcResult conversation = call("新建会话", "POST", "/api/v1/ai/conversations", bearer,
                json(Map.of("entryId", entryId)));
        long conversationId = idAt(conversation);
        call("会话列表", "GET", "/api/v1/ai/conversations", bearer, null);
        call("会话消息（空）", "GET", "/api/v1/ai/conversations/" + conversationId + "/messages", bearer, null);

        // 发一条消息（SSE）。桩住的 AssistantConversation 只吐出完成事件，不连上游。
        doAnswer(invocation -> {
            AssistantConversation.Listener listener = invocation.getArgument(3);
            listener.onContent("自查回复");
            listener.onUsage(new TokenUsage(5, 10, 15));
            listener.onCompleted();
            return null;
        }).when(assistant).stream(any(), any(), any(), any());
        MvcResult streamed = mvc.perform(MockMvcRequestBuilders
                .post("/api/v1/ai/conversations/" + conversationId + "/messages")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(json(Map.of("message", "你好")))).andReturn();
        record("发消息（SSE）", "POST", "/api/v1/ai/conversations/" + conversationId + "/messages",
                streamed.getResponse().getStatus(), 200, null);
        noteTouched("POST", "/api/v1/ai/conversations/" + conversationId + "/messages");

        when(reviewer.review(anyString(), any())).thenReturn(new InterviewReviewer.GeneratedReview(
                new InterviewEvaluation(4,
                        List.of(new InterviewEvaluation.Dimension("技术准确性", 4, "基本准确"),
                                new InterviewEvaluation.Dimension("表达", 4, "清楚")),
                        List.of("结构清晰"), List.of("补充复杂度分析")),
                new TokenUsage(10, 20, 30)));
        call("生成面试点评（桩）", "POST", "/api/v1/ai/interviews/" + conversationId + "/review", bearer, null);
        call("我历次点评", "GET", "/api/v1/ai/interviews/evaluations", bearer, null);

        // ---- 简历（WORK-018）：三个模型端口都桩住，这条路径不连上游、不花积分 ----
        when(parser.parse(anyString(), any())).thenReturn(new ResumeParser.Parsed(
                new ResumeData(new ResumeData.Basics("张三", "", "", "", List.of()),
                        new ResumeData.Intent("", "", ""), List.of(),
                        List.of(new ResumeData.Work("某公司", "后端", "2023.07", "至今", "",
                                List.of("把接口响应从 800ms 降到 200ms"))),
                        List.of(), List.of(new ResumeData.SkillGroup("语言", List.of("Java"))),
                        List.of(), ""),
                0, 0, new TokenUsage(10, 20, 30)));
        when(analyzer.analyze(any(), anyString(), any())).thenReturn(new ResumeAnalyzer.Analyzed(
                new ResumeAnalysis(List.of(
                        new ResumeAnalysis.Requirement("Java", "熟悉 Java"),
                        new ResumeAnalysis.Requirement("Kubernetes", "熟悉 Kubernetes")),
                        "看重后端基础"),
                new TokenUsage(10, 20, 30)));
        when(proposer.propose(any(), anyString(), any(), any(), any())).thenReturn(new ResumeProposer.Proposed(
                List.of(new ResumeProposal("work[0].bullets[0]", "bullet",
                        "把接口响应从 800ms 降到 200ms",
                        "高并发场景下把接口响应从 800ms 降到 200ms",
                        "rewrite", "JD 提到高并发", List.of("Java"))),
                0, new TokenUsage(10, 20, 30)));

        // 上传解析走 multipart（它不是 JSON 端点，不能用 call()）
        MvcResult parsed = mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/ai/resumes/parse")
                .file(new org.springframework.mock.web.MockMultipartFile(
                        "file", "cv.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        selfCheckDocx()))
                .header("Authorization", bearer)).andReturn();
        record("上传并解析简历（桩）", "POST", "/api/v1/ai/resumes/parse",
                parsed.getResponse().getStatus(), 200, null);
        noteTouched("POST", "/api/v1/ai/resumes/parse");

        MvcResult resumeDetail = call("新建主简历", "POST", "/api/v1/ai/resumes", bearer,
                json(Map.of("title", "自查简历",
                        "data", Map.of("basics", Map.of("name", "张三", "phone", "", "email", "",
                                        "city", "", "links", List.of()),
                                "intent", Map.of("role", "", "city", "", "availability", ""),
                                "education", List.of(),
                                "work", List.of(Map.of("company", "某公司", "title", "后端",
                                        "start", "2023.07", "end", "至今", "summary", "",
                                        "bullets", List.of("把接口响应从 800ms 降到 200ms"))),
                                "projects", List.of(),
                                "skills", List.of(), "awards", List.of(), "selfEvaluation", ""),
                        "plainText", "张三 某公司 后端 2023.07 - 至今 把接口响应从 800ms 降到 200ms")));
        long resumeId = idAt(resumeDetail);
        call("简历列表", "GET", "/api/v1/ai/resumes", bearer, null);
        call("读简历", "GET", "/api/v1/ai/resumes/" + resumeId, bearer, null);
        call("改简历标题", "PUT", "/api/v1/ai/resumes/" + resumeId + "/title", bearer,
                json(Map.of("title", "自查简历-改")));
        call("设为默认", "PUT", "/api/v1/ai/resumes/" + resumeId + "/default", bearer, null);
        call("体检", "GET", "/api/v1/ai/resumes/" + resumeId + "/checkup", bearer, null);
        MvcResult resumeSaved = call("保存简历", "PUT", "/api/v1/ai/resumes/" + resumeId, bearer,
                json(Map.of("title", "自查简历-改", "revision", 1,
                        "data", Map.of("basics", Map.of("name", "张三", "phone", "", "email", "",
                                        "city", "", "links", List.of()),
                                "intent", Map.of("role", "", "city", "", "availability", ""),
                                "education", List.of(),
                                "work", List.of(Map.of("company", "某公司", "title", "后端",
                                        "start", "2023.07", "end", "至今", "summary", "",
                                        "bullets", List.of("把接口响应从 800ms 降到 200ms"))),
                                "projects", List.of(),
                                "skills", List.of(), "awards", List.of(), "selfEvaluation", ""))));
        call("编辑历史", "GET", "/api/v1/ai/resumes/" + resumeId + "/history", bearer, null);
        MvcResult resumeHistory = call("编辑历史（取列表）", "GET",
                "/api/v1/ai/resumes/" + resumeId + "/history", bearer, null, 200);
        long historyId = firstIdAt(resumeHistory);
        call("读某一版", "GET", "/api/v1/ai/resumes/" + resumeId + "/history/" + historyId, bearer, null);
        MvcResult restored = call("回滚到某一版", "POST",
                "/api/v1/ai/resumes/" + resumeId + "/history/" + historyId + "/restore?revision=2",
                bearer, null, 200);
        long revisionAfterRestore = longAt(restored, "$.data.revision");

        MvcResult versionCreated = call("建岗位版本", "POST",
                "/api/v1/ai/resumes/" + resumeId + "/versions", bearer,
                json(Map.of("company", "某公司", "role", "后端", "jdText",
                        "岗位描述：熟悉 Java 与高并发，熟悉 Kubernetes 优先，三年以上经验。")));
        long versionId = idAt(versionCreated);
        call("版本列表", "GET", "/api/v1/ai/resumes/" + resumeId + "/versions", bearer, null);
        call("分析岗位（桩）", "POST", "/api/v1/ai/versions/" + versionId + "/analysis", bearer, null);
        MvcResult proposed = call("生成提案（桩）", "POST",
                "/api/v1/ai/versions/" + versionId + "/proposals", bearer, null);
        long proposalId = firstIdAt(proposed);
        call("提案列表", "GET", "/api/v1/ai/versions/" + versionId + "/proposals", bearer, null);
        call("采纳一条提案", "PATCH", "/api/v1/ai/proposals/" + proposalId, bearer,
                json(Map.of("accepted", true)));
        call("定稿", "POST", "/api/v1/ai/versions/" + versionId + "/finalize", bearer, null);
        call("读版本", "GET", "/api/v1/ai/versions/" + versionId, bearer, null);
        call("删版本", "DELETE", "/api/v1/ai/versions/" + versionId, bearer, null);
        call("删简历", "DELETE", "/api/v1/ai/resumes/" + resumeId, bearer, null);
        stepOk("回滚后的 revision=" + revisionAfterRestore + "（保存与回滚都各推进一次）");

        // ---- 训练营（WORK-029）----
        when(campGenerator.diagnostic(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new CampItemGenerator.Generated(
                        campItems("集合", "并发"), "{}", new TokenUsage(10, 20, 30)));
        // 复测卷的题干必须与诊断卷不同，否则会被同卷去重全过滤掉（`kept.isEmpty()` 直接失败）
        when(campGenerator.retest(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new CampItemGenerator.Generated(
                        campItems("集合", "并发").stream()
                                .map(item -> new ItemValidator.RawItem(item.topic(), item.conceptName(),
                                        item.difficulty(), "复测自查：" + item.stem(), item.options(),
                                        item.answerIndex(), item.explanation()))
                                .toList(),
                        "{}", new TokenUsage(10, 20, 30)));
        // 上面那份自查简历已经被删掉，训练营要自己再建一份带技能区的
        MvcResult campResume = call("训练营用的简历", "POST", "/api/v1/ai/resumes", bearer,
                json(Map.of("title", "训练营自查简历", "plainText", "张三 Java MySQL",
                        "data", Map.of("basics", Map.of("name", "张三", "phone", "13800001111",
                                        "email", "z@example.test", "city", "杭州", "links", List.of()),
                                "intent", Map.of("role", "Java 后端", "city", "杭州", "availability", ""),
                                "education", List.of(), "work", List.of(), "projects", List.of(),
                                "skills", List.of(Map.of("group", "技术栈",
                                        "items", List.of("Java", "MySQL"))),
                                "awards", List.of(), "selfEvaluation", ""))), 200);
        long resumeIdForCamp = idAt(campResume);
        MvcResult campCreated = call("开训练营（桩）", "POST", "/api/v1/ai/camps", bearer,
                json(Map.of("resumeDocumentId", resumeIdForCamp, "days", 3)), 200);
        long campId = idAt(campCreated);
        call("我的训练营", "GET", "/api/v1/ai/camps", bearer, null);
        call("营概览", "GET", "/api/v1/ai/camps/" + campId, bearer, null);
        MvcResult campItemsResult = call("取诊断卷", "GET",
                "/api/v1/ai/camps/" + campId + "/items?form=diagnostic", bearer, null, 200);
        java.util.List<Integer> itemIds = JsonPath.read(body(campItemsResult), "$.data[*].id");
        call("交诊断卷", "POST", "/api/v1/ai/camps/" + campId + "/submit", bearer,
                json(Map.of("form", "diagnostic", "answers",
                        itemIds.stream().map(id -> Map.of("itemId", id, "choice", 1)).toList())), 200);
        call("诊断结果", "GET", "/api/v1/ai/camps/" + campId + "/result?form=diagnostic", bearer, null);
        call("出计划", "POST", "/api/v1/ai/camps/" + campId + "/plan", bearer, null, 200);
        MvcResult campPlan = call("读计划", "GET", "/api/v1/ai/camps/" + campId + "/plan", bearer, null, 200);
        long campTaskId = longAt(campPlan, "$.data.dayPlans[0].tasks[0].id");
        call("勾选任务", "PATCH", "/api/v1/ai/camps/" + campId + "/tasks/" + campTaskId, bearer,
                json(Map.of("done", true)));
        call("复测出卷（桩）", "POST", "/api/v1/ai/camps/" + campId + "/retest", bearer, null, 200);
        MvcResult campRetest = call("取复测卷", "GET",
                "/api/v1/ai/camps/" + campId + "/items?form=retest", bearer, null, 200);
        java.util.List<Integer> retestIds = JsonPath.read(body(campRetest), "$.data[*].id");
        call("交复测卷", "POST", "/api/v1/ai/camps/" + campId + "/submit", bearer,
                json(Map.of("form", "retest", "answers",
                        retestIds.stream().map(id -> Map.of("itemId", id, "choice", 1)).toList())), 200);
        call("复测结果（并排）", "GET", "/api/v1/ai/camps/" + campId + "/result?form=retest", bearer, null);
        call("删营", "DELETE", "/api/v1/ai/camps/" + campId, bearer, null);

        call("管理端用量流水", "GET", "/api/v1/ai/admin/credits/audit", adminToken(), null);
        call("管理端用量流水（分页筛选）", "GET",
                "/api/v1/ai/admin/credits/ledger?page=1&size=10", adminToken(), null);
        call("管理端查某人积分", "GET", "/api/v1/ai/admin/credits/" + userId, adminToken(), null);

        // ---- 用户管理（WORK-030）----
        // 造一个一次性账号来验四条写路径；删号放在最后，正好也验了级联。
        String managedSuffix = Long.toString(System.nanoTime() % 1_000_000L);
        jdbcTemplate.update(
                "INSERT INTO users (username, email, password_hash, role, email_verified_at)"
                        + " VALUES (?, ?, ?, 'learner', CURRENT_TIMESTAMP(3))",
                "walk-" + managedSuffix, "walk-" + managedSuffix + "@example.test", "$2a$10$placeholder");
        long managedId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM users", Long.class);
        call("管理端用户列表", "GET", "/api/v1/account/admin/users?page=1&size=10", adminToken(), null);
        call("管理端用户详情", "GET", "/api/v1/account/admin/users/" + managedId, adminToken(), null);
        call("管理端改角色", "POST", "/api/v1/account/admin/users/" + managedId + "/role", adminToken(),
                json(Map.of("role", "admin")));
        call("管理端调积分（用户管理）", "POST", "/api/v1/account/admin/users/" + managedId + "/credits",
                adminToken(), json(Map.of("delta", 10, "note", "自查调分")));
        call("管理端删号", "DELETE", "/api/v1/account/admin/users/" + managedId, adminToken(), null);
        call("管理端调分", "POST", "/api/v1/ai/admin/credits/" + userId + "/adjust", adminToken(),
                json(Map.of("delta", 100, "note", "自查调分")));

        // ---- AI 调用统计（WORK-022）----
        // **先造一条确定的失败记录**：概览里的 errorTypes / byKind 是"有哪些值"的数组，
        // 空数组与有元素是两种形状。不造数据的话，快照会在"单跑有、全量无"之间来回漂
        // （2026-10-19 实测）。写一行带 error_type 的入档记录比依赖前面的用例更稳。
        jdbcTemplate.update("""
                INSERT INTO ai_runs (trace_id, user_id, kind, status, error_type, started_at,
                                     duration_ms, input_tokens, output_tokens)
                VALUES (?, ?, 'chat', 'failed', 'INVALID_ARGUMENT', ?, 120, 10, 0)
                """, "selfcheck" + suffix, userId, java.time.Instant.now());
        call("AI 调用概览", "GET", "/api/v1/ai/admin/runs/overview", adminToken(), null);
        call("AI 调用明细", "GET", "/api/v1/ai/admin/runs", adminToken(), null);
        call("AI 调用筛选项", "GET", "/api/v1/ai/admin/runs/meta", adminToken(), null);
        // WORK-036：趋势序列、采集健康、账实对账。三者都只读，不需要预置数据。
        call("AI 趋势序列", "GET", "/api/v1/ai/admin/runs/series?days=7", adminToken(), null);
        call("AI 采集健康", "GET", "/api/v1/ai/admin/runs/health", adminToken(), null);
        call("AI 账实对账", "GET", "/api/v1/ai/admin/runs/reconcile?days=7", adminToken(), null);
        // 详情要求一个真实 traceId；上面那轮对话已经写了一条（采集是异步的，等一下）
        String traceId = null;
        for (int attempt = 0; attempt < 20 && traceId == null; attempt++) {
            traceId = jdbcTemplate.query(
                    "SELECT trace_id FROM ai_runs WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null, userId);
            if (traceId == null) {
                Thread.sleep(100);
            }
        }
        if (traceId != null) {
            call("AI 调用详情", "GET", "/api/v1/ai/admin/runs/" + traceId, adminToken(), null);
        }
        // 回答反馈（WORK-036 C 组）：写一条再读管理端列表。traceId 可能为 null（采集关闭），
        // 那时唯一键退化成不生效，写入仍然合法。
        bestEffort("POST", "/api/v1/ai/feedback", bearer);
        call("管理端回答反馈", "GET", "/api/v1/ai/feedback/admin", adminToken(), null);

        // ---- SQL 数据集（WORK-035）----
        // 上传需要真文件与白名单校验，自查不打这条路；其余端点用一个不存在的 key
        // 走 404 分支——端点被走到、鉴权生效、错误语义正确，这就是覆盖要的东西。
        call("我的数据集", "GET", "/api/v1/sql-datasets/mine", bearer, null);
        bestEffort("POST", "/api/v1/sql-datasets", bearer);
        bestEffort("POST", "/api/v1/sql-datasets/generate", bearer);
        bestEffort("DELETE", "/api/v1/sql-datasets/__nonexistent__", bearer);
        bestEffort("POST", "/api/v1/sql-datasets/__nonexistent__/publish", bearer);
        bestEffort("POST", "/api/v1/sql-datasets/__nonexistent__/report", bearer);
        bestEffort("POST", "/api/v1/sql-datasets/__nonexistent__/question", bearer);
        call("数据集表结构", "GET", "/api/v1/sandbox/datasets/__nonexistent__/schema", bearer, null,
                org.springframework.http.HttpStatus.OK.value(),
                org.springframework.http.HttpStatus.NOT_FOUND.value());
        call("管理端数据集", "GET", "/api/v1/sandbox/admin/sql-datasets", adminToken(), null);
        call("管理端数据集举报", "GET", "/api/v1/sandbox/admin/sql-datasets/reports",
                adminToken(), null);
        bestEffort("POST", "/api/v1/sandbox/admin/sql-datasets/__nonexistent__/take-down",
                adminToken());
        bestEffort("POST", "/api/v1/sandbox/admin/sql-datasets/__nonexistent__/restore",
                adminToken());

        // ---- 沙箱 ----
        call("沙箱状态", "GET", "/api/v1/sandbox/status", bearer, null);
        // 逐步录制（WORK-023）：本地测试里沙箱通常没开，这条走的是"不可用"分支——
        // 那仍然是有效覆盖（端点存在、鉴权生效、错误语义正确）。
        call("逐步录制", "POST", "/api/v1/sandbox/trace", bearer,
                json(Map.of("language", "python", "code", "x = 1")), 503);
        MvcResult sandbox = mvc.perform(MockMvcRequestBuilders.post("/api/v1/sandbox/run")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("language", "java", "code", "System.out.println(1);")))).andReturn();
        // 没开沙箱时返回 503（这是设计），开了则 200——两种都算"接口可达"
        record("运行代码", "POST", "/api/v1/sandbox/run", sandbox.getResponse().getStatus(),
                sandbox.getResponse().getStatus() == 503 ? 503 : 200, null);
        noteTouched("POST", "/api/v1/sandbox/run");

        // ---- 登出：吊销 refresh，但 access 仍在有效期内（JWT 固有取舍）----
        call("登出", "POST", "/api/v1/auth/logout", null, json(Map.of("refreshToken", refresh)));

        // ---- 收尾：注销（它自己也是一条要验的接口）----
        call("注销账号", "POST", "/api/v1/me/deletion", bearer,
                json(Map.of("password", PASSWORD)));
        // 已知取舍（见 TokenService 类注释）：access 是无状态 JWT，**注销后它在自己的有效期内仍然有效**。
        // 所以这里不断言 401，而是断言"refresh 已经不能用"——那才是注销真正切断的东西。
        MvcResult afterDeletion = call("注销后 access 仍可用（JWT 固有取舍）", "GET", "/api/v1/me", bearer, null);
        stepOk("注销后 access 仍返回 " + afterDeletion.getResponse().getStatus() + "（预期行为，不是缺陷）");
        call("注销后 refresh 失效", "POST", "/api/v1/auth/refresh", null,
                json(Map.of("refreshToken", refresh3)), 401);

        // ---- 清理知识库夹具 ----
        bestEffort("DELETE", "/api/v1/knowledge/admin/concepts/" + conceptId, adminToken());
        bestEffort("DELETE", "/api/v1/knowledge/admin/entries/" + entryId2, adminToken());
        bestEffort("DELETE", "/api/v1/knowledge/admin/entries/" + entryId, adminToken());
        bestEffort("DELETE", "/api/v1/knowledge/admin/categories/" + categoryId, adminToken());

        // ---- 运维摘要（WORK-028）：只读；Prometheus 不可达时全 UNKNOWN 是设计行为 ----
        call("运维摘要", "GET", "/api/v1/ops/admin/summary", adminToken(), null);
        // 反代路径只在 Grafana 活着时才 200，所以用 bestEffort 打一下让"全接口自查"覆盖到它。
        // 目标不可达时本接口返回 502（这是设计），不算失败。
        bestEffort("GET", "/api/v1/ops/grafana/api/health", adminToken());
        // 面板的图线走 Grafana 的 POST 查询接口（唯一的 POST 例外，语义仍是只读）
        bestEffort("POST", "/api/v1/ops/grafana/api/ds/query", adminToken());

        // ---- 数据大屏、运维诊断与事件（WORK-042 / WORK-043 / WORK-045）----
        // 这几条自 2026-10-27 起就没被走到，于是"全接口自查"一直是红的。
        //
        // 有真实 id 才能成功的（单条报告、事件详情、补备注、评分）**用不存在的 id**：
        // 端点被走到、404/409 不影响结论——与上面速递删除同一条口径。
        // 诊断默认是关闭的，所以 POST 会返回 409 而不是 202，同样交给 bestEffort。
        call("运维数据总览", "GET", "/api/v1/ops/admin/analytics/overview", adminToken(), null);
        call("诊断列表", "GET", "/api/v1/ops/admin/diagnoses", adminToken(), null);
        call("诊断统计", "GET", "/api/v1/ops/admin/diagnoses/stats", adminToken(), null);
        bestEffort("GET", "/api/v1/ops/admin/diagnoses/999999999", adminToken());
        bestEffort("POST", "/api/v1/ops/admin/diagnoses", adminToken());
        bestEffort("POST", "/api/v1/ops/admin/diagnoses/999999999/feedback", adminToken());
        call("事件列表", "GET", "/api/v1/ops/admin/incidents", adminToken(), null);
        bestEffort("GET", "/api/v1/ops/admin/incidents/999999999", adminToken());
        bestEffort("POST", "/api/v1/ops/admin/incidents/999999999/notes", adminToken());
        // Grafana 告警进来的入口：不带 X-Paideia-Ops-Token 就是 401，这正是设计。
        // 这里只要求"端点被打到"，不要求它能成功。
        bestEffort("POST", "/api/v1/ops/hooks/grafana", null);

        // ---- 每日速递（WORK-027）----
        // 读接口在学习者端；管理端两个走 ADMIN。抓取由定时任务做，自查不触发。
        call("速递当日", "GET", "/api/v1/digest", bearer, null);
        call("速递日期列表", "GET", "/api/v1/digest/dates", bearer, null);
        call("速递周报", "GET", "/api/v1/digest/weekly", bearer, null);
        MvcResult digestItems = call("管理端速递清单", "GET", "/api/v1/digest/admin/items",
                adminToken(), null);
        // 删除端点要有条真实 id 才能打到；今天没有速递内容时跳过（接口本身仍被
        // 上面的清单命中，覆盖度不受影响）。有内容时删掉一条，顺便验证硬删语义。
        String digestDeleteId = firstIdOrNull(digestItems);
        if (digestDeleteId != null) {
            call("管理端删速递", "DELETE", "/api/v1/digest/admin/items/" + digestDeleteId,
                    adminToken(), null);
        } else {
            // 没有数据时用 bestEffort 打一次不存在的 id：端点被走到，404 不影响结论
            bestEffort("DELETE", "/api/v1/digest/admin/items/999999999", adminToken());
        }
        // 点击埋点（数据大屏的阅读量来源）。同样用不存在的 id：只要求端点到。
        bestEffort("POST", "/api/v1/digest/items/999999999/click", bearer);


        } catch (RuntimeException | AssertionError failure) {
            // 单步异常不中断整轮：把这一条记下来，最后连同其余失败一起报
            steps.add(new Step("中断于：" + failure.getClass().getSimpleName(), "-", "-", 0, false,
                    String.valueOf(failure.getMessage())));
            failure.printStackTrace();
        }

        assertFullCoverage();
        assertShapeMatchesSnapshot();
        writeReport();
        assertFailures();
    }

    // ---- 调用与记录 ----

    private MvcResult call(String name, String method, String path, String bearer, String body,
                           int... expectedStatuses) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method), path)
                .with(request -> {
                    request.setRemoteAddr(sourceAddress);
                    return request;
                })
                .header("Authorization", bearer == null ? new String[0] : new String[] {bearer})
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8)))
                .andReturn();
        int[] expected = expectedStatuses.length == 0 ? new int[] {200} : expectedStatuses;
        record(name, method, path, result.getResponse().getStatus(), expected, result);
        noteTouched(method, path);
        return result;
    }

    private void record(String name, String method, String path, int actual, int expected, MvcResult result) {
        boolean ok = actual == expected;
        String note = result == null ? "" : firstErrorCode(result);
        steps.add(new Step(name, method, path, actual, ok, note));
        captureShape(method, path, actual, result);
    }

    private void record(String name, String method, String path, int actual, int[] expected, MvcResult result) {
        boolean ok = java.util.Arrays.stream(expected).anyMatch(value -> value == actual);
        String note = result == null ? "" : firstErrorCode(result);
        steps.add(new Step(name, method, path, actual, ok, note));
        captureShape(method, path, actual, result);
    }

    /**
     * 记下这次成功响应的结构。
     *
     * <p>只对 2xx 且是 JSON 的响应做：SSE（text/event-stream）、二进制附件、302 跳转都不参与。
     * 同一个端点在自查里被调多次时，取**第一次成功**的那次——后面的调用可能拿到不同的数据形态
     * （例如"读精简"第一次是 null、生成之后是对象），以第一次为基准才稳定。
     */
    private void captureShape(String method, String path, int status, MvcResult result) {
        if (result == null || status < 200 || status >= 300) {
            return;
        }
        String contentType = result.getResponse().getContentType();
        if (contentType == null || !contentType.contains("json")) {
            return;
        }
        String key = method + " " + canonicalPath(method, path);
        if (shapes.containsKey(key)) {
            return;
        }
        try {
            String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return;
            }
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
            java.util.List<String> lines = new java.util.ArrayList<>();
            flatten("", root, lines);
            java.util.Collections.sort(lines);
            shapes.put(key, lines);
        } catch (Exception ignored) {
            // 结构读不出来就不参与快照，不影响自查结论
        }
    }

    /** 把 JSON 树压成 "路径: 类型" 的平铺行；数组只看第一个元素的结构。 */
    private static void flatten(String prefix, com.fasterxml.jackson.databind.JsonNode node,
                                java.util.List<String> out) {
        if (node == null || node.isNull()) {
            out.add(prefix + ": null");
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry ->
                    flatten(prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(),
                            entry.getValue(), out));
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                out.add(prefix + "[]: empty");
                return;
            }
            flatten(prefix + "[]", node.get(0), out);
            return;
        }
        out.add(prefix + ": " + node.getNodeType().name().toLowerCase());
    }

    /** 实际请求路径对回注册 pattern；对不上就原样返回。 */
    private String canonicalPath(String method, String path) {
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        return ApiSurfaceTest.endpoints(handlerMapping).stream()
                .filter(endpoint -> endpoint.method().equals(method))
                .map(ApiSurfaceTest.Endpoint::pattern)
                .distinct()
                .sorted(ApiSurfaceTest.bySpecificity())
                .filter(pattern -> pattern.equals(bare) || ApiSurfaceTest.matches(pattern, bare))
                .findFirst()
                .orElse(bare);
    }

    /**
     * 与提交在仓库里的结构快照比对。
     *
     * <p>快照在 `paideia-app/src/test/resources/api-shape.json` —— **它是代码的一部分**，
     * 会随提交被评审。字段改名、增删、改类型都会让它对不上，从而红。
     *
     * <p>确认变更是**有意的**之后，用 `-Dapi.shape.update=true` 重跑一次更新快照，
     * 然后 review 那份 diff —— 那份 diff 就是"这次接口契约到底变了什么"的清单。
     */
    private void assertShapeMatchesSnapshot() {
        Path snapshot = Path.of("src/test/resources/api-shape.json");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode current = mapper.createObjectNode();
        shapes.forEach((key, lines) -> current.set(key, mapper.valueToTree(lines)));

        try {
            if (Boolean.getBoolean("api.shape.update") || !Files.exists(snapshot)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(snapshot.toFile(), current);
                System.out.println("[api-shape] 已写入快照：" + snapshot.toAbsolutePath()
                        + "（请 review 这份 diff 再提交）");
                return;
            }

            com.fasterxml.jackson.databind.JsonNode expected = mapper.readTree(snapshot.toFile());
            java.util.List<String> diffs = new java.util.ArrayList<>();
            java.util.Set<String> keys = new java.util.TreeSet<>();
            current.fieldNames().forEachRemaining(keys::add);
            expected.fieldNames().forEachRemaining(keys::add);

            for (String key : keys) {
                java.util.List<String> now = linesOf(current.get(key));
                java.util.List<String> before = linesOf(expected.get(key));
                if (before.isEmpty() && !now.isEmpty()) {
                    diffs.add("+ 新端点 " + key);
                } else if (!before.isEmpty() && now.isEmpty()) {
                    diffs.add("- 端点消失 " + key);
                } else if (!now.equals(before)) {
                    java.util.Set<String> added = new java.util.TreeSet<>(now);
                    added.removeAll(before);
                    java.util.Set<String> removed = new java.util.TreeSet<>(before);
                    removed.removeAll(now);
                    added.forEach(line -> diffs.add("+ " + key + " -> " + line));
                    removed.forEach(line -> diffs.add("- " + key + " -> " + line));
                }
            }

            assertThat(diffs)
                    .as("接口响应结构与提交的快照不一致。如果这是有意的契约变更："
                            + " 1) 用 -Dapi.shape.update=true 重跑一次更新快照；"
                            + " 2) review src/test/resources/api-shape.json 的 diff；"
                            + " 3) 与前端改动一起提交。"
                            + " 如果不是有意的，那就是这次改动破坏了既有契约。")
                    .isEmpty();
        } catch (java.io.IOException exception) {
            throw new AssertionError("读写接口结构快照失败：" + exception.getMessage(), exception);
        }
    }

    private static java.util.List<String> linesOf(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) {
            return java.util.List.of();
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        node.forEach(item -> lines.add(item.asText()));
        java.util.Collections.sort(lines);
        return lines;
    }

    /**
     * 把实际请求的路径对回运行时注册的 pattern（{@code /notes/17} → {@code /notes/{id}}）。
     *
     * <p>没有它，"全接口自查"就只是个说法——谁知道漏了哪条。
     */
    private void noteTouched(String method, String path) {
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;

        // **必须先试精确匹配**：`/credits/audit` 与 `/credits/{userId}` 都能匹配
        // `/credits/audit`，按注册顺序遍历会随机归到通配那条上——审计端点于是永远显示"没走到"
        // （2026-10-18 合跑时偶发、单跑不复现，就是这个原因）。
        // 排序：变量越少越具体，0 个变量的字面量路径排最前。
        java.util.List<String> patterns = ApiSurfaceTest.endpoints(handlerMapping).stream()
                .filter(endpoint -> endpoint.method().equals(method))
                .map(ApiSurfaceTest.Endpoint::pattern)
                .distinct()
                .sorted(ApiSurfaceTest.bySpecificity())
                .toList();

        for (String pattern : patterns) {
            if (pattern.equals(bare) || ApiSurfaceTest.matches(pattern, bare)) {
                touched.add(method + " " + pattern);
                return;
            }
        }
    }

    /** 运行时注册的每个端点都必须被这轮自查真的打到。 */
    private void assertFullCoverage() {
        java.util.Set<String> all = ApiSurfaceTest.endpoints(handlerMapping).stream()
                .map(ApiSurfaceTest.Endpoint::key)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        all.removeAll(touched);
        assertThat(all)
                .as("这些端点这一轮没有被打到——全接口自查没有做到'全'")
                .isEmpty();
    }

    private void stepOk(String name) {
        steps.add(new Step(name, "-", "-", 0, true, ""));
    }

    private void bestEffort(String method, String path, String bearer) {
        try {
            mvc.perform(MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(method), path)
                    .header("Authorization", bearer));
            // 清理也是真的走了一遍接口，计入覆盖；但不进断言（失败不影响结论）
            noteTouched(method, path);
        } catch (Exception ignored) {
            // 清理失败不影响结论：这一条不是验收对象
        }
    }

    /** 管理端令牌：直接签发，不依赖种子账号（种子会与 devdata 漂移）。 */
    private String adminToken() {
        return "Bearer " + authPort.issue(new io.github.xiaou61.security.AuthPort.Subject("1",
                io.github.xiaou61.security.AuthPort.ROLE_ADMIN)).value();
    }

    @Autowired
    io.github.xiaou61.security.AuthPort authPort;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;

    /** 本轮真的打到的端点（METHOD + 规范化路径）。最后与运行时路由全集比对。 */
    private final java.util.Set<String> touched = new java.util.TreeSet<>();

    /**
     * 每个端点的**响应结构**（字段路径 + 类型，不含值），键为 {@code METHOD pattern}。
     *
     * <p>为什么需要它：69 步全绿只证明"调用成功"，证明不了"返回的东西还是原来那个形状"。
     * 把 {@code NoteView.title} 改名成 {@code heading} 后，前端会静默拿不到标题，
     * 而所有状态码断言仍然全过（2026-10-18 实测确认过这一点）。结构快照补的就是这一层。
     */
    private final java.util.Map<String, java.util.List<String>> shapes = new java.util.TreeMap<>();

    /**
     * 本轮用的来源地址。
     *
     * <p>发邮箱验证码有三道限流，其中一道是"同来源每小时"。MockMvc 的默认来源都是
     * 127.0.0.1，于是同一个 JVM 里跑第二遍就会被挡成 429——实测合跑时偶发过一次，
     * 单跑不复现。每轮取一个随机地址，让自查之间互不影响。
     */
    private final String sourceAddress = "10.77."
            + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 250) + "."
            + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 250);

    // ---- JSON 辅助 ----

    /** 训练营自查用的两主题各 2 题（共 4 题，够走完判分与计划两条链）。 */
    private static java.util.List<ItemValidator.RawItem> campItems(String first, String second) {
        java.util.List<ItemValidator.RawItem> list = new java.util.ArrayList<>();
        for (String topic : java.util.List.of(first, second)) {
            for (int index = 0; index < 2; index++) {
                list.add(new ItemValidator.RawItem(topic, "", "medium",
                        topic + " 第 " + index + " 道自查题：这个技术点在什么场景下会出问题？",
                        java.util.List.of("并发扩容时", "冷启动时", "序列化时", "编译阶段"), 0,
                        "并发扩容时是这道题的核心考点，选项一为正解。"));
            }
        }
        return list;
    }

    private static String json(Map<String, Object> values) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String stringAt(MvcResult result, String path) throws Exception {
        try {
            Object value = JsonPath.read(body(result), path);
            return value == null ? null : value.toString();
        } catch (RuntimeException exception) {
            throw new AssertionError("响应里没有 " + path + "：" + body(result), exception);
        }
    }

    /**
     * 自查用的一份最小 docx。
     *
     * <p>它只用来让抽取器产出"有文字层"的结果：解析器已被桩住，内容本身不参与断言。
     * 用 DOCX 而不是 PDF 是因为 app 的测试类路径上没有 PDFBox。
     */
    private static byte[] selfCheckDocx() {
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

    /** 列表响应的第一项 id（`$.data[0].id`）。与 {@link #idAt} 分开：那个读的是单个对象。 */
    private long firstIdAt(MvcResult result) throws Exception {
        Object nested = JsonPath.read(body(result), "$.data[0].id");
        return ((Number) nested).longValue();
    }

    /** 列表为空时返回 null（不抛）——给"有数据才删一条"这种可选步骤用。 */
    private String firstIdOrNull(MvcResult result) throws Exception {
        try {
            Object nested = JsonPath.read(body(result), "$.data[0].id");
            return String.valueOf(((Number) nested).longValue());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private long longAt(MvcResult result, String path) throws Exception {
        Object value = JsonPath.read(body(result), path);
        return ((Number) value).longValue();
    }

    private long idAt(MvcResult result) throws Exception {
        Object value = JsonPath.read(body(result), "$.data");
        if (value instanceof Number number) {
            return number.longValue();
        }
        Object nested = JsonPath.read(body(result), "$.data.id");
        return ((Number) nested).longValue();
    }

    private String firstErrorCode(MvcResult result) {
        try {
            String text = body(result);
            if (text.isBlank()) {
                return "";
            }
            Object code = JsonPath.read(text, "$.code");
            Object message = JsonPath.read(text, "$.message");
            return "code=" + code + " " + (message == null ? "" : message);
        } catch (Exception ignored) {
            return "";
        }
    }

    private long firstReportId(MvcResult result, String shareId) throws Exception {
        // JSONPath 把小整数解成 Integer，直接声明 List<Long> 会在取值处抛 ClassCastException
        List<Number> ids = JsonPath.read(body(result), "$.data[?(@.shareId=='" + shareId + "')].id");
        assertThat(ids).as("举报列表里找不到刚提交的分享 %s", shareId).isNotEmpty();
        return ids.get(0).longValue();
    }

    // ---- 报告与断言 ----

    private void assertFailures() {
        List<Step> failed = steps.stream().filter(step -> !step.ok()).toList();
        if (failed.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("有 " + failed.size() + " 条接口没通过：\n");
        for (Step step : failed) {
            message.append("  ").append(step.method()).append(' ').append(step.path())
                    .append("  → ").append(step.status()).append(' ').append(step.note()).append('\n');
        }
        assertThat(failed).as(message.toString()).isEmpty();
    }

    /**
     * 把这一轮写进台账。
     *
     * <p>写在 {@code .agent/testing/runs/} 下（时间戳命名，追加不覆盖）——线上或本地每跑一次
     * 就多一份记录，{@code ls} 即时间线。路径从当前 Maven 模块反推仓库根；找不到就只打印不写，
     * 不让"报告写不出来"变成测试失败。
     */
    private void writeReport() {
        long passed = steps.stream().filter(Step::ok).count();
        long failed = steps.size() - passed;
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm"));
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder markdown = new StringBuilder();
        markdown.append("---\n");
        markdown.append("artifact: test-run\n");
        markdown.append("run_id: ").append(stamp).append('\n');
        markdown.append("layer: walkthrough\n");
        markdown.append("target: local\n");
        markdown.append("status: ").append(failed == 0 ? "passed" : "failed").append('\n');
        markdown.append("started: ").append(timestamp).append('\n');
        markdown.append("---\n\n");
        markdown.append("# 全接口自查 ").append(timestamp).append("\n\n");
        markdown.append("合计 **").append(steps.size()).append("** 步：通过 ").append(passed)
                .append("，失败 ").append(failed).append("；覆盖端点 **").append(touched.size())
                .append("** 条。\n\n");
        markdown.append("| # | 检查项 | 方法 | 路径 | 状态码 | 结果 |\n");
        markdown.append("| --- | --- | --- | --- | --- | --- |\n");
        int index = 1;
        for (Step step : steps) {
            markdown.append("| ").append(index++).append(" | ").append(step.name())
                    .append(" | ").append(step.method())
                    .append(" | `").append(step.path()).append("`")
                    .append(" | ").append(step.status())
                    .append(" | ").append(step.ok() ? "通过" : "失败 " + step.note())
                    .append(" |\n");
        }

        String text = markdown.toString();
        System.out.println(text);
        try {
            Path runs = Path.of(System.getProperty("user.dir")).resolve("../../.agent/testing/runs").normalize();
            if (Files.isDirectory(runs)) {
                Files.writeString(runs.resolve(stamp + "-walkthrough-local.md"), text,
                        StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 报告写不出来不该让自查失败
        }
    }
}
