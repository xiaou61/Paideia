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
 * 事件与时间线的读写（WORK-043）。
 *
 * <p>`upsertFiring` 的 `id = LAST_INSERT_ID(id)` 是 MySQL 的固定技巧：命中唯一键时让
 * `useGeneratedKeys` 拿回**已存在**的 id，否则调用方只能再查一次。
 */
@Mapper
interface OpsIncidentMapper {

    @Insert("""
            INSERT INTO ops_incidents
                (fingerprint, dedupe_key, alert_name, severity, status, first_seen, last_seen,
                 alert_count, labels_json, created_at, updated_at)
            VALUES
                (#{fingerprint}, #{dedupeKey}, #{alertName}, #{severity}, 'OPEN', #{firstSeen}, #{lastSeen},
                 1, #{labelsJson}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                id = LAST_INSERT_ID(id),
                last_seen = VALUES(last_seen),
                alert_count = alert_count + 1,
                updated_at = VALUES(updated_at)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsertFiring(OpsIncident row);

    @Update("""
            UPDATE ops_incidents
            SET status = 'RESOLVED', resolved_at = #{resolvedAt}, dedupe_key = NULL,
                updated_at = #{updatedAt}
            WHERE dedupe_key = #{dedupeKey} AND status = 'OPEN'
            """)
    int resolve(@Param("dedupeKey") String dedupeKey,
                @Param("resolvedAt") Instant resolvedAt,
                @Param("updatedAt") Instant updatedAt);

    /** 手动关闭（WORK-049）：条件 UPDATE 保证并发下只有一次成功。 */
    @Update("""
            UPDATE ops_incidents
            SET status = 'RESOLVED', resolved_at = #{at}, dedupe_key = NULL, updated_at = #{at}
            WHERE id = #{id} AND status = 'OPEN'
            """)
    int resolveById(@Param("id") long id, @Param("at") Instant at);

    @Select("""
            SELECT id, fingerprint, dedupe_key, alert_name, severity, status,
                   first_seen, last_seen, resolved_at, alert_count,
                   last_diagnosed_at, last_diagnosis_id, labels_json, created_at, updated_at
            FROM ops_incidents
            WHERE dedupe_key = #{dedupeKey} AND status = 'OPEN'
            LIMIT 1
            """)
    Optional<OpsIncident> findOpen(@Param("dedupeKey") String dedupeKey);

    @Select("""
            SELECT id, fingerprint, dedupe_key, alert_name, severity, status,
                   first_seen, last_seen, resolved_at, alert_count,
                   last_diagnosed_at, last_diagnosis_id, labels_json, created_at, updated_at
            FROM ops_incidents
            WHERE id = #{id}
            """)
    Optional<OpsIncident> findById(@Param("id") long id);

    @Select("""
            <script>
            SELECT id, fingerprint, dedupe_key, alert_name, severity, status,
                   first_seen, last_seen, resolved_at, alert_count,
                   last_diagnosed_at, last_diagnosis_id, labels_json, created_at, updated_at
            FROM ops_incidents
            <where>
              <if test="status != null and status != ''">status = #{status}</if>
            </where>
            ORDER BY last_seen DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<OpsIncident> listRecent(@Param("status") String status, @Param("limit") int limit);

    /**
     * keyset 分页（WORK-049）：同一 `last_seen` 用 `id` 破平，翻页不重不漏。
     * 不用 OFFSET：深翻页随行数线性变慢，而事件表只增不减。
     */
    @Select("""
            <script>
            SELECT id, fingerprint, dedupe_key, alert_name, severity, status,
                   first_seen, last_seen, resolved_at, alert_count,
                   last_diagnosed_at, last_diagnosis_id, labels_json, created_at, updated_at
            FROM ops_incidents
            <where>
              <if test="status != null and status != ''">status = #{status}</if>
              <if test="since != null">AND last_seen >= #{since}</if>
              <if test="beforeLastSeen != null">
                AND (last_seen &lt; #{beforeLastSeen}
                     OR (last_seen = #{beforeLastSeen} AND id &lt; #{beforeId}))
              </if>
            </where>
            ORDER BY last_seen DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<OpsIncident> listPage(@Param("status") String status,
                               @Param("since") Instant since,
                               @Param("beforeLastSeen") Instant beforeLastSeen,
                               @Param("beforeId") Long beforeId,
                               @Param("limit") int limit);

    /** 历史同类事件（WORK-049）：一次 join 拿到上次结论与评分，报告只截前 400 字。 */
    @Select("""
            SELECT i.id AS incident_id, i.alert_name, i.first_seen, i.last_seen, i.status,
                   i.last_diagnosis_id AS diagnosis_id,
                   LEFT(d.report_md, 400) AS report_md, d.rating
            FROM ops_incidents i
            LEFT JOIN ops_diagnoses d ON d.id = i.last_diagnosis_id
            WHERE i.fingerprint = #{fingerprint} AND i.id <> #{excludeId}
              AND i.last_seen >= #{since}
            ORDER BY i.last_seen DESC, i.id DESC
            LIMIT #{limit}
            """)
    List<HistoryRow> listHistoryByFingerprint(@Param("fingerprint") String fingerprint,
                                              @Param("excludeId") long excludeId,
                                              @Param("since") Instant since,
                                              @Param("limit") int limit);

    @Select("""
            SELECT i.id AS incident_id, i.alert_name, i.first_seen, i.last_seen, i.status,
                   i.last_diagnosis_id AS diagnosis_id,
                   LEFT(d.report_md, 400) AS report_md, d.rating
            FROM ops_incidents i
            LEFT JOIN ops_diagnoses d ON d.id = i.last_diagnosis_id
            WHERE i.alert_name = #{alertName} AND i.id <> #{excludeId}
              AND i.last_seen >= #{since}
            ORDER BY i.last_seen DESC, i.id DESC
            LIMIT #{limit}
            """)
    List<HistoryRow> listHistoryByAlertName(@Param("alertName") String alertName,
                                            @Param("excludeId") long excludeId,
                                            @Param("since") Instant since,
                                            @Param("limit") int limit);

    /** 同期其他 OPEN 事件（WORK-049）：只取时间上相邻，不推断同一根因。 */
    @Select("""
            SELECT id, fingerprint, dedupe_key, alert_name, severity, status,
                   first_seen, last_seen, resolved_at, alert_count,
                   last_diagnosed_at, last_diagnosis_id, labels_json, created_at, updated_at
            FROM ops_incidents
            WHERE status = 'OPEN' AND last_seen >= #{since} AND id <> #{excludeId}
            ORDER BY last_seen DESC, id DESC
            LIMIT #{limit}
            """)
    List<OpsIncident> listOpenSince(@Param("since") Instant since,
                                    @Param("excludeId") long excludeId,
                                    @Param("limit") int limit);

    /** 历史行。报告只取首行，所以 SQL 里已经截到 400 字。 */
    record HistoryRow(long incidentId, String alertName, Instant firstSeen, Instant lastSeen,
                      String status, Long diagnosisId, String reportMd, Integer rating) {
    }

    /**
     * 重启补诊断（WORK-049）：OPEN、窗口内创建、没有任何 DONE 诊断，且上次尝试已过冷却。
     * 条件写进 SQL 而不是 Java 过滤，避免把整张 OPEN 表捞出来。
     */
    @Select("""
            SELECT i.id, i.fingerprint, i.dedupe_key, i.alert_name, i.severity, i.status,
                   i.first_seen, i.last_seen, i.resolved_at, i.alert_count,
                   i.last_diagnosed_at, i.last_diagnosis_id, i.labels_json, i.created_at, i.updated_at
            FROM ops_incidents i
            WHERE i.status = 'OPEN'
              AND i.created_at >= #{since}
              AND (i.last_diagnosed_at IS NULL OR i.last_diagnosed_at < #{diagnosedBefore})
              AND NOT EXISTS (SELECT 1 FROM ops_diagnoses d
                              WHERE d.incident_id = i.id AND d.status = 'DONE')
            ORDER BY i.last_seen DESC, i.id DESC
            LIMIT 1
            """)
    Optional<OpsIncident> findCatchUpCandidate(@Param("since") Instant since,
                                               @Param("diagnosedBefore") Instant diagnosedBefore);

    @Update("""
            UPDATE ops_incidents
            SET last_diagnosed_at = #{at}, updated_at = #{at}
            WHERE id = #{id}
            """)
    int markDiagnosed(@Param("id") long id, @Param("at") Instant at);

    @Update("""
            UPDATE ops_incidents
            SET last_diagnosis_id = #{diagnosisId}, updated_at = #{at}
            WHERE id = #{id}
            """)
    int linkDiagnosis(@Param("id") long id,
                      @Param("diagnosisId") long diagnosisId,
                      @Param("at") Instant at);

    @Insert("""
            INSERT INTO ops_incident_events
                (incident_id, kind, message, actor_user_id, payload_json, created_at)
            VALUES
                (#{incidentId}, #{kind}, #{message}, #{actorUserId}, #{payloadJson}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEvent(OpsIncidentEvent event);

    @Select("""
            SELECT id, incident_id, kind, message, actor_user_id, payload_json, created_at
            FROM ops_incident_events
            WHERE incident_id = #{incidentId}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<OpsIncidentEvent> listEvents(@Param("incidentId") long incidentId,
                                      @Param("limit") int limit);
}
