package io.github.xiaou61.note.internal.share;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 举报的读写。
 *
 * <p>放在 {@code internal.share} 而不是单开一个 {@code internal.report}：它要读
 * {@link NoteShareMapper}（判分享是否存在、下架），而那两个 mapper 是包内可见的。
 * 为一个包名去把 mapper 改成 public，比把这块逻辑放在同包更贵。
 */
@Mapper
interface NoteReportMapper {

    /** 分享是否存在（不论是否已失效）。举报一条**不存在**的分享没有意义，也说明请求有问题。 */
    @Select("SELECT COUNT(1) FROM note_shares WHERE share_id = #{shareId}")
    int countShare(@Param("shareId") String shareId);

    /**
     * 提交举报。同一个人对同一条分享**只留一条**：重复提交是"再用一次"，
     * 所以命中唯一键时更新理由并把状态拨回 pending（管理员可能刚驳回过）。
     */
    @Insert("""
            INSERT INTO note_reports (share_id, reporter_user_id, reason)
            VALUES (#{shareId}, #{userId}, #{reason})
            ON DUPLICATE KEY UPDATE reason = VALUES(reason), status = 'pending', handled_at = NULL
            """)
    int upsert(@Param("shareId") String shareId, @Param("userId") long userId, @Param("reason") String reason);

    /** 同一条分享被举报了几次（帮管理员判断要不要处理）。 */
    @Select("SELECT COUNT(1) FROM note_reports WHERE share_id = #{shareId}")
    int countByShare(@Param("shareId") String shareId);

    /** 管理端列表：待处理在前，同状态内早的在前。带上笔记标题，好判断该看哪条。 */
    @Select("""
            SELECT r.id, r.share_id, r.reason, r.status, r.created_at,
                   COALESCE(n.title, b.name) AS title
            FROM note_reports r
            LEFT JOIN note_shares s ON s.share_id = r.share_id
            LEFT JOIN notes n ON s.target_type = 'note' AND n.id = s.target_id
            LEFT JOIN note_books b ON s.target_type = 'book' AND b.id = s.target_id
            WHERE #{status} IS NULL OR r.status = #{status}
            ORDER BY (r.status = 'pending') DESC, r.created_at ASC
            LIMIT #{limit}
            """)
    List<ReportRow> list(@Param("status") String status, @Param("limit") int limit);

    /** 下架时按举报 id 反查它指的是哪条分享。 */
    @Select("SELECT id, share_id, reason, status, created_at FROM note_reports WHERE id = #{id}")
    Optional<ReportRow> findById(@Param("id") long id);

    @Update("""
            UPDATE note_reports
            SET status = 'taken_down', handled_at = #{now}
            WHERE id = #{id}
            """)
    int markTakenDown(@Param("id") long id, @Param("now") Instant now);

    /** 管理端列表行。 */
    class ReportRow {

        private Long id;
        private String shareId;
        private String reason;
        private String status;
        private Instant createdAt;
        /** 所举报分享的笔记标题（可能是 null：笔记被删了）。 */
        private String title;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getShareId() {
            return shareId;
        }

        public void setShareId(String shareId) {
            this.shareId = shareId;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }
}
