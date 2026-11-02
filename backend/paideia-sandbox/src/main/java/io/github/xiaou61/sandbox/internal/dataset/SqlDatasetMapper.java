package io.github.xiaou61.sandbox.internal.dataset;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * SQL 数据集与举报（WORK-035 T-07）。
 *
 * <p>内置数据集**不在这两张表里**：它们永远在 classpath。这里只存私人/社区集。
 *
 * <p>没有"按 key 取内容"的方法：内容是 `FileStorage` 里的字节，这里只存 `storage_id`。
 */
@Mapper
public interface SqlDatasetMapper {

    /** 一行数据集。`schemaJson` 可能是 null（入库后抓结构失败时）。 */
    record Row(long id, String datasetKey, String name, String source, Long ownerUserId,
               String storageId, String schemaJson, int tableCount, int rowCount,
               int sizeBytes, String status, Instant createdAt, Instant updatedAt) {
    }

    /** 选择器要的一行（不含 schema 与 storage_id，那些只在运行时用）。 */
    record Summary(String datasetKey, String name, String source, Long ownerUserId,
                   int tableCount, int rowCount) {
    }

    @Insert("""
            INSERT INTO sql_datasets
                (dataset_key, name, source, owner_user_id, storage_id, schema_json,
                 table_count, row_count, size_bytes, status, created_at, updated_at)
            VALUES (#{datasetKey}, #{name}, #{source}, #{ownerUserId}, #{storageId}, #{schemaJson},
                    #{tableCount}, #{rowCount}, #{sizeBytes}, #{status}, #{now}, #{now})
            """)
    int insert(@Param("datasetKey") String datasetKey, @Param("name") String name,
               @Param("source") String source, @Param("ownerUserId") Long ownerUserId,
               @Param("storageId") String storageId, @Param("schemaJson") String schemaJson,
               @Param("tableCount") int tableCount, @Param("rowCount") int rowCount,
               @Param("sizeBytes") int sizeBytes, @Param("status") String status,
               @Param("now") Instant now);

    @Select("""
            SELECT id, dataset_key, name, source, owner_user_id, storage_id, schema_json,
                   table_count, row_count, size_bytes, status, created_at, updated_at
            FROM sql_datasets WHERE dataset_key = #{datasetKey}
            """)
    Optional<Row> findByKey(@Param("datasetKey") String datasetKey);

    /**
     * 某用户能看到的私人集 + 所有已发布的社区集。
     *
     * <p>**一条 SQL 里做完**而不是查两次再合并：合并两批要在应用层去重与排序，
     * 而这里本来就是一个"可见集合"的概念，交给数据库更不容易漏。
     */
    @Select("""
            SELECT dataset_key, name, source, owner_user_id, table_count, row_count
            FROM sql_datasets
            WHERE (source = 'private' AND owner_user_id = #{userId})
               OR (source = 'community' AND status = 'published')
            ORDER BY source DESC, name
            """)
    List<Summary> listVisible(@Param("userId") long userId);

    @Update("""
            UPDATE sql_datasets SET schema_json = #{schemaJson}, table_count = #{tableCount},
                   row_count = #{rowCount}, size_bytes = #{sizeBytes}, updated_at = #{now}
            WHERE dataset_key = #{datasetKey}
            """)
    int updateSnapshot(@Param("datasetKey") String datasetKey, @Param("schemaJson") String schemaJson,
                       @Param("tableCount") int tableCount, @Param("rowCount") int rowCount,
                       @Param("sizeBytes") int sizeBytes, @Param("now") Instant now);

    @Update("""
            UPDATE sql_datasets SET status = #{status}, updated_at = #{now}
            WHERE dataset_key = #{datasetKey}
            """)
    int updateStatus(@Param("datasetKey") String datasetKey, @Param("status") String status,
                     @Param("now") Instant now);

    /**
     * 私人 → 社区（WORK-035 T-10）。
     *
     * <p>与 `updateStatus` 分两步而不是合成一条：状态与来源是两个维度
     * （社区集也有 draft/published/taken_down 三态）。合成一条会让"把已下架的社区集重新发布"
     * 与"把私人集发布"走同一个方法，而它们的权限检查不同。
     */
    @Update("""
            UPDATE sql_datasets SET source = 'community', status = 'published', updated_at = #{now}
            WHERE dataset_key = #{datasetKey} AND source = 'private'
            """)
    int promoteToCommunity(@Param("datasetKey") String datasetKey, @Param("now") Instant now);

    /**
     * 社区 → 私人（WORK-060）。
     *
     * <p>**只改来源与状态，不删内容**：作者把公开集收回私有后，别人立刻看不到，
     * 但作者自己的数据还在，随时可以再公开。
     */
    @Update("""
            UPDATE sql_datasets SET source = 'private', status = 'draft', updated_at = #{now}
            WHERE dataset_key = #{datasetKey} AND source = 'community'
            """)
    int demoteToPrivate(@Param("datasetKey") String datasetKey, @Param("now") Instant now);

    @Delete("DELETE FROM sql_datasets WHERE dataset_key = #{datasetKey} AND owner_user_id = #{userId}")
    int deleteOwned(@Param("datasetKey") String datasetKey, @Param("userId") long userId);

    /** 注销：私人集删掉。 */
    @Delete("DELETE FROM sql_datasets WHERE owner_user_id = #{userId} AND source = 'private'")
    int deletePrivateByOwner(@Param("userId") long userId);

    /**
     * 注销：社区集**不删，只解除归属**。
     *
     * <p>别人可能正在用它做题；删掉会让他们的练习突然失效。与"注销删掉本人数据"的口径
     * 冲突时，这里选"保留公共内容"——因为它已经不是私有数据了（用户主动发布过）。
     */
    @Update("UPDATE sql_datasets SET owner_user_id = NULL, updated_at = #{now} "
            + "WHERE owner_user_id = #{userId} AND source = 'community'")
    int orphanCommunityByOwner(@Param("userId") long userId, @Param("now") Instant now);

    /** 管理端：按状态列出（含已下架，便于恢复）。 */
    @Select("""
            SELECT dataset_key, name, source, owner_user_id, table_count, row_count
            FROM sql_datasets
            WHERE source = 'community'
              AND (#{status} IS NULL OR status = #{status})
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<Summary> listCommunity(@Param("status") String status, @Param("limit") int limit);

    // ── 举报 ──────────────────────────────────────────────────────────────

    record ReportRow(long id, String datasetKey, long reporterUserId, String reason,
                     String status, Instant createdAt, Instant handledAt) {
    }

    /**
     * 举报：同一人对同一集只留一条。
     *
     * <p>`ON DUPLICATE KEY UPDATE` 而不是先查再插：并发下"查不到再插"会撞唯一键报 500，
     * 而这里重复提交的语义就是"再举报一次"，更新理由与时间即可。
     */
    @Insert("""
            INSERT INTO sql_dataset_reports
                (dataset_key, reporter_user_id, reason, status, created_at)
            VALUES (#{datasetKey}, #{reporterUserId}, #{reason}, 'pending', #{now})
            ON DUPLICATE KEY UPDATE reason = #{reason}, created_at = #{now},
                status = CASE WHEN status = 'taken_down' THEN 'taken_down' ELSE 'pending' END
            """)
    int upsertReport(@Param("datasetKey") String datasetKey, @Param("reporterUserId") long reporterUserId,
                     @Param("reason") String reason, @Param("now") Instant now);

    @Select("""
            SELECT id, dataset_key, reporter_user_id, reason, status, created_at, handled_at
            FROM sql_dataset_reports
            WHERE #{status} IS NULL OR status = #{status}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<ReportRow> listReports(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM sql_dataset_reports WHERE dataset_key = #{datasetKey}")
    int countReports(@Param("datasetKey") String datasetKey);

    @Update("UPDATE sql_dataset_reports SET status = #{status}, handled_at = #{now} "
            + "WHERE dataset_key = #{datasetKey} AND status = 'pending'")
    int markReportsHandled(@Param("datasetKey") String datasetKey, @Param("status") String status,
                           @Param("now") Instant now);

    /** 注销：本人提交的举报一并删（与"注销删掉本人数据"一致）。 */
    @Delete("DELETE FROM sql_dataset_reports WHERE reporter_user_id = #{userId}")
    int deleteReportsByReporter(@Param("userId") long userId);
}
