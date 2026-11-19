package io.github.xiaou61.ops;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 日志模板与模板级异常的持久化（WORK-085）。
 *
 * <p>三张表：模板字典、窗口频次桶、异常账本。写入全部用 upsert（`ON DUPLICATE KEY UPDATE`），
 * 因为同步每几分钟就会重跑同一窗口——重算覆盖而不是累加，重复同步不会把频次刷成两倍。
 *
 * <p>桶按 `window-minutes` 对齐（不是整点），检测窗口与桶同宽，避免「15 分钟窗口落到整点桶里
 * 只算到几分钟」的系统性低估。
 *
 * <p>`total_count` 取 `GREATEST`（最近一轮回看窗口内的条数），不是累加：同步每几分钟重算同一段
 * 历史，累加会把它越刷越大（2026-11-19 修正）。
 */
@Mapper
interface PatternMapper {

    @Insert("""
            INSERT INTO ops_log_templates
                (template_hash, unit, pattern, sample_line, first_seen, last_seen,
                 last_count, total_count, created_at, updated_at)
            VALUES
                (#{templateHash}, #{unit}, #{pattern}, #{sampleLine}, #{firstSeen}, #{lastSeen},
                 #{lastCount}, #{totalCount}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                pattern     = VALUES(pattern),
                sample_line = VALUES(sample_line),
                first_seen  = LEAST(first_seen, VALUES(first_seen)),
                last_seen   = VALUES(last_seen),
                last_count  = VALUES(last_count),
                total_count = GREATEST(total_count, VALUES(total_count)),
                updated_at  = VALUES(updated_at)
            """)
    int upsertTemplate(@Param("templateHash") String templateHash,
                       @Param("unit") String unit,
                       @Param("pattern") String pattern,
                       @Param("sampleLine") String sampleLine,
                       @Param("firstSeen") Instant firstSeen,
                       @Param("lastSeen") Instant lastSeen,
                       @Param("lastCount") int lastCount,
                       @Param("totalCount") long totalCount,
                       @Param("createdAt") Instant createdAt,
                       @Param("updatedAt") Instant updatedAt);

    @Insert("""
            INSERT INTO ops_log_template_buckets
                (template_hash, unit, bucket_start, dow, hour, count, created_at)
            VALUES
                (#{templateHash}, #{unit}, #{bucketStart}, #{dow}, #{hour}, #{count}, #{createdAt})
            ON DUPLICATE KEY UPDATE count = VALUES(count)
            """)
    int upsertBucket(@Param("templateHash") String templateHash,
                     @Param("unit") String unit,
                     @Param("bucketStart") Instant bucketStart,
                     @Param("dow") int dow,
                     @Param("hour") int hour,
                     @Param("count") int count,
                     @Param("createdAt") Instant createdAt);

    /** 最近仍在活动的模板（当天见过）；检测以模板为驱动，才能发现「这次一条都没有」。 */
    @Select("""
            SELECT template_hash, unit, pattern, first_seen, last_seen, last_count, total_count
            FROM ops_log_templates
            WHERE unit = #{unit} AND last_seen >= #{since}
            ORDER BY last_seen DESC
            LIMIT #{limit}
            """)
    List<Template> recentTemplates(@Param("unit") String unit,
                                   @Param("since") Instant since,
                                   @Param("limit") int limit);

    /** 窗口内某模板的条数；没有行为 0（不返回 null）。 */
    @Select("""
            SELECT COALESCE(SUM(count), 0)
            FROM ops_log_template_buckets
            WHERE template_hash = #{templateHash} AND unit = #{unit}
              AND bucket_start >= #{from} AND bucket_start < #{to}
            """)
    long sumInWindow(@Param("templateHash") String templateHash,
                     @Param("unit") String unit,
                     @Param("from") Instant from,
                     @Param("to") Instant to);

    /** 同 dow+hour 的历史窗口条数（含最近几周），供均值/标准差基线。 */
    @Select("""
            SELECT count
            FROM ops_log_template_buckets
            WHERE template_hash = #{templateHash} AND unit = #{unit}
              AND dow = #{dow} AND hour = #{hour}
              AND bucket_start >= #{since} AND bucket_start < #{before}
            ORDER BY bucket_start DESC
            LIMIT 200
            """)
    List<Integer> baselineCounts(@Param("templateHash") String templateHash,
                                 @Param("unit") String unit,
                                 @Param("dow") int dow,
                                 @Param("hour") int hour,
                                 @Param("since") Instant since,
                                 @Param("before") Instant before);

    /** 该单元在本窗口之前是否已有历史桶；没有就是冷启动，不判 NEW_TEMPLATE。 */
    @Select("""
            SELECT COUNT(*) FROM ops_log_template_buckets
            WHERE unit = #{unit} AND bucket_start < #{before}
            """)
    long countBucketsBefore(@Param("unit") String unit, @Param("before") Instant before);

    /** Top 模板（窗口内按条数），带模板文本便于展示。 */
    @Select("""
            SELECT t.template_hash AS templateHash, t.unit AS unit, t.pattern AS pattern,
                   COALESCE(SUM(b.count), 0) AS count
            FROM ops_log_template_buckets b
            JOIN ops_log_templates t
              ON t.template_hash = b.template_hash AND t.unit = b.unit
            WHERE b.unit = #{unit} AND b.bucket_start >= #{from} AND b.bucket_start < #{to}
            GROUP BY t.template_hash, t.unit, t.pattern
            ORDER BY count DESC
            LIMIT #{limit}
            """)
    List<TopTemplate> topTemplates(@Param("unit") String unit,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                    @Param("limit") int limit);

    @Insert("""
            INSERT INTO ops_pattern_anomalies
                (kind, template_hash, unit, ratio, z, baseline_count, current_count,
                 window_start, window_end, status, detail, created_at)
            VALUES
                (#{kind}, #{templateHash}, #{unit}, #{ratio}, #{z}, #{baselineCount}, #{currentCount},
                 #{windowStart}, #{windowEnd}, #{status}, #{detail}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAnomaly(AnomalyRow row);

    /** 同一模板同一类型在窗口内只记一条，避免每轮同步都追加一条同样的异常。 */
    @Select("""
            SELECT COUNT(*) FROM ops_pattern_anomalies
            WHERE kind = #{kind} AND template_hash = #{templateHash} AND unit = #{unit}
              AND created_at >= #{since}
            """)
    long countRecent(@Param("kind") String kind,
                     @Param("templateHash") String templateHash,
                     @Param("unit") String unit,
                     @Param("since") Instant since);

    @Select("SELECT COUNT(*) FROM ops_pattern_anomalies WHERE kind = #{kind} AND status = 'OPEN'")
    long countOpen(@Param("kind") String kind);

    /** 最近一次触发该模板异常的时间；用于跨轮次的触发冷却。 */
    @Select("""
            SELECT MAX(created_at) FROM ops_pattern_anomalies
            WHERE kind = #{kind} AND template_hash = #{templateHash} AND unit = #{unit}
              AND (diagnosis_id IS NOT NULL OR status = 'TRIGGER_SKIPPED')
            """)
    Instant lastTriggeredAt(@Param("kind") String kind,
                            @Param("templateHash") String templateHash,
                            @Param("unit") String unit);

    @Update("""
            UPDATE ops_pattern_anomalies
            SET status = #{status}, detail = #{detail}
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") long id,
                     @Param("status") String status,
                     @Param("detail") String detail);

    @Update("""
            UPDATE ops_pattern_anomalies
            SET diagnosis_id = #{diagnosisId}, status = 'OPEN'
            WHERE id = #{id}
            """)
    int setDiagnosis(@Param("id") long id, @Param("diagnosisId") long diagnosisId);

    @Select("""
            SELECT id, kind, template_hash, unit, ratio, z, baseline_count, current_count,
                   window_start, window_end, status, incident_id, diagnosis_id, detail,
                   created_at, resolved_at
            FROM ops_pattern_anomalies
            WHERE created_at >= #{since}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<AnomalyRow> recentAnomalies(@Param("since") Instant since, @Param("limit") int limit);

    @Delete("DELETE FROM ops_log_template_buckets WHERE bucket_start < #{before}")
    int purgeBuckets(@Param("before") Instant before);

    @Delete("DELETE FROM ops_log_templates WHERE last_seen < #{before}")
    int purgeTemplates(@Param("before") Instant before);

    @Delete("DELETE FROM ops_pattern_anomalies WHERE created_at < #{before}")
    int purgeAnomalies(@Param("before") Instant before);

    /** 历史诊断全文检索（REQ-007）；参数化 MATCH...AGAINST，不拼串。 */
    @Select("""
            SELECT id, report_md, created_at
            FROM ops_diagnoses
            WHERE status = 'DONE'
              AND MATCH(report_md) AGAINST (#{q} IN NATURAL LANGUAGE MODE)
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<DiagnosisHit> searchDiagnoses(@Param("q") String q, @Param("limit") int limit);

    /** 历史模板异常全文检索（REQ-007）。 */
    @Select("""
            SELECT id, kind, template_hash, unit, ratio, z, baseline_count, current_count,
                   window_start, window_end, status, incident_id, diagnosis_id, detail,
                   created_at, resolved_at
            FROM ops_pattern_anomalies
            WHERE MATCH(detail) AGAINST (#{q} IN NATURAL LANGUAGE MODE)
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<AnomalyRow> searchAnomalies(@Param("q") String q, @Param("limit") int limit);

    record Template(String templateHash, String unit, String pattern,
                    Instant firstSeen, Instant lastSeen, int lastCount, long totalCount) {
    }

    record TopTemplate(String templateHash, String unit, String pattern, long count) {
    }

    record DiagnosisHit(long id, String reportMd, Instant createdAt) {
    }

    class AnomalyRow {
        private Long id;
        private String kind;
        private String templateHash;
        private String unit;
        private Double ratio;
        private Double z;
        private Integer baselineCount;
        private Integer currentCount;
        private Instant windowStart;
        private Instant windowEnd;
        private String status;
        private Long incidentId;
        private Long diagnosisId;
        private String detail;
        private Instant createdAt;
        private Instant resolvedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getTemplateHash() {
            return templateHash;
        }

        public void setTemplateHash(String templateHash) {
            this.templateHash = templateHash;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public Double getRatio() {
            return ratio;
        }

        public void setRatio(Double ratio) {
            this.ratio = ratio;
        }

        public Double getZ() {
            return z;
        }

        public void setZ(Double z) {
            this.z = z;
        }

        public Integer getBaselineCount() {
            return baselineCount;
        }

        public void setBaselineCount(Integer baselineCount) {
            this.baselineCount = baselineCount;
        }

        public Integer getCurrentCount() {
            return currentCount;
        }

        public void setCurrentCount(Integer currentCount) {
            this.currentCount = currentCount;
        }

        public Instant getWindowStart() {
            return windowStart;
        }

        public void setWindowStart(Instant windowStart) {
            this.windowStart = windowStart;
        }

        public Instant getWindowEnd() {
            return windowEnd;
        }

        public void setWindowEnd(Instant windowEnd) {
            this.windowEnd = windowEnd;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getIncidentId() {
            return incidentId;
        }

        public void setIncidentId(Long incidentId) {
            this.incidentId = incidentId;
        }

        public Long getDiagnosisId() {
            return diagnosisId;
        }

        public void setDiagnosisId(Long diagnosisId) {
            this.diagnosisId = diagnosisId;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getResolvedAt() {
            return resolvedAt;
        }

        public void setResolvedAt(Instant resolvedAt) {
            this.resolvedAt = resolvedAt;
        }
    }
}
