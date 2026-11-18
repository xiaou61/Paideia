package io.github.xiaou61.knowledge.internal.paper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** {@code papers}/{@code paper_items}/{@code paper_answers} 的读写（WORK-083）。 */
@Mapper
public interface PaperMapper {

    @Insert("""
            INSERT INTO papers (user_id, title, rule_json, status, created_at)
            VALUES (#{userId}, #{title}, #{ruleJson}, #{status}, #{now})
            """)
    int insertPaper(@Param("userId") long userId, @Param("title") String title,
                    @Param("ruleJson") String ruleJson, @Param("status") String status,
                    @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Insert("""
            INSERT INTO paper_items
                (paper_id, user_id, seq, entry_id, block_key, question_key, fingerprint,
                 concept_id, difficulty_label, stem_snapshot, options_json,
                 answer_index_snapshot, explanation_snapshot)
            VALUES (#{paperId}, #{userId}, #{seq}, #{entryId}, #{blockKey}, #{questionKey},
                    #{fingerprint}, #{conceptId}, #{difficultyLabel}, #{stemSnapshot},
                    #{optionsJson}, #{answerIndexSnapshot}, #{explanationSnapshot})
            """)
    int insertItem(@Param("paperId") long paperId, @Param("userId") long userId,
                   @Param("seq") int seq, @Param("entryId") long entryId,
                   @Param("blockKey") String blockKey, @Param("questionKey") String questionKey,
                   @Param("fingerprint") String fingerprint, @Param("conceptId") Long conceptId,
                   @Param("difficultyLabel") String difficultyLabel,
                   @Param("stemSnapshot") String stemSnapshot, @Param("optionsJson") String optionsJson,
                   @Param("answerIndexSnapshot") int answerIndexSnapshot,
                   @Param("explanationSnapshot") String explanationSnapshot);

    @Select("""
            SELECT id, user_id, title, status, submitted_at, correct_count, total_count, created_at
            FROM papers WHERE user_id = #{userId} ORDER BY id DESC
            """)
    List<PaperRow> listPapers(@Param("userId") long userId);

    @Select("""
            SELECT id, user_id, title, status, submitted_at, correct_count, total_count, created_at
            FROM papers WHERE id = #{id} AND user_id = #{userId}
            """)
    PaperRow findPaper(@Param("id") long id, @Param("userId") long userId);

    @Select("""
            SELECT id, paper_id, user_id, seq, entry_id, block_key, question_key, fingerprint,
                   concept_id, difficulty_label, stem_snapshot, options_json,
                   answer_index_snapshot, explanation_snapshot
            FROM paper_items WHERE paper_id = #{paperId} ORDER BY seq
            """)
    List<ItemRow> findItems(@Param("paperId") long paperId);

    @Delete("DELETE FROM paper_items WHERE paper_id = #{paperId}")
    int deleteItems(@Param("paperId") long paperId);

    @Delete("DELETE FROM papers WHERE id = #{id} AND user_id = #{userId}")
    int deletePaper(@Param("id") long id, @Param("userId") long userId);

    @Update("UPDATE papers SET total_count = #{total} WHERE id = #{paperId}")
    int updateTotals(@Param("paperId") long paperId, @Param("total") int total);

    @Insert("""
            INSERT INTO paper_answers (paper_id, user_id, item_id, choice_index, correct, created_at)
            VALUES (#{paperId}, #{userId}, #{itemId}, #{choiceIndex}, #{correct}, #{now})
            """)
    int insertAnswer(@Param("paperId") long paperId, @Param("userId") long userId,
                     @Param("itemId") long itemId, @Param("choiceIndex") Integer choiceIndex,
                     @Param("correct") boolean correct, @Param("now") Instant now);

    @Select("SELECT item_id, choice_index, correct FROM paper_answers WHERE paper_id = #{paperId}")
    List<AnswerRow> findAnswers(@Param("paperId") long paperId);

    @Delete("DELETE FROM paper_answers WHERE paper_id = #{paperId}")
    int deleteAnswers(@Param("paperId") long paperId);

    @Update("""
            UPDATE papers SET status = #{status}, correct_count = #{correct},
                total_count = #{total}, submitted_at = #{submittedAt}
            WHERE id = #{paperId}
            """)
    int markSubmitted(@Param("paperId") long paperId, @Param("status") String status,
                      @Param("correct") int correct, @Param("total") int total,
                      @Param("submittedAt") Instant submittedAt);

    @Select("""
            <script>
            SELECT qi.entry_id, qi.block_key, qi.question_key, qi.fingerprint,
                   qi.option_count, qi.difficulty_label
            FROM question_index qi
            <where>
              qi.status = 'active'
              <if test="categoryId != null"> AND qi.category_id = #{categoryId}</if>
              <if test="conceptIds != null and conceptIds.size() > 0">
                AND EXISTS (
                  SELECT 1 FROM question_concepts qc
                  WHERE qc.entry_id = qi.entry_id AND qc.block_key = qi.block_key
                    AND qc.question_key = qi.question_key
                    AND qc.concept_id IN
                    <foreach collection="conceptIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
                )
              </if>
            </where>
            ORDER BY qi.entry_id, qi.block_key, qi.question_key
            </script>
            """)
    List<CandidateRow> findCandidates(@Param("categoryId") Long categoryId,
                                      @Param("conceptIds") List<Long> conceptIds);

    @Select("""
            SELECT concept_id FROM question_concepts
            WHERE entry_id = #{entryId} AND block_key = #{blockKey} AND question_key = #{questionKey}
            ORDER BY concept_id
            """)
    List<Long> findConceptIds(@Param("entryId") long entryId, @Param("blockKey") String blockKey,
                              @Param("questionKey") String questionKey);

    record PaperRow(long id, long userId, String title, String status, Instant submittedAt,
                    Integer correctCount, Integer totalCount, Instant createdAt) {
    }

    record ItemRow(long id, long paperId, long userId, int seq, long entryId, String blockKey,
                   String questionKey, String fingerprint, Long conceptId, String difficultyLabel,
                   String stemSnapshot, String optionsJson, int answerIndexSnapshot,
                   String explanationSnapshot) {
    }

    record CandidateRow(long entryId, String blockKey, String questionKey, String fingerprint,
                        int optionCount, String difficultyLabel) {
    }

    record AnswerRow(long itemId, Integer choiceIndex, boolean correct) {
    }
}
