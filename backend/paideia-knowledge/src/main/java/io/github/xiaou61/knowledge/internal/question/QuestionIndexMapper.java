package io.github.xiaou61.knowledge.internal.question;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@code question_index} 的读写（WORK-083 TASK-001）。 */
@Mapper
public interface QuestionIndexMapper {

    @Insert("""
            INSERT INTO question_index
                (entry_id, category_id, block_key, question_key, fingerprint,
                 option_count, difficulty_label, status, refreshed_at)
            VALUES (#{entryId}, #{categoryId}, #{blockKey}, #{questionKey}, #{fingerprint},
                    #{optionCount}, #{difficultyLabel}, 'active', #{now})
            ON DUPLICATE KEY UPDATE
                category_id = VALUES(category_id),
                fingerprint = VALUES(fingerprint),
                option_count = VALUES(option_count),
                difficulty_label = VALUES(difficulty_label),
                status = 'active',
                refreshed_at = VALUES(refreshed_at)
            """)
    int upsert(@Param("entryId") long entryId,
               @Param("categoryId") Long categoryId,
               @Param("blockKey") String blockKey,
               @Param("questionKey") String questionKey,
               @Param("fingerprint") String fingerprint,
               @Param("optionCount") int optionCount,
               @Param("difficultyLabel") String difficultyLabel,
               @Param("now") Instant now);

    @Delete("DELETE FROM question_index WHERE entry_id = #{entryId} AND question_key = #{questionKey}")
    int deleteByEntryAndKey(@Param("entryId") long entryId, @Param("questionKey") String questionKey);

    @Select("""
            SELECT question_key, fingerprint, difficulty_label
            FROM question_index WHERE entry_id = #{entryId}
            """)
    List<IndexRow> listIdentities(@Param("entryId") long entryId);

    @Select("SELECT COUNT(*) FROM question_index")
    long countAll();

    @Select("SELECT COUNT(*) FROM question_index WHERE entry_id = #{entryId}")
    long countByEntry(@Param("entryId") long entryId);

    /** 一次取回一批指纹的难度校准（全量重建时按条目批量查，避免逐题往返）。 */
    @Select("""
            <script>
            SELECT fingerprint, rating, attempts FROM question_difficulty
            WHERE fingerprint IN
            <foreach collection="fingerprints" item="fp" open="(" separator="," close=")">#{fp}</foreach>
            </script>
            """)
    List<DifficultyRow> findDifficulties(@Param("fingerprints") List<String> fingerprints);

    /**
     * 清空整张派生表。
     *
     * <p>刻意与 {@link #deleteOrphans(List)} 分开：这个动作等于"索引表即将被完全重建"，
     * 只该在明确知道没有任何已发布条目时发生，不能由"传了个空集合"隐式触发。
     */
    @Delete("DELETE FROM question_index")
    int deleteAll();

    @Delete("""
            <script>
            DELETE FROM question_index
            WHERE entry_id NOT IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteOrphans(@Param("ids") List<Long> ids);

    record IndexRow(String questionKey, String fingerprint, String difficultyLabel) {
    }

    record DifficultyRow(String fingerprint, BigDecimal rating, Integer attempts) {
    }
}
