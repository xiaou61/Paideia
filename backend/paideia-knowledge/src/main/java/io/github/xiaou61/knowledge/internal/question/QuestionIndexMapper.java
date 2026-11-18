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

    @Select("""
            SELECT rating, attempts FROM question_difficulty WHERE fingerprint = #{fingerprint}
            """)
    DifficultyRow findDifficulty(@Param("fingerprint") String fingerprint);

    @Select("SELECT COUNT(*) FROM question_index")
    long countAll();

    @Select("SELECT COUNT(*) FROM question_index WHERE entry_id = #{entryId}")
    long countByEntry(@Param("entryId") long entryId);

    @Delete("""
            <script>
            DELETE FROM question_index
            <if test="ids != null and ids.size() > 0">
              WHERE entry_id NOT IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            </script>
            """)
    int deleteOrphans(@Param("ids") List<Long> ids);

    record IndexRow(String questionKey, String fingerprint, String difficultyLabel) {
    }

    record DifficultyRow(BigDecimal rating, Integer attempts) {
    }
}
