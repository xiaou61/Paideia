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
