package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.knowledge.KnowledgeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 八股文专区的入库与去重（WORK-025）。
 *
 * <p>验三件只有真库才能验的事：V24 的两列存在且可写、按指纹去重生效、学习者侧能看到来源链接。
 * 抓取与抽题不在这里——那两条需要外部站点与模型，属另一层验证。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class EssayCollectionIntegrationTest {

    private static final String SLUG = "essay-it-category";
    private static final String FINGERPRINT = "it-fingerprint-0001";

    @Autowired
    KnowledgeApi knowledge;

    @Autowired
    JdbcTemplate jdbc;

    long categoryId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM knowledge_entries WHERE fingerprint = ?", FINGERPRINT);
        jdbc.update("DELETE FROM knowledge_categories WHERE slug = ?", SLUG);
        jdbc.update("INSERT INTO knowledge_categories (name, slug, sort_order) VALUES (?, ?, 999)",
                "测试八股分类", SLUG);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM knowledge_categories WHERE slug = ?", Long.class, SLUG);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM knowledge_entries WHERE fingerprint = ?", FINGERPRINT);
        jdbc.update("DELETE FROM knowledge_categories WHERE slug = ?", SLUG);
    }

    @Test
    @DisplayName("自动采集写入后能按指纹查到，且带上来源链接")
    void writesCollectedEntryWithSourceUrl() {
        KnowledgeApi.CreateOutcome outcome = knowledge.createCollected(
                new KnowledgeApi.CollectedEntry(
                        "HashMap 为什么线程不安全", "## 解析\n\n因为并发扩容会形成环。",
                        categoryId, "published", FINGERPRINT,
                        "https://www.nowcoder.com/feed/main/detail/abc"));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.entryId()).isPositive();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_url, fingerprint, status FROM knowledge_entries WHERE id = ?",
                outcome.entryId());
        assertThat(row.get("source_url")).isEqualTo("https://www.nowcoder.com/feed/main/detail/abc");
        assertThat(row.get("fingerprint")).isEqualTo(FINGERPRINT);
        assertThat(row.get("status")).isEqualTo("published");
    }

    @Test
    @DisplayName("同一指纹重复写入只产生一条")
    void duplicateFingerprintDoesNotCreateTwice() {
        KnowledgeApi.CollectedEntry input = new KnowledgeApi.CollectedEntry(
                "HashMap 为什么线程不安全", "第一次", categoryId, "draft", FINGERPRINT, null);

        KnowledgeApi.CreateOutcome first = knowledge.createCollected(input);
        KnowledgeApi.CreateOutcome second = knowledge.createCollected(
                new KnowledgeApi.CollectedEntry(
                        "HashMap 为什么线程不安全（换个标题）", "第二次", categoryId, "draft",
                        FINGERPRINT, null));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.entryId()).isEqualTo(first.entryId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entries WHERE fingerprint = ?", Integer.class, FINGERPRINT))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("多方向的三个分类由迁移建好（前端 / 测试 / 数据）")
    void directionCategoriesAreCreatedByMigration() {
        List<String> slugs = knowledge.listCategories().stream()
                .map(KnowledgeApi.CategoryNode::slug)
                .toList();

        assertThat(slugs).contains("essay-frontend", "essay-testing", "essay-data");
    }

    @Test
    @DisplayName("同一指纹跨分类也只产生一条（多方向抓取防重复）")
    void sameFingerprintInAnotherCategoryDoesNotCreateTwice() {
        String otherSlug = "essay-it-category-2";
        jdbc.update("DELETE FROM knowledge_categories WHERE slug = ?", otherSlug);
        jdbc.update("INSERT INTO knowledge_categories (name, slug, sort_order) VALUES (?, ?, 998)",
                "测试八股分类二", otherSlug);
        Long otherId = jdbc.queryForObject(
                "SELECT id FROM knowledge_categories WHERE slug = ?", Long.class, otherSlug);
        try {
            KnowledgeApi.CreateOutcome first = knowledge.createCollected(new KnowledgeApi.CollectedEntry(
                    "HashMap 为什么线程不安全", "第一次", categoryId, "draft", FINGERPRINT, null));
            // 同一篇帖子被另一个方向的源再次抓到：指纹一样，只是目标分类不同。
            KnowledgeApi.CreateOutcome second = knowledge.createCollected(new KnowledgeApi.CollectedEntry(
                    "HashMap 为什么线程不安全", "第二次", otherId, "draft", FINGERPRINT, null));

            assertThat(first.created()).isTrue();
            assertThat(second.created()).isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_entries WHERE fingerprint = ?", Integer.class, FINGERPRINT))
                    .isEqualTo(1);
            // 留在先落地的分类里，不会被后一次悄悄搬家
            assertThat(jdbc.queryForObject(
                    "SELECT category_id FROM knowledge_entries WHERE fingerprint = ?", Long.class, FINGERPRINT))
                    .isEqualTo(categoryId);
        } finally {
            jdbc.update("DELETE FROM knowledge_entries WHERE fingerprint = ?", FINGERPRINT);
            jdbc.update("DELETE FROM knowledge_categories WHERE slug = ?", otherSlug);
        }
    }

    @Test
    @DisplayName("分类按 slug 可定位，且节点带 slug 供抓取任务查找")
    void categoryNodeCarriesSlug() {
        List<KnowledgeApi.CategoryNode> nodes = knowledge.listCategories();
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.slug()).isEqualTo(SLUG);
            assertThat(node.id()).isEqualTo(categoryId);
        });
    }
}
