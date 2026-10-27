package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.internal.digest.DigestMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * 速递的入库、去重与表结构（WORK-027 TASK-001/005）。
 *
 * <p>只验"真库才能验"的部分：两张表存在、指纹唯一、日期窗口查询、周报 upsert 幂等。
 * 抓取与 AI 压缩在单测层用桩验过，不在这里重复也**不连外网**。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class DigestIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    DigestMapper mapper;

    @Autowired
    io.github.xiaou61.ai.internal.digest.DigestCollectionService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM digest_items");
        jdbc.update("DELETE FROM digest_weeklies");
    }

    private void insert(String fingerprint, LocalDate date, String group) {
        mapper.insert(date, group, "test-source", "测试源", "标题 " + fingerprint, "摘要",
                "https://example.com/" + fingerprint, fingerprint, 10, null, null, null, null,
                1_800_000_000_000L, "ready", java.time.Instant.now());
    }

    @Test
    @DisplayName("TASK-001/WORK-041：两张表存在，指纹唯一键按日生效")
    void tablesAndKeysExist() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'digest_items'
                  AND index_name = 'uk_digest_items_edition_fingerprint'
                """, Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'digest_items'
                  AND index_name = 'uk_digest_items_fingerprint'
                """, Integer.class)).as("旧的全局唯一键必须已删除").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'digest_items'
                  AND index_name = 'idx_digest_items_edition'
                """, Integer.class)).isGreaterThan(0);
    }

    @Test
    @DisplayName("TASK-005：同指纹插两次只留一条（唯一约束兜住并发）")
    void duplicateFingerprintIsRejected() {
        insert("fp-dup", TODAY, "news");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> insert("fp-dup", TODAY, "news"))
                .satisfies(exception -> assertThat(String.valueOf(exception.getMessage()))
                        .as("应是唯一键冲突，而不是别的错误")
                        .containsIgnoringCase("Duplicate"));

        assertThat(mapper.findByDate(TODAY)).hasSize(1);
    }

    @Test
    @DisplayName("WORK-041：同一指纹可以跨天重复（热榜连续在榜）")
    void sameFingerprintOnDifferentDaysIsAllowed() {
        insert("fp-cross", TODAY, "github");
        insert("fp-cross", TODAY.plusDays(1), "github");

        assertThat(mapper.findByDate(TODAY)).hasSize(1);
        assertThat(mapper.findByDate(TODAY.plusDays(1))).hasSize(1);
    }

    @Test
    @DisplayName("WORK-041：GitHub 两列落库并随查询返回")
    void githubStarsAreStored() {
        mapper.insert(TODAY, "github", "github-trending", "GitHub 热榜", "owner/repo", "摘要",
                "https://github.com/owner/repo", "fp-stars", 250, 250, 850,
                "12345★", "Python", 1_800_000_000_000L, "ready", java.time.Instant.now());

        DigestMapper.Row row = mapper.findByDate(TODAY).get(0);
        assertThat(row.heat()).isEqualTo(250);
        assertThat(row.starsDay()).isEqualTo(250);
        assertThat(row.starsWeek()).isEqualTo(850);
    }

    @Test
    @DisplayName("TASK-005：日期窗口查询只返回窗口内的指纹")
    void existingFingerprintsRespectsWindow() {
        insert("fp-today", TODAY, "news");
        insert("fp-old", TODAY.minusDays(30), "news");

        List<String> found = mapper.existingFingerprints(TODAY.minusDays(1), TODAY.plusDays(1),
                List.of("fp-today", "fp-old"));

        assertThat(found).containsExactly("fp-today");
    }

    @Test
    @DisplayName("TASK-010/AC-016：周报同周 upsert 两次只有一行")
    void weeklyUpsertIsIdempotent() {
        LocalDate week = TODAY.minusDays(TODAY.getDayOfWeek().getValue() - 1);
        mapper.upsertWeekly(week, "第一版正文", 10, java.time.Instant.now());
        mapper.upsertWeekly(week, "第二版正文", 12, java.time.Instant.now());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM digest_weeklies", Integer.class))
                .isEqualTo(1);
        DigestMapper.WeeklyRow row = mapper.findWeekly(week);
        assertThat(row.body()).isEqualTo("第二版正文");
        assertThat(row.itemCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("TASK-010：没有周报时 findWeekly 返回 null（接口据此回 null 而不是 500）")
    void missingWeeklyReturnsNull() {
        assertThat(mapper.findWeekly(TODAY.minusWeeks(1))).isNull();
    }

    @Test
    @DisplayName("TASK-005：按日查询按 heat 降序，heat 为空的排后面")
    void findByDateOrdersByHeat() {
        mapper.insert(TODAY, "news", "s1", "源一", "无热度", "摘要", "https://example.com/n1",
                "fp-noheat", null, null, null, null, null, 1_000L, "ready", java.time.Instant.now());
        mapper.insert(TODAY, "news", "s2", "源二", "高热度", "摘要", "https://example.com/hi",
                "fp-hi", 999, null, null, null, null, 2_000L, "ready", java.time.Instant.now());
        mapper.insert(TODAY, "news", "s3", "源三", "低热度", "摘要", "https://example.com/lo",
                "fp-lo", 1, null, null, null, null, 3_000L, "ready", java.time.Instant.now());

        assertThat(mapper.findByDate(TODAY)).extracting(DigestMapper.Row::title)
                .containsExactly("高热度", "低热度", "无热度");
    }

    @Test
    @DisplayName("TASK-008：分组读取把未出现的分组也返回（前端据此渲染空态）")
    void groupedByDateIncludesEmptyGroups() {
        insert("fp-g", TODAY, "github");

        Map<String, List<DigestMapper.Row>> grouped = service.groupedByDate(TODAY);

        assertThat(grouped).containsKeys("news", "github", "community", "hackernews");
        assertThat(grouped.get("github")).hasSize(1);
        assertThat(grouped.get("news")).isEmpty();
    }

    @Test
    @DisplayName("TASK-011：删除单条后查不到")
    void deleteRemovesRow() {
        insert("fp-del", TODAY, "news");
        long id = mapper.findByDate(TODAY).get(0).id();

        assertThat(mapper.deleteById(id)).isEqualTo(1);
        assertThat(mapper.findByDate(TODAY)).isEmpty();
        assertThat(mapper.deleteById(id)).as("再删一次影响 0 行").isZero();
    }
}
