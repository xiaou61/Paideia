package io.github.xiaou61.knowledge.internal.plan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 计划项的读写（WORK-053 阶段二）。
 *
 * <p><b>每一条 SQL 都带 {@code user_id}</b>：归属靠数据过滤，不靠调用方记得过滤。
 * 写入的 `user_id` 只来自令牌。
 */
@Mapper
public interface LearningPlanMapper {

    @Select("""
            SELECT id, user_id, plan_date, goal, reason, done_at
            FROM learning_plan_items
            WHERE user_id = #{userId} AND plan_date = #{date}
            ORDER BY id ASC
            """)
    List<PlanRow> findByDate(@Param("userId") long userId, @Param("date") LocalDate date);

    @Insert("""
            INSERT INTO learning_plan_items (user_id, plan_date, goal, reason, done_at, created_at, updated_at)
            VALUES (#{userId}, #{date}, #{goal}, #{reason}, NULL, #{now}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insert(@Param("userId") long userId, @Param("date") LocalDate date,
               @Param("goal") String goal, @Param("reason") String reason,
               @Param("now") Instant now, @Param("row") PlanRow row);

    /** 只改"做没做"，顺手记完成时间；`user_id` 命中 0 行就是不属于我。 */
    @Update("""
            UPDATE learning_plan_items
            SET done_at = #{doneAt}, updated_at = #{now}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int setDone(@Param("userId") long userId, @Param("id") long id,
                @Param("doneAt") Instant doneAt, @Param("now") Instant now);

    @Delete("DELETE FROM learning_plan_items WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("userId") long userId, @Param("id") long id);
}
