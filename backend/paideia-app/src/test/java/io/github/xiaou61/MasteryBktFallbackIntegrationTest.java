package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BKT 紧急回退开关的**真实行为**（WORK-033 AC-010）。
 *
 * <p>这个类用 {@code @TestPropertySource} 把 `paideia.knowledge.mastery-bkt-enabled`
 * 关掉，然后验证一件事：**即使这个用户有逐题作答，图谱也不吃 BKT 的数**。
 *
 * <p>为什么不能只测属性读写：那样只能证明"字段能存取"，证明不了
 * "关掉之后 `ConceptGraphService` 真的不查 BKT 状态"。而后者才是 AC-010 要的东西。
 *
 * <p>对照组在 `ConceptGraphIntegrationTest`（开关默认开）。这里单独一个类是因为
 * `@TestPropertySource` 会改变整个 Spring 上下文——与那个类共用一个上下文会让
 * 它的 BKT 断言失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
@TestPropertySource(properties = "paideia.knowledge.mastery-bkt-enabled=false")
class MasteryBktFallbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthPort authPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String SUBJECT = "733";

    private long conceptId;
    private long entryId;

    @BeforeEach
    void setUp() throws Exception {
        entryId = new SeedIds(jdbcTemplate).entry("什么是依赖注入");

        String body = mockMvc.perform(post("/api/v1/knowledge/admin/concepts")
                        .header("Authorization", TestTokens.bearer(authPort, "900", AuthPort.ROLE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"回退开关测试-" + System.nanoTime()
                                + "\",\"summary\":\"测开关\"}"))
                .andReturn().getResponse().getContentAsString();
        conceptId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data")).longValue();

        // 概念挂到这个条目上，这样图谱里能看到它
        mockMvc.perform(post("/api/v1/knowledge/admin/concepts/" + conceptId + "/entries")
                        .header("Authorization", TestTokens.bearer(authPort, "900", AuthPort.ROLE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryId\":" + entryId + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        clearBkt();
    }

    @AfterEach
    void cleanUp() {
        clearBkt();
        if (conceptId > 0) {
            jdbcTemplate.update("DELETE FROM knowledge_concepts WHERE id = ?", conceptId);
            conceptId = -1;
        }
    }

    /** 清掉这个主体在这张概念上的 BKT 行，避免与别的用例互相污染。 */
    private void clearBkt() {
        long userId = Long.parseLong(SUBJECT);
        jdbcTemplate.update("DELETE FROM concept_mastery_state WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM question_attempts WHERE user_id = ?", userId);
    }

    /**
     * **AC-010 的核心断言**：开关关掉时，即使有 BKT 状态，图谱也不用它。
     *
     * <p>做法：往 `concept_mastery_state` 里塞一行掌握度 **1.0**，然后取图谱。
     * 如果开关无效，这个概念会显示 mastered（tier 为 mastered）；
     * 开关有效的话它走旧公式，没有其它证据 → 显示 unknown。
     */
    @Test
    @DisplayName("关掉开关后，BKT 状态不参与图谱的档位计算")
    void disabledSwitchIgnoresBktState() throws Exception {
        long userId = Long.parseLong(SUBJECT);
        jdbcTemplate.update("""
                        INSERT INTO concept_mastery_state (user_id, concept_id, mastery, attempts, correct, updated_at)
                        VALUES (?, ?, 1.0, 20, 20, NOW(3))
                        """, userId, conceptId);

        String body = mockMvc.perform(get("/api/v1/learning/graph")
                        .header("Authorization", TestTokens.bearer(authPort, SUBJECT)))
                .andReturn().getResponse().getContentAsString();

        // 只看**这个概念自己**的 tier。整段 body 里搜 "mastered" 会被别的概念
        // （库里可能本来就有已掌握的概念）干扰，那是假红。
        java.util.List<String> tiers = com.jayway.jsonpath.JsonPath.read(
                body, "$.data.concepts[?(@.id == " + conceptId + ")].tier");
        String tier = tiers.isEmpty() ? "（概念没出现在图谱里）" : tiers.get(0);
        assertThat(tier)
                .as("开关关闭时这个概念应回退旧公式（无其它证据 → unknown），实际 %s", tier)
                .isEqualTo("unknown");
    }
}
