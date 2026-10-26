package io.github.xiaou61.knowledge.internal.track;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 学习轨道的读写（WORK-024）。
 *
 * <p>**只读**：轨道与关卡由内容导入脚本（`scripts/import-course.mjs`）写入，
 * 学习者端不需要写它们。管理端维护是以后的事，本项不做——见 tasks 的非目标。
 */
@Mapper
public interface TrackMapper {

    @Select("""
            SELECT id, slug, name, summary, kind, sort_order, mode, theory_track_slug
            FROM learning_tracks
            ORDER BY sort_order, id
            """)
    List<Track> findAll();

    @Select("""
            SELECT id, slug, name, summary, kind, sort_order, mode, theory_track_slug
            FROM learning_tracks WHERE slug = #{slug}
            """)
    Track findBySlug(@Param("slug") String slug);

    /**
     * 一条轨道的全部关卡，按 seq。
     *
     * <p>连条目一起取（标题要显示在地图上），**不取正文**：地图页不需要正文，
     * 一次拉几十篇正文会白占带宽；正文在进关卡时单独取。
     */
    @Select("""
            SELECT s.id, s.track_id, s.entry_id, s.seq, s.chapter, s.requires_entry_id,
                   s.level, e.title
            FROM learning_track_stages s
            JOIN knowledge_entries e ON e.id = s.entry_id
            WHERE s.track_id = #{trackId}
            ORDER BY s.seq
            """)
    List<Stage> findStages(@Param("trackId") long trackId);

    @Insert("""
            INSERT INTO learning_tracks
                (slug, name, summary, kind, sort_order, mode, theory_track_slug, created_at, updated_at)
            VALUES (#{slug}, #{name}, #{summary}, #{kind}, #{sortOrder}, #{mode}, #{theoryTrackSlug},
                    #{now}, #{now})
            ON DUPLICATE KEY UPDATE
                name = VALUES(name), summary = VALUES(summary), kind = VALUES(kind),
                sort_order = VALUES(sort_order), mode = VALUES(mode),
                theory_track_slug = VALUES(theory_track_slug), updated_at = VALUES(updated_at)
            """)
    int upsertTrack(@Param("slug") String slug, @Param("name") String name,
                    @Param("summary") String summary, @Param("kind") String kind,
                    @Param("sortOrder") int sortOrder, @Param("mode") String mode,
                    @Param("theoryTrackSlug") String theoryTrackSlug, @Param("now") Instant now);

    /**
     * 整体替换前清空一条轨道的关卡。
     *
     * <p>不能只靠 upsert：唯一键是 {@code (track_id, seq)}，重排时新关卡按 seq 覆盖掉别人、
     * 末尾那关被挤掉（实测 14 篇只落 13 关）。关卡是内容的投影不是用户数据——没有表引用
     * {@code stage.id}，学习进度按 {@code entry_id} 记，所以删了重建是安全的。
     */
    @Delete("DELETE FROM learning_track_stages WHERE track_id = #{trackId}")
    int deleteStages(@Param("trackId") long trackId);

    @Insert("""
            INSERT INTO learning_track_stages
                (track_id, entry_id, seq, chapter, requires_entry_id, level, created_at)
            VALUES (#{trackId}, #{entryId}, #{seq}, #{chapter}, #{requiresEntryId}, #{level}, #{now})
            ON DUPLICATE KEY UPDATE
                seq = VALUES(seq), chapter = VALUES(chapter),
                requires_entry_id = VALUES(requires_entry_id), level = VALUES(level)
            """)
    int upsertStage(@Param("trackId") long trackId, @Param("entryId") long entryId,
                    @Param("seq") int seq, @Param("chapter") String chapter,
                    @Param("requiresEntryId") Long requiresEntryId,
                    @Param("level") String level, @Param("now") Instant now);

    /**
     * 一条 entry 属于哪些轨道（只取判定需要的两列）。
     *
     * <p>通过判定要按轨道类型分支（WORK-038）：课程仍要"动手 + 小测"，理论只认小测，
     * 实战只认动手题。同一 entry 可能在多条轨道里，调用方取最严的那套。
     */
    @Select("""
            SELECT DISTINCT t.kind, t.mode
            FROM learning_track_stages s
            JOIN learning_tracks t ON t.id = s.track_id
            WHERE s.entry_id = #{entryId}
            """)
    List<EntryTrack> findTracksOfEntry(@Param("entryId") long entryId);

    /** 一条轨道：路径标识、名字、一句话介绍，以及理论/实战与配对引用（WORK-038）。 */
    record Track(long id, String slug, String name, String summary, String kind, int sortOrder,
                 String mode, String theoryTrackSlug) {
    }

    /** entry 所属轨道的判定相关列。 */
    record EntryTrack(String kind, String mode) {
    }

    /** 一个关卡：地图上的一格。 */
    record Stage(long id, long trackId, long entryId, int seq, String chapter, Long requiresEntryId,
                 String level, String title) {
    }
}
