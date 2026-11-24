package io.github.xiaou61.note.internal.link;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 双链关系的读写。保存文档时按 source 全量重建。 */
@Mapper
interface NoteLinkMapper {

    @Delete("DELETE FROM note_links WHERE source_note_id = #{sourceNoteId} AND user_id = #{userId}")
    int deleteBySource(@Param("sourceNoteId") long sourceNoteId, @Param("userId") long userId);

    @Insert("""
            INSERT INTO note_links (user_id, source_note_id, target_note_id, target_title)
            VALUES (#{userId}, #{sourceNoteId}, #{targetNoteId}, #{targetTitle})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NoteLink link);

    /** 本篇引用了谁（出链），带目标当前标题；目标悬空时 targetNoteId 为 null。 */
    @Select("""
            SELECT l.target_note_id AS target_note_id, l.target_title AS target_title
            FROM note_links l
            WHERE l.user_id = #{userId} AND l.source_note_id = #{sourceNoteId}
            ORDER BY l.id
            """)
    List<LinkRow> listOutbound(@Param("userId") long userId, @Param("sourceNoteId") long sourceNoteId);

    /** 谁引用了本篇（入链），带来源当前标题（来源若已删则显示悬空）。 */
    @Select("""
            SELECT l.source_note_id AS target_note_id, n.title AS target_title
            FROM note_links l
            LEFT JOIN notes n ON n.id = l.source_note_id AND n.deleted_at IS NULL
            WHERE l.user_id = #{userId} AND l.target_note_id = #{targetNoteId}
            ORDER BY l.id
            """)
    List<LinkRow> listInbound(@Param("userId") long userId, @Param("targetNoteId") long targetNoteId);

    /** 关系行：targetNoteId 为 null 表示悬空。 */
    class LinkRow {

        private Long targetNoteId;
        private String targetTitle;

        public Long getTargetNoteId() {
            return targetNoteId;
        }

        public void setTargetNoteId(Long targetNoteId) {
            this.targetNoteId = targetNoteId;
        }

        public String getTargetTitle() {
            return targetTitle;
        }

        public void setTargetTitle(String targetTitle) {
            this.targetTitle = targetTitle;
        }
    }
}
