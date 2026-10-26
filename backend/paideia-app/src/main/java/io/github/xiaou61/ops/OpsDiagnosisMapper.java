package io.github.xiaou61.ops;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 诊断记录的读写（WORK-042）。
 *
 * <p>状态推进用**带条件的 UPDATE**：`markRunning` 只接受 `PENDING`，避免一个重复提交
 * 把已经跑起来的行重新置成 RUNNING；`markStale` 只碰超过时限的 `PENDING/RUNNING`，
 * 不会误伤刚提交的那一条。
 */
@Mapper
interface OpsDiagnosisMapper {

    @Insert("""
            INSERT INTO ops_diagnoses
                (trigger_type, alert_name, question, status, requested_by, created_at)
            VALUES
                (#{triggerType}, #{alertName}, #{question}, #{status}, #{requestedBy}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OpsDiagnosisRow row);

    @Update("""
            UPDATE ops_diagnoses
            SET status = 'RUNNING'
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markRunning(@Param("id") long id);

    @Update("""
            UPDATE ops_diagnoses
            SET status = 'DONE', model = #{model}, input_tokens = #{inputTokens},
                output_tokens = #{outputTokens}, report_md = #{report},
                error_message = NULL, finished_at = #{finishedAt}
            WHERE id = #{id}
            """)
    int markDone(@Param("id") long id,
                 @Param("model") String model,
                 @Param("inputTokens") Integer inputTokens,
                 @Param("outputTokens") Integer outputTokens,
                 @Param("report") String report,
                 @Param("finishedAt") Instant finishedAt);

    @Update("""
            UPDATE ops_diagnoses
            SET status = 'FAILED', error_message = #{error}, finished_at = #{finishedAt}
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") long id,
                   @Param("error") String error,
                   @Param("finishedAt") Instant finishedAt);

    /** 应用重启时把陈旧的 PENDING/RUNNING 收成 FAILED，避免"永远诊断中"。 */
    @Update("""
            UPDATE ops_diagnoses
            SET status = 'FAILED', error_message = #{error}, finished_at = #{finishedAt}
            WHERE status IN ('PENDING', 'RUNNING') AND created_at < #{before}
            """)
    int markStale(@Param("before") Instant before,
                  @Param("error") String error,
                  @Param("finishedAt") Instant finishedAt);

    @Select("""
            SELECT id, trigger_type, alert_name, question, status, model,
                   input_tokens, output_tokens, report_md, error_message,
                   requested_by, created_at, finished_at
            FROM ops_diagnoses
            WHERE id = #{id}
            """)
    Optional<OpsDiagnosisRow> findById(@Param("id") long id);

    @Select("""
            SELECT id, trigger_type, alert_name, question, status, model,
                   input_tokens, output_tokens, report_md, error_message,
                   requested_by, created_at, finished_at
            FROM ops_diagnoses
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<OpsDiagnosisRow> listRecent(@Param("limit") int limit);
}
