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
import org.apache.ibatis.annotations.Update;

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

    /**
     * 确保 {@code (user_id, entry_id)} 一定有一行，供并发写入前先加行锁。
     *
     * <p>已存在时是空操作（{@code id = id}），不存在时插入一份空记录（空 JSON、未通过）。
     * 必须在同一事务里紧跟 {@link #lockOne}，否则锁不住"还不存在的行"。
     * 用 upsert 而不是"先查再插"：后者在并发首写时仍会撞唯一键。
     */
    @Insert("""
            INSERT INTO learning_progress
                (user_id, entry_id, tour_step, challenges_json, quizzes_json, status, review_stage)
            VALUES
                (#{userId}, #{entryId}, 0, '{}', '{}', 'in_progress', 0)
            ON DUPLICATE KEY UPDATE id = id
            """)
    int ensureRow(@Param("userId") Long userId, @Param("entryId") Long entryId);

    /**
     * 带行锁读当前值（{@code SELECT … FOR UPDATE}）。
     *
     * <p>合并必须基于"被锁住的最新值"，否则两个不同块的并发更新会各自按旧快照合并，
     * 后写整体覆盖先写（丢更新）。调用前须先 {@link #ensureRow}。
     */
    @Select("""
            SELECT id, user_id, entry_id, tour_step, challenges_json, quizzes_json, status,
                   passed_at, review_stage, review_due_at, created_at, updated_at
            FROM learning_progress
            WHERE user_id = #{userId} AND entry_id = #{entryId}
            FOR UPDATE
            """)
    Optional<LearningProgressRow> lockOne(@Param("userId") Long userId, @Param("entryId") Long entryId);

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
     *
     * <p>line_kind 在**分类**上（V17），不在条目上：这里 JOIN 到分类取。修的是 61b005d1
     * 把 N+1 改 JOIN 时写成 `e.line_kind` 的错误引用——列不存在，整条查询报
     * 「Unknown column」，`/learning/graph` 一系接口直接 500（2026-11-02 全量跑抓出）。
     *
     * <p>分类用 **LEFT JOIN**：`knowledge_entries.category_id` 允许 NULL（V3「NULL = 未分类」），
     * 内连接会把未分类的已发布条目从「我在学什么」里整行丢掉（2026-11-03 审查）。此时 line_kind 为 NULL。
     */
    @Select("""
            SELECT p.id, p.user_id, p.entry_id, p.tour_step, p.challenges_json, p.quizzes_json, p.status,
                   p.passed_at, p.review_stage, p.review_due_at, p.created_at, p.updated_at,
                   e.title, c.line_kind
            FROM learning_progress p
            JOIN knowledge_entries e ON e.id = p.entry_id
            LEFT JOIN knowledge_categories c ON c.id = e.category_id
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

    /**
     * 按主键更新一行（合并已在服务层基于行锁后的最新值算好）。
     *
     * <p>用 id 定位并同时校验 {@code user_id}，与 upsert 的整行覆盖不同：这里只覆盖既有行，
     * 不会因为并发插入产生第二行。行锁由调用方在事务内先取（{@link #lockOne}）。
     */
    @Update("""
            UPDATE learning_progress
            SET tour_step = #{tourStep},
                challenges_json = #{challengesJson},
                quizzes_json = #{quizzesJson},
                status = #{status},
                passed_at = #{passedAt,jdbcType=TIMESTAMP},
                review_stage = #{reviewStage},
                review_due_at = #{reviewDueAt,jdbcType=TIMESTAMP}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateById(LearningProgressRow row);

    @Delete("""
            DELETE FROM learning_progress WHERE user_id = #{userId} AND entry_id = #{entryId}
            """)
    int delete(@Param("userId") Long userId, @Param("entryId") Long entryId);
}
