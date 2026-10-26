package io.github.xiaou61.ai.internal.digest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 速递两张表的 SQL（WORK-027）。 */
@Mapper
public interface DigestMapper {

    /** 一条入库行。 */
    record Row(long id, LocalDate editionDate, String groupKey, String sourceKey, String sourceName,
               String title, String summary, String url, String fingerprint, Integer heat,
               Integer starsDay, Integer starsWeek, String meta, Long publishedAt,
               String summaryState, Instant createdAt) {
    }

    /** 周报行。 */
    record WeeklyRow(LocalDate weekStart, String body, int itemCount, Instant createdAt) {
    }

    /**
     * 在日期窗口内已存在的指纹。
     *
     * <p>**只查 [from, to]，不查全表**：新闻不会隔一个月重新出现；查全表会让每次运行
     * 扫一张不断增长的表。
     */
    @Select("""
            <script>
            SELECT fingerprint FROM digest_items
            WHERE edition_date BETWEEN #{from} AND #{to}
              AND fingerprint IN
              <foreach collection="fingerprints" item="fp" open="(" separator="," close=")">#{fp}</foreach>
            </script>
            """)
    List<String> existingFingerprints(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                      @Param("fingerprints") List<String> fingerprints);

    /** 批量查询用：空列表直接短路（IN () 是语法错误）。 */
    default List<String> existingFingerprintsSafe(LocalDate from, LocalDate to,
                                                  List<String> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) {
            return List.of();
        }
        return existingFingerprints(from, to, fingerprints);
    }

    /** 入库一条。重复指纹由唯一约束挡住，调用方捕获异常按"已存在"处理。 */
    @Insert("""
            INSERT INTO digest_items (edition_date, group_key, source_key, source_name, title,
                                      summary, url, fingerprint, heat, stars_day, stars_week,
                                      meta, published_at, summary_state, created_at)
            VALUES (#{editionDate}, #{groupKey}, #{sourceKey}, #{sourceName}, #{title},
                    #{summary}, #{url}, #{fingerprint}, #{heat}, #{starsDay}, #{starsWeek},
                    #{meta}, #{publishedAt}, #{summaryState}, #{createdAt})
            """)
    int insert(@Param("editionDate") LocalDate editionDate, @Param("groupKey") String groupKey,
               @Param("sourceKey") String sourceKey, @Param("sourceName") String sourceName,
               @Param("title") String title, @Param("summary") String summary,
               @Param("url") String url, @Param("fingerprint") String fingerprint,
               @Param("heat") Integer heat, @Param("starsDay") Integer starsDay,
               @Param("starsWeek") Integer starsWeek, @Param("meta") String meta,
               @Param("publishedAt") Long publishedAt, @Param("summaryState") String summaryState,
               @Param("createdAt") Instant createdAt);

    /**
     * 某天的全部条目。
     *
     * <p>排序：`heat` 降序，`heat` 为空的排后面（MySQL 里 `NULL` 在 `DESC` 中排最后，
     * 正是想要的），同热度按发布时间新到旧。GitHub 的 `heat` 就是 `d1`；
     * 周榜由前端按 `stars_week` 重排，不在这里加第二套顺序。
     */
    @Select("""
            SELECT id, edition_date, group_key, source_key, source_name, title, summary, url,
                   fingerprint, heat, stars_day, stars_week, meta, published_at, summary_state,
                   created_at
            FROM digest_items
            WHERE edition_date = #{date}
            ORDER BY heat DESC, published_at DESC, id
            """)
    List<Row> findByDate(@Param("date") LocalDate date);

    /** 有内容的日期，新到旧。给日期选择器用。 */
    @Select("""
            SELECT DISTINCT edition_date FROM digest_items
            ORDER BY edition_date DESC
            LIMIT #{limit}
            """)
    List<LocalDate> recentDates(@Param("limit") int limit);

    /** 管理端删除单条。硬删：抓来的公开新闻没有审计价值。 */
    @Delete("DELETE FROM digest_items WHERE id = #{id}")
    int deleteById(@Param("id") long id);

    /** 周报 upsert：同周重跑只留一条（AC-016）。 */
    @Insert("""
            INSERT INTO digest_weeklies (week_start, body, item_count, created_at)
            VALUES (#{weekStart}, #{body}, #{itemCount}, #{createdAt})
            ON DUPLICATE KEY UPDATE
                body = VALUES(body), item_count = VALUES(item_count)
            """)
    int upsertWeekly(@Param("weekStart") LocalDate weekStart, @Param("body") String body,
                     @Param("itemCount") int itemCount, @Param("createdAt") Instant createdAt);

    /** 某周周报；没有时返回 null。 */
    @Select("""
            SELECT week_start, body, item_count, created_at FROM digest_weeklies
            WHERE week_start = #{weekStart}
            """)
    WeeklyRow findWeekly(@Param("weekStart") LocalDate weekStart);

    /** 时间段内的标题（周报素材）。只要标题，不带正文。 */
    @Select("""
            SELECT group_key, title FROM digest_items
            WHERE edition_date BETWEEN #{from} AND #{to}
            ORDER BY edition_date, group_key, heat DESC
            LIMIT #{limit}
            """)
    List<java.util.Map<String, Object>> titlesBetween(@Param("from") LocalDate from,
                                                      @Param("to") LocalDate to,
                                                      @Param("limit") int limit);

    /** 供测试与排查：清空（只在测试里用）。 */
    @Update("DELETE FROM digest_items")
    int deleteAll();
}
