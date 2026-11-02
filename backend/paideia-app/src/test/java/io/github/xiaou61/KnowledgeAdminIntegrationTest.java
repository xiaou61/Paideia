package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.security.AuthPort;
import org.junit.jupiter.api.BeforeEach;
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
 * 知识库管理端写端点的集成测试：角色三态 + 录入到发布的全链路。
 *
 * <p><b>三态是这里的重点</b>：匿名 401、学习者 403、管理员放行。只断言"管理员能写"是不够的——
 * 角色规则写反、甚至完全没生效时，那条断言**照样通过**，而越权写入就这么上线了。
 *
 * <p>数据自带唯一后缀（{@code System.nanoTime()}）并在末尾清理：种子用的是固定 id，
 * 而这个类会真的往题库里写东西，重跑时不能让上一次的残留把自己顶掉（slug 唯一索引）。
 *
 * <p><b>种子 id 现查，不写死</b>（2026-10-18 改）：涉及种子行的断言改按标题查（{@link SeedIds}），
 * 同库上别处删了种子行不会再让这里红。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class KnowledgeAdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthPort authPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SeedIds seeds;

    private final String suffix = String.valueOf(System.nanoTime());

    @BeforeEach
    void resolveSeedIds() {
        seeds = new SeedIds(jdbcTemplate);
    }

    private String learner() {
        return TestTokens.bearer(authPort, "1");
    }

    private String admin() {
        return TestTokens.bearer(authPort, "2", AuthPort.ROLE_ADMIN);
    }

    @Test
    void anonymousIsUnauthenticatedOnAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/admin/categories"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/knowledge/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"匿名\",\"slug\":\"anon\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void learnerIsForbiddenOnAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/admin/categories").header("Authorization", learner()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/knowledge/admin/categories")
                        .header("Authorization", learner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"学习者\",\"slug\":\"learner-attempt\"}"))
                .andExpect(status().isForbidden());
        // 写接口被拒，读接口仍然可用——证明这条规则限定了前缀而不是把整个知识库关掉
        mockMvc.perform(get("/api/v1/knowledge/entries").header("Authorization", learner()))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanCreatePublishAndThenLearnersCanRead() throws Exception {
        long categoryId = createCategory("管理端测试分类", "admin-test-" + suffix);
        long entryId = createEntry("管理端测试条目", "正文第一段\n\n## 小节\n\n第二段", categoryId,
                "draft");

        // 草稿：学习者列表里看不到，详情也是 404
        mockMvc.perform(get("/api/v1/knowledge/entries/" + entryId).header("Authorization", learner()))
                .andExpect(status().isNotFound());
        // 但管理员能预览到它，且拿到正文
        mockMvc.perform(get("/api/v1/knowledge/admin/entries/" + entryId).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("draft"))
                .andExpect(jsonPath("$.data.body").isNotEmpty());

        // 发布（就是一次把 status 置为 published 的 PUT）
        mockMvc.perform(put("/api/v1/knowledge/admin/entries/" + entryId)
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryJson("管理端测试条目", "正文第一段\n\n## 小节\n\n第二段", categoryId,
                                "published")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/knowledge/entries/" + entryId).header("Authorization", learner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("管理端测试条目"))
                .andExpect(jsonPath("$.data.categoryName").value("管理端测试分类"));

        // 建立关系：这条新条目是种子条目「模块化单体的边界」的前置
        long modularMonolith = seeds.entry("模块化单体的边界");
        String relation = mockMvc.perform(post("/api/v1/knowledge/admin/relations")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromEntryId\":" + entryId + ",\"toEntryId\":" + modularMonolith
                                + ",\"relationType\":\"prerequisite\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long relationId = ((Number) JsonPath.read(relation, "$.data")).longValue();

        mockMvc.perform(get("/api/v1/knowledge/entries/" + modularMonolith).header("Authorization", learner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prerequisites[?(@.id==" + entryId + ")]").exists());

        // 清理：先关系，再条目，最后分类（删除有子分类或条目的分类会被拒）
        mockMvc.perform(delete("/api/v1/knowledge/admin/relations/" + relationId)
                        .header("Authorization", admin()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/knowledge/admin/entries/" + entryId).header("Authorization", admin()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/knowledge/admin/categories/" + categoryId)
                        .header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    @Test
    void deletingCategoryWithEntriesIsRejected() throws Exception {
        long categoryId = createCategory("还有条目的分类", "admin-busy-" + suffix);
        long entryId = createEntry("占位条目", "正文", categoryId, "draft");

        mockMvc.perform(delete("/api/v1/knowledge/admin/categories/" + categoryId)
                        .header("Authorization", admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        mockMvc.perform(delete("/api/v1/knowledge/admin/entries/" + entryId).header("Authorization", admin()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/knowledge/admin/categories/" + categoryId)
                        .header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    @Test
    void deletingCategoryWithChildrenIsRejected() throws Exception {
        long parentId = createCategory("父分类", "admin-parent-" + suffix);
        long childId = createCategory("子分类", "admin-child-" + suffix, parentId);

        mockMvc.perform(delete("/api/v1/knowledge/admin/categories/" + parentId)
                        .header("Authorization", admin()))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/v1/knowledge/admin/categories/" + childId)
                        .header("Authorization", admin()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/knowledge/admin/categories/" + parentId)
                        .header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    @Test
    void movingCategoryUnderItsOwnDescendantIsRejected() throws Exception {
        long parentId = createCategory("成环父", "admin-cycle-p-" + suffix);
        long childId = createCategory("成环子", "admin-cycle-c-" + suffix, parentId);

        // 把父挂到子下面 = 成环，会让整棵子树从根不可达；外键对此毫无意见，必须应用层拦
        mockMvc.perform(put("/api/v1/knowledge/admin/categories/" + parentId)
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + childId + ",\"name\":\"成环父\",\"slug\":\"admin-cycle-p-"
                                + suffix + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/knowledge/admin/categories/" + childId).header("Authorization", admin()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/knowledge/admin/categories/" + parentId).header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    @Test
    void selfReferencingRelationIsRejected() throws Exception {
        long entry = seeds.entry("模块化单体的边界");

        mockMvc.perform(post("/api/v1/knowledge/admin/relations")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromEntryId\":" + entry + ",\"toEntryId\":" + entry
                                + ",\"relationType\":\"related\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateRelationIsRejected() throws Exception {
        // 种子里已经有「依赖注入 → 模块化单体」这条 prerequisite
        long from = seeds.entry("什么是依赖注入");
        long to = seeds.entry("模块化单体的边界");

        mockMvc.perform(post("/api/v1/knowledge/admin/relations")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromEntryId\":" + from + ",\"toEntryId\":" + to
                                + ",\"relationType\":\"prerequisite\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidStatusIsRejected() throws Exception {
        long entry = seeds.entry("什么是依赖注入");
        long category = seeds.category("backend-basics");

        mockMvc.perform(put("/api/v1/knowledge/admin/entries/" + entry)
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryJson("标题", "正文", category, "whatever")))
                .andExpect(status().isBadRequest());
    }

    private long createCategory(String name, String slug) throws Exception {
        return createCategory(name, slug, null);
    }

    private long createCategory(String name, String slug, Long parentId) throws Exception {
        String body = parentId == null
                ? "{\"name\":\"" + name + "\",\"slug\":\"" + slug + "\"}"
                : "{\"parentId\":" + parentId + ",\"name\":\"" + name + "\",\"slug\":\"" + slug + "\"}";
        String response = mockMvc.perform(post("/api/v1/knowledge/admin/categories")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.data")).longValue();
    }

    private long createEntry(String title, String body, Long categoryId, String entryStatus) throws Exception {
        String response = mockMvc.perform(post("/api/v1/knowledge/admin/entries")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryJson(title, body, categoryId, entryStatus)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.data")).longValue();
    }

    /** 正文里含换行，必须转义成 JSON 的 {@code \n}，否则请求体本身不合法。 */
    private static String entryJson(String title, String body, Long categoryId, String entryStatus) {
        String encodedBody = body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"title\":\"" + title + "\",\"body\":\"" + encodedBody + "\",\"categoryId\":"
                + (categoryId == null ? "null" : categoryId) + ",\"status\":\"" + entryStatus + "\"}";
    }
}
