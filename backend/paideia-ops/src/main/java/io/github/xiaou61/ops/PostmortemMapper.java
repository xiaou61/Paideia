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
 * 运维复盘的持久化（WORK-086）。
 *
 * <p>状态推进全部用**带条件的 UPDATE**（`WHERE status = ...`），与 {@link OpsDiagnosisMapper}
 * 同一套理由：两个人同时点"确认"时，后到的那个不该把已归档的行重新拉回 APPROVED。
 *
 * <p>「同一事件只能有一份生效复盘」由 {@link PostmortemService} 在事务里处理（把旧的置 ARCHIVED），
 * 不建唯一索引——历史数据里可能已经有多份，加唯一索引会让迁移直接失败。
 */
@Mapper
interface PostmortemMapper {

    String COLUMNS = """
            id, incident_id, diagnosis_id, alert_name, title, severity, status,
            root_cause, impact, timeline_md, actions_md, body_md, evidence_refs, origin,
            created_by, approved_by, approved_at, created_at, updated_at
            """;

    @Insert("""
            INSERT INTO ops_postmortems
                (incident_id, diagnosis_id, alert_name, title, severity, status,
                 root_cause, impact, timeline_md, actions_md, body_md, evidence_refs, origin,
                 created_by, created_at, updated_at)
            VALUES
                (#{incidentId}, #{diagnosisId}, #{alertName}, #{title}, #{severity}, #{status},
                 #{rootCause}, #{impact}, #{timelineMd}, #{actionsMd}, #{bodyMd}, #{evidenceRefs},
                 #{origin}, #{createdBy}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PostmortemRow row);

    @Select("SELECT " + COLUMNS + " FROM ops_postmortems WHERE id = #{id}")
    Optional<PostmortemRow> findById(@Param("id") long id);

    /** 同一事件下还没退出知识库的那一份（DRAFT 或 APPROVED）；用于避免重复自动起草。 */
    @Select("""
            SELECT
            """ + COLUMNS + """
             FROM ops_postmortems
            WHERE incident_id = #{incidentId} AND status IN ('DRAFT', 'APPROVED')
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    Optional<PostmortemRow> findLiveByIncident(@Param("incidentId") long incidentId);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
             FROM ops_postmortems
            <where>
              <if test="status != null">status = #{status}</if>
            </where>
            ORDER BY updated_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<PostmortemRow> list(@Param("status") String status,
                             @Param("limit") int limit,
                             @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM ops_postmortems
            <where>
              <if test="status != null">status = #{status}</if>
            </where>
            </script>
            """)
    long count(@Param("status") String status);

    /** 编辑正文与各字段；状态由调用方决定（APPROVED 被编辑时一并回到 DRAFT）。 */
    @Update("""
            UPDATE ops_postmortems
            SET title = #{title},
                severity = #{severity},
                alert_name = #{alertName},
                root_cause = #{rootCause},
                impact = #{impact},
                timeline_md = #{timelineMd},
                actions_md = #{actionsMd},
                body_md = #{bodyMd},
                evidence_refs = #{evidenceRefs},
                status = #{status},
                approved_by = #{approvedBy},
                approved_at = #{approvedAt},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateFields(PostmortemRow row);

    @Update("""
            UPDATE ops_postmortems
            SET status = 'APPROVED', approved_by = #{approvedBy}, approved_at = #{at},
                updated_at = #{at}
            WHERE id = #{id} AND status IN ('DRAFT', 'ARCHIVED')
            """)
    int markApproved(@Param("id") long id,
                     @Param("approvedBy") long approvedBy,
                     @Param("at") Instant at);

    @Update("""
            UPDATE ops_postmortems
            SET status = 'ARCHIVED', updated_at = #{at}
            WHERE id = #{id} AND status = 'APPROVED'
            """)
    int markArchived(@Param("id") long id, @Param("at") Instant at);

    /** 同一事件出现新的 APPROVED 时，把旧的生效复盘退役（保留历史，退出检索）。 */
    @Update("""
            UPDATE ops_postmortems
            SET status = 'ARCHIVED', updated_at = #{at}
            WHERE incident_id = #{incidentId} AND status = 'APPROVED' AND id &lt;&gt; #{keepId}
            """)
    int archiveOthersOfIncident(@Param("incidentId") long incidentId,
                                @Param("keepId") long keepId,
                                @Param("at") Instant at);

    /**
     * 知识召回：只查 APPROVED，按「同名告警 → 全文相关度 → 越新越前」排序。
     *
     * <p>`score` 列不在 {@link PostmortemRow} 上，MyBatis 默认忽略未映射列；排序仍按它，所以不用回填。
     */
    @Select("""
            SELECT id, incident_id, diagnosis_id, alert_name, title, severity, status,
                   root_cause, impact, timeline_md, actions_md, body_md, evidence_refs, origin,
                   created_by, approved_by, approved_at, created_at, updated_at,
                   MATCH(title, root_cause, body_md) AGAINST (#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM ops_postmortems
            WHERE status = 'APPROVED'
              AND (alert_name = #{alertName}
                   OR MATCH(title, root_cause, body_md) AGAINST (#{query} IN NATURAL LANGUAGE MODE) > 0)
            ORDER BY (alert_name = #{alertName}) DESC, score DESC, approved_at DESC
            LIMIT #{limit}
            """)
    List<PostmortemRow> searchApproved(@Param("alertName") String alertName,
                                       @Param("query") String query,
                                       @Param("limit") int limit);
}
