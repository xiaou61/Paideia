package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * 概念图谱的集成测试（WORK-021）。
 *
 * <p>钉住的是"接口能返回什么、以及绝不能返回什么"：
 *
 * <ul>
 *   <li>浏览上报：一次一行，重复累加，草稿 404 且零行；</li>
 *   <li>图谱：一个概念挂两个条目时两条分数都进入；A 的证据不出现在 B 的图里；</li>
 *   <li>无载体的概念仍然出现；没有概念时返回空而不是报错；</li>
 *   <li>管理端：重名 409 语义（400）、自环 400、删概念连带清空关联。</li>
 * </ul>
 *
 * <p>用 111 / 112 两个令牌主体（与自评、学习记录同一套），种子 id 现查不写死。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class ConceptGraphIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthPort authPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SeedIds seeds;
    private long conceptId;

    @BeforeEach
    void setUp() {
        seeds = new SeedIds(jdbcTemplate);
        conceptId = createConcept("图谱测试概念-" + System.nanoTime());
    }

    @AfterEach
    void cleanUp() {
        if (conceptId > 0) {
            jdbcTemplate.update("DELETE FROM knowledge_concepts WHERE id = ?", conceptId);
            conceptId = -1;
        }
    }

    private String token(String subject) {
        return TestTokens.bearer(authPort, subject);
    }

    private String adminToken() {
        return TestTokens.bearer(authPort, "900", AuthPort.ROLE_ADMIN);
    }

    private long createConcept(String name) {
        try {
            String body = mockMvc.perform(post("/api/v1/knowledge/admin/concepts")
                            .header("Authorization", adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + name + "\",\"summary\":\"集成测试用\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data")).longValue();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void link(long concept, long entry) throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/admin/concepts/" + concept + "/entries")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryId\":" + entry + "}"))
                .andExpect(status().isOk());
    }

    private void view(String subject, long entry) throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/entries/" + entry + "/view")
                        .header("Authorization", token(subject)))
                .andReturn();
    }

    /** 清掉某个主体在某条目上的全部证据：测试之间共用同一个库，不清会互相污染。 */
    private void clearEvidence(String subject, long entry) {
        long userId = Long.parseLong(subject);
        jdbcTemplate.update("DELETE FROM learning_progress WHERE user_id = ? AND entry_id = ?", userId, entry);
        jdbcTemplate.update("DELETE FROM knowledge_entry_views WHERE user_id = ? AND entry_id = ?", userId, entry);
        jdbcTemplate.update("DELETE FROM knowledge_self_assessments WHERE user_id = ? AND entry_id = ?", userId, entry);
    }

    private void report(String subject, long entry, int score, int total) throws Exception {
        mockMvc.perform(put("/api/v1/learning/entries/" + entry)
                        .header("Authorization", token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challenges":{"c1":{"score":%d,"total":%d}},
                                 "quizzes":{"q1":{"score":%d,"total":%d}}}
                                """.formatted(score, total, score, total)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("浏览：一次一行，重复打开只累加不新增")
    void repeatedViewIncrementsSameRow() throws Exception {
        long entry = seeds.entry("什么是依赖注入");
        jdbcTemplate.update("DELETE FROM knowledge_entry_views WHERE entry_id = ?", entry);

        view("111", entry);
        view("111", entry);
        view("111", entry);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT view_count FROM knowledge_entry_views WHERE entry_id = ? AND user_id = 111",
                Integer.class, entry);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entry_views WHERE entry_id = ? AND user_id = 111",
                Integer.class, entry);

        assertThat(count).isEqualTo(3);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("浏览草稿条目返回 404，且不写行（不泄露草稿存在）")
    void viewingDraftIsNotFound() throws Exception {
        long draft = seeds.entry("虚拟线程的落地注意（草稿）");
        jdbcTemplate.update("DELETE FROM knowledge_entry_views WHERE entry_id = ?", draft);

        mockMvc.perform(post("/api/v1/knowledge/entries/" + draft + "/view")
                        .header("Authorization", token("111")))
                .andExpect(status().isNotFound());

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entry_views WHERE entry_id = ?", Integer.class, draft);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("一个概念挂两个条目时，两条分数都进入掌握度，不是只取一条")
    void scoreAveragesAcrossCarrierEntries() throws Exception {
        long first = seeds.entry("什么是依赖注入");
        long second = seeds.entry("线程与锁的基本认识");
        clearEvidence("112", first);
        clearEvidence("112", second);
        link(conceptId, first);
        link(conceptId, second);

        report("112", first, 3, 3);
        report("112", second, 0, 3);

        mockMvc.perform(get("/api/v1/learning/graph").header("Authorization", token("112")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.concepts[?(@.id == %d)].evidence.scoredEntries".formatted(conceptId))
                        .value(2))
                .andExpect(jsonPath("$.data.concepts[?(@.id == %d)].entries.length()".formatted(conceptId))
                        .value(2));
    }

    @Test
    @DisplayName("A 的自评与分数不出现在 B 的图里")
    void otherUsersEvidenceIsInvisible() throws Exception {
        long entry = seeds.entry("什么是依赖注入");
        clearEvidence("111", entry);
        clearEvidence("112", entry);
        link(conceptId, entry);

        report("111", entry, 3, 3);
        view("111", entry);

        mockMvc.perform(get("/api/v1/learning/graph").header("Authorization", token("112")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.concepts[?(@.id == %d)].evidence.scoredEntries".formatted(conceptId))
                        .value(0))
                .andExpect(jsonPath("$.data.concepts[?(@.id == %d)].evidence.viewed".formatted(conceptId))
                        .value(0));
    }

    @Test
    @DisplayName("没有载体条目的概念仍然出现在图里")
    void conceptWithoutEntriesStillListed() throws Exception {
        mockMvc.perform(get("/api/v1/learning/graph").header("Authorization", token("111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.concepts[?(@.id == %d)].tier".formatted(conceptId))
                        .value("unknown"));
    }

    @Test
    @DisplayName("管理端：重名概念被拒")
    void duplicateConceptNameRejected() throws Exception {
        String name = "重名测试-" + System.nanoTime();
        long first = createConcept(name);
        try {
            mockMvc.perform(post("/api/v1/knowledge/admin/concepts")
                            .header("Authorization", adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest());
        } finally {
            jdbcTemplate.update("DELETE FROM knowledge_concepts WHERE id = ?", first);
        }
    }

    @Test
    @DisplayName("管理端：概念边不能自环")
    void selfEdgeRejected() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/admin/concept-edges")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromConceptId":%d,"toConceptId":%d,"relationType":"related"}
                                """.formatted(conceptId, conceptId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("删除概念后关联与边一并消失")
    void deletingConceptCascadesLinksAndEdges() throws Exception {
        long entry = seeds.entry("什么是依赖注入");
        long other = createConcept("另一概念-" + System.nanoTime());
        link(conceptId, entry);
        mockMvc.perform(post("/api/v1/knowledge/admin/concept-edges")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromConceptId":%d,"toConceptId":%d,"relationType":"prerequisite"}
                                """.formatted(conceptId, other)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/knowledge/admin/concepts/" + conceptId)
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk());

        Integer links = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entry_concepts WHERE concept_id = ?", Integer.class, conceptId);
        Integer edges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_concept_edges WHERE from_concept_id = ? OR to_concept_id = ?",
                Integer.class, conceptId, conceptId);
        assertThat(links).isZero();
        assertThat(edges).isZero();

        jdbcTemplate.update("DELETE FROM knowledge_concepts WHERE id = ?", other);
        conceptId = -1;
    }
}
