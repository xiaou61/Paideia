package io.github.xiaou61.knowledge.internal.learning;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 学习记录访问（WORK-020）。
 *
 * <p><b>每一条查询都带 {@code user_id}</b>，与自评同一纪律：隔离靠数据过滤，不靠调用方记得过滤。
 * 入口 {@code PUT/DELETE} 的 user_id 只来自令牌。
 */
@Mapper
public interface LearningProgressMapper {

    @Select("""
            SELECT id, user_id, entry_id, tour_step, challenges_json, quizzes_json, status,
                   passed_at, review_stage, review_due_at, created_at, updated_at
            FROM learning_progress
            WHERE user_id = #{userId} AND entry_id = #{entryId}
            """)
    Optional<LearningProgressRow> findOne(@Param("userId") Long userId, @Param("entryId") Long entryId);

    /** 某个人的全部记录（含已下架条目），供线的统计用。 */
    @Select("""
            SELECT id, user_id, entry_id, tour_step, challenges_json, quizzes_json, status,
                   passed_at, review_stage, review_due_at, created_at, updated_at
            FROM learning_progress
            WHERE user_id = #{userId}
            """)
    List<LearningProgressRow> findByUser(@Param("userId") Long userId);

    /**
     * 只列**仍已发布**的条目——下架/删除的条目不应出现在"我在学什么"里，
     * 而记录行本身保留（条目重新发布后进度还在）。
     */
    @Select("""
            SELECT p.id, p.user_id, p.entry_id, p.tour_step, p.challenges_json, p.quizzes_json, p.status,
                   p.passed_at, p.review_stage, p.review_due_at, p.created_at, p.updated_at,
                   e.title, e.line_kind
            FROM learning_progress p
            JOIN knowledge_entries e ON e.id = p.entry_id
            WHERE p.user_id = #{userId} AND e.status = 'published'
            ORDER BY p.updated_at DESC, p.entry_id DESC
            """)
    List<LearningProgressRow> findVisibleByUser(@Param("userId") Long userId);

    /**
     * 整行覆盖写（合并已在服务层算好）。
     *
     * <p>用 upsert 而不是"先判存在再 INSERT/UPDATE"：后者在并发下会撞唯一键。
     * 合并语义（分数取 max）在服务层做，因为要看旧值；两个并发请求写的是同一份合并结果，
     * 丢一次不会造成损失。
     */
    @Insert("""
            INSERT INTO learning_progress
                (user_id, entry_id, tour_step, challenges_json, quizzes_json, status,
                 passed_at, review_stage, review_due_at)
            VALUES
                (#{userId}, #{entryId}, #{tourStep}, #{challengesJson}, #{quizzesJson}, #{status},
                 #{passedAt,jdbcType=TIMESTAMP}, #{reviewStage}, #{reviewDueAt,jdbcType=TIMESTAMP}) AS incoming
            ON DUPLICATE KEY UPDATE
                tour_step = incoming.tour_step,
                challenges_json = incoming.challenges_json,
                quizzes_json = incoming.quizzes_json,
                status = incoming.status,
                passed_at = incoming.passed_at,
                review_stage = incoming.review_stage,
                review_due_at = incoming.review_due_at
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(LearningProgressRow row);

    @Delete("""
            DELETE FROM learning_progress WHERE user_id = #{userId} AND entry_id = #{entryId}
            """)
    int delete(@Param("userId") Long userId, @Param("entryId") Long entryId);
}
