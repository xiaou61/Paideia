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
                (trigger_type, alert_name, question, status, requested_by, created_at, incident_id)
            VALUES
                (#{triggerType}, #{alertName}, #{question}, #{status}, #{requestedBy}, #{createdAt}, #{incidentId})
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
                   requested_by, created_at, finished_at, incident_id, rating, feedback_note, feedback_at
            FROM ops_diagnoses
            WHERE id = #{id}
            """)
    Optional<OpsDiagnosisRow> findById(@Param("id") long id);

    @Select("""
            SELECT id, trigger_type, alert_name, question, status, model,
                   input_tokens, output_tokens, report_md, error_message,
                   requested_by, created_at, finished_at, incident_id, rating, feedback_note, feedback_at
            FROM ops_diagnoses
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<OpsDiagnosisRow> listRecent(@Param("limit") int limit);

    @Select("""
            SELECT id, trigger_type, alert_name, question, status, model,
                   input_tokens, output_tokens, report_md, error_message,
                   requested_by, created_at, finished_at, incident_id, rating, feedback_note, feedback_at
            FROM ops_diagnoses
            WHERE incident_id = #{incidentId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<OpsDiagnosisRow> listByIncident(@Param("incidentId") long incidentId,
                                         @Param("limit") int limit);

    /** 评分覆盖（WORK-043）：同一人重评只保留最后一次，不追加历史。只对已完成的诊断开放。 */
    @Update("""
            UPDATE ops_diagnoses
            SET rating = #{rating}, feedback_note = #{note}, feedback_at = #{at}
            WHERE id = #{id} AND status = 'DONE'
            """)
    int updateFeedback(@Param("id") long id,
                       @Param("rating") int rating,
                       @Param("note") String note,
                       @Param("at") Instant at);

    /**
     * 好评率（WORK-043 TASK-023）。
     *
     * <p>`rated` 是分母（有评分的条数），`good/partial/bad` 对应 1/0/-1。
     * 好评率在服务层算：分母为 0 时给 null 而不是 0——"还没人评"与"全都不准"不是一回事。
     */
    @Select("""
            SELECT COUNT(*) AS rated,
                   COALESCE(SUM(rating = 1), 0)  AS good,
                   COALESCE(SUM(rating = 0), 0)  AS partial,
                   COALESCE(SUM(rating = -1), 0) AS bad
            FROM ops_diagnoses
            WHERE rating IS NOT NULL AND status = 'DONE'
            """)
    FeedbackStats stats();

    /** 统计行。与 AiUsageMapper 的用法一致：编译器开了 -parameters，MyBatis 能按名回填。 */
    record FeedbackStats(long rated, long good, long partial, long bad) {
    }
}
