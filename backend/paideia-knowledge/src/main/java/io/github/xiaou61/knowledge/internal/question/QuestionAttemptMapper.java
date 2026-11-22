package io.github.xiaou61.knowledge.internal.question;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * WORK-033 的四张表：作答、题目关联、掌握状态、题目难度。
 *
 * <p>掌握状态与难度都用 `ON DUPLICATE KEY UPDATE`：它们是「当前值」而不是账本，
 * 逐条历史在 `question_attempts` 里，重算随时可以从那里重放。
 */
@Mapper
public interface QuestionAttemptMapper {

    @Insert("""
            INSERT INTO question_attempts
                (user_id, entry_id, block_key, question_key, fingerprint, choice_index,
                 correct, latency_ms, judged_by, created_at)
            VALUES (#{userId}, #{entryId}, #{blockKey}, #{questionKey}, #{fingerprint},
                    #{choiceIndex}, #{correct}, #{latencyMs}, #{judgedBy}, #{now})
            """)
    int insertAttempt(@Param("userId") long userId, @Param("entryId") long entryId,
                      @Param("blockKey") String blockKey, @Param("questionKey") String questionKey,
                      @Param("fingerprint") String fingerprint,
                      @Param("choiceIndex") Integer choiceIndex, @Param("correct") boolean correct,
                      @Param("latencyMs") Integer latencyMs, @Param("judgedBy") String judgedBy,
                      @Param("now") Instant now);

    /** 这一条里的全部作答，最新在前：调用方按题取第一条就是"上次选了什么"。 */
    @Select("""
            SELECT block_key, question_key, fingerprint, choice_index FROM question_attempts
            WHERE user_id = #{userId} AND entry_id = #{entryId}
            ORDER BY id DESC
            """)
    List<ChoiceRow> findChoices(@Param("userId") long userId, @Param("entryId") long entryId);

    @Select("""
            SELECT concept_id, weight FROM question_concepts
            WHERE entry_id = #{entryId} AND block_key = #{blockKey} AND question_key = #{questionKey}
            ORDER BY id
            """)
    List<LinkRow> findLinks(@Param("entryId") long entryId, @Param("blockKey") String blockKey,
                            @Param("questionKey") String questionKey);

    @Delete("""
            DELETE FROM question_concepts
            WHERE entry_id = #{entryId} AND block_key = #{blockKey} AND question_key = #{questionKey}
            """)
    int deleteLinks(@Param("entryId") long entryId, @Param("blockKey") String blockKey,
                    @Param("questionKey") String questionKey);

    /**
     * 批量取一批条目的题目—概念关联（错题本避免逐题一次 SQL）。
     *
     * <p>行里带上 {@code entry_id/block_key/question_key}，调用方按三者组合分组。
     */
    @Select("""
            <script>
            SELECT entry_id, block_key, question_key, concept_id, weight
            FROM question_concepts
            WHERE entry_id IN
            <foreach collection="entryIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY entry_id, block_key, question_key, id
            </script>
            """)
    List<KeyedLinkRow> findLinksForEntries(@Param("entryIds") List<Long> entryIds);

    record KeyedLinkRow(long entryId, String blockKey, String questionKey, long conceptId,
                        double weight) {
    }

    @Insert("""
            INSERT INTO question_concepts
                (entry_id, block_key, question_key, concept_id, weight, source, confidence, created_at)
            VALUES (#{entryId}, #{blockKey}, #{questionKey}, #{conceptId}, #{weight},
                    #{source}, #{confidence}, #{now})
            """)
    int insertLink(@Param("entryId") long entryId, @Param("blockKey") String blockKey,
                   @Param("questionKey") String questionKey, @Param("conceptId") long conceptId,
                   @Param("weight") double weight, @Param("source") String source,
                   @Param("confidence") Double confidence, @Param("now") Instant now);

    @Select("""
            SELECT user_id, concept_id, mastery, attempts, correct FROM concept_mastery_state
            WHERE user_id = #{userId}
            """)
    List<MasteryRow> findMastery(@Param("userId") long userId);

    /**
     * 专供"读-改-写"路径使用：加行锁，避免并发交卷/答题时两次读同一份旧值、互相覆盖。
     *
     * <p>没有行时拿不到锁（这也对）：此时 upsert 的 INSERT 靠唯一键兜底，
     * 后到的那次会走 ON DUPLICATE KEY UPDATE，不会丢计数。
     */
    @Select("""
            SELECT user_id, concept_id, mastery, attempts, correct FROM concept_mastery_state
            WHERE user_id = #{userId} AND concept_id = #{conceptId}
            FOR UPDATE
            """)
    Optional<MasteryRow> findMasteryOf(@Param("userId") long userId, @Param("conceptId") long conceptId);

    @Insert("""
            INSERT INTO concept_mastery_state
                (user_id, concept_id, mastery, attempts, correct, updated_at)
            VALUES (#{userId}, #{conceptId}, #{mastery}, #{attempts}, #{correct}, #{now})
            ON DUPLICATE KEY UPDATE
                mastery = VALUES(mastery), attempts = VALUES(attempts),
                correct = VALUES(correct), updated_at = VALUES(updated_at)
            """)
    int upsertMastery(@Param("userId") long userId, @Param("conceptId") long conceptId,
                      @Param("mastery") double mastery, @Param("attempts") int attempts,
                      @Param("correct") int correct, @Param("now") Instant now);

    @Select("""
            SELECT fingerprint, entry_id, block_key, question_key, rating, attempts, correct
            FROM question_difficulty WHERE fingerprint = #{fingerprint}
            """)
    Optional<RatingRow> findRating(@Param("fingerprint") String fingerprint);

    @Insert("""
            INSERT INTO question_difficulty
                (fingerprint, entry_id, block_key, question_key, rating, attempts, correct, updated_at)
            VALUES (#{fingerprint}, #{entryId}, #{blockKey}, #{questionKey}, #{rating},
                    #{attempts}, #{correct}, #{now})
            ON DUPLICATE KEY UPDATE
                rating = VALUES(rating), attempts = VALUES(attempts),
                correct = VALUES(correct), updated_at = VALUES(updated_at)
            """)
    int upsertRating(@Param("fingerprint") String fingerprint, @Param("entryId") long entryId,
                     @Param("blockKey") String blockKey, @Param("questionKey") String questionKey,
                     @Param("rating") double rating, @Param("attempts") int attempts,
                     @Param("correct") int correct, @Param("now") Instant now);

    @Select("""
            SELECT from_concept_id, to_concept_id FROM knowledge_concept_edges
            """)
    List<EdgeRow> findEdges();

    @Select("""
            SELECT entry_id, concept_id FROM knowledge_entry_concepts WHERE entry_id = #{entryId}
            """)
    List<EntryConceptRow> findEntryConcepts(@Param("entryId") long entryId);

    @Select("""
            SELECT id, name, summary FROM knowledge_concepts
            """)
    List<ConceptRow> findAllConcepts();

    // ---- 练习选题与复习联动（WORK-033 第二轮）----

    @Select("""
            <script>
            SELECT qc.entry_id, qc.block_key, qc.question_key, qd.fingerprint,
                   qd.rating, qd.attempts, qd.updated_at
            FROM question_concepts qc
            LEFT JOIN question_difficulty qd
              ON qd.entry_id = qc.entry_id AND qd.block_key = qc.block_key
             AND qd.question_key = qc.question_key
            WHERE qc.concept_id IN
              <foreach item="id" collection="conceptIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<CandidateRow> findCandidates(@Param("conceptIds") List<Long> conceptIds);

    /** 答错时把该条目的复习排期拉回明天；没有学习记录时不补建（没开始读的人不需要复习）。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE learning_progress
            SET review_stage = 0, review_due_at = #{dueAt}
            WHERE user_id = #{userId} AND entry_id = #{entryId}
            """)
    int scheduleReview(@Param("userId") long userId, @Param("entryId") long entryId,
                       @Param("dueAt") Instant dueAt);

    @Select("""
            SELECT id, user_id, entry_id, block_key, question_key, fingerprint, correct,
                   latency_ms, judged_by, created_at
            FROM question_attempts ORDER BY id DESC LIMIT #{limit}
            """)
    List<AttemptRow> recentAttempts(@Param("limit") int limit);

    @Select("""
            <script>
            SELECT qi.entry_id, qi.block_key, qi.question_key, qi.fingerprint,
                   qi.option_count, qi.difficulty_label,
                   (SELECT qa.correct FROM question_attempts qa
                      WHERE qa.user_id = #{userId} AND qa.entry_id = qi.entry_id
                        AND qa.block_key = qi.block_key AND qa.question_key = qi.question_key
                      ORDER BY qa.id DESC LIMIT 1) AS lastCorrect,
                   (SELECT COUNT(*) FROM question_attempts qa2
                      WHERE qa2.user_id = #{userId} AND qa2.entry_id = qi.entry_id
                        AND qa2.block_key = qi.block_key AND qa2.question_key = qi.question_key) AS attemptCount
            FROM question_index qi
            WHERE qi.status = 'active'
              <if test="categoryId != null"> AND qi.category_id = #{categoryId}</if>
              <if test="difficultyLabel != null and difficultyLabel != ''"> AND qi.difficulty_label = #{difficultyLabel}</if>
              <if test="conceptIds != null and conceptIds.size() > 0">
                AND EXISTS (SELECT 1 FROM question_concepts qc
                  WHERE qc.entry_id = qi.entry_id AND qc.block_key = qi.block_key
                    AND qc.question_key = qi.question_key
                    AND qc.concept_id IN
                    <foreach collection="conceptIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>)
              </if>
              <if test="onlyUndone">
                AND NOT EXISTS (SELECT 1 FROM question_attempts qa3
                  WHERE qa3.user_id = #{userId} AND qa3.entry_id = qi.entry_id
                    AND qa3.block_key = qi.block_key AND qa3.question_key = qi.question_key)
              </if>
              <if test="onlyWrong">
                AND (SELECT qa4.correct FROM question_attempts qa4
                  WHERE qa4.user_id = #{userId} AND qa4.entry_id = qi.entry_id
                    AND qa4.block_key = qi.block_key AND qa4.question_key = qi.question_key
                  ORDER BY qa4.id DESC LIMIT 1) = 0
              </if>
            ORDER BY qi.entry_id, qi.block_key, qi.question_key
            LIMIT #{limit}
            </script>
            """)
    List<PracticeCandidateRow> findPracticeCandidates(@Param("userId") long userId,
                                                      @Param("categoryId") Long categoryId,
                                                      @Param("conceptIds") List<Long> conceptIds,
                                                      @Param("difficultyLabel") String difficultyLabel,
                                                      @Param("onlyWrong") boolean onlyWrong,
                                                      @Param("onlyUndone") boolean onlyUndone,
                                                      @Param("limit") int limit);

    record CandidateRow(long entryId, String blockKey, String questionKey, String fingerprint,
                        Double rating, Integer attempts, java.time.Instant updatedAt) {
    }

    record PracticeCandidateRow(long entryId, String blockKey, String questionKey, String fingerprint,
                                int optionCount, String difficultyLabel, Boolean lastCorrect,
                                int attemptCount) {
    }

    @Select("""
            <script>
            SELECT qi.entry_id, qi.block_key, qi.question_key, qi.fingerprint,
                   qi.option_count, qi.difficulty_label
            FROM question_index qi
            JOIN question_attempts la ON la.entry_id = qi.entry_id
              AND la.block_key = qi.block_key AND la.question_key = qi.question_key
              AND la.user_id = #{userId}
              AND la.id = (SELECT MAX(qa2.id) FROM question_attempts qa2
                WHERE qa2.user_id = la.user_id AND qa2.entry_id = la.entry_id
                  AND qa2.block_key = la.block_key AND qa2.question_key = la.question_key)
            WHERE la.correct = 0 AND qi.status = 'active'
              <if test="categoryId != null">
                AND qi.category_id = #{categoryId}
              </if>
              <if test="conceptId != null">
                AND EXISTS (SELECT 1 FROM question_concepts qc
                  WHERE qc.entry_id = qi.entry_id AND qc.block_key = qi.block_key
                    AND qc.question_key = qi.question_key AND qc.concept_id = #{conceptId})
              </if>
            ORDER BY qi.entry_id, qi.block_key, qi.question_key
            LIMIT #{limit}
            </script>
            """)
    List<WrongRow> findWrongLatest(@Param("userId") long userId,
                                   @Param("categoryId") Long categoryId,
                                   @Param("conceptId") Long conceptId,
                                   @Param("limit") int limit);

    record WrongRow(long entryId, String blockKey, String questionKey, String fingerprint,
                    int optionCount, String difficultyLabel) {
    }

    record AttemptRow(long id, long userId, long entryId, String blockKey, String questionKey,
                      String fingerprint, boolean correct, Integer latencyMs, String judgedBy,
                      java.time.Instant createdAt) {
    }

    record LinkRow(long conceptId, double weight) {
    }

    record ChoiceRow(String blockKey, String questionKey, String fingerprint,
                     Integer choiceIndex) {
    }

    record MasteryRow(long userId, long conceptId, double mastery, int attempts, int correct) {
    }

    record RatingRow(String fingerprint, long entryId, String blockKey, String questionKey,
                     double rating, int attempts, int correct) {
    }

    record EdgeRow(long fromConceptId, long toConceptId) {
    }

    record EntryConceptRow(long entryId, long conceptId) {
    }

    record ConceptRow(long id, String name, String summary) {
    }
}
