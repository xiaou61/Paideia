package io.github.xiaou61.ai.internal.memory;

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
 * 记忆整理任务行的读写（WORK-061）。
 *
 * <p>抢占沿用仓内既有的条件 UPDATE 手法：只有把 {@code status} 从 pending 改成 running 的
 * 那一条语句影响行数为 1，别的一律 0——即时的异步派发与中继撞上时，只有一个跑得到。
 *
 * <p>时间一律由调用方传入（应用按 UTC 算）：库会话时区是 +08:00，不用库的 {@code NOW()}。
 */
@Mapper
interface MemoryExtractionJobMapper {

    @Insert("""
            INSERT INTO ai_memory_extraction_jobs
                (user_id, conversation_id, from_message_id, to_message_id, status, attempts,
                 next_attempt_at, created_at)
            VALUES
                (#{userId}, #{conversationId}, #{fromMessageId}, #{toMessageId}, 'pending', 0,
                 #{nextAttemptAt}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MemoryExtractionJob job);

    /** 抢占：pending → running，并记一次尝试。影响行数为 1 才算抢到。 */
    @Update("""
            UPDATE ai_memory_extraction_jobs
            SET status = 'running', attempts = attempts + 1, locked_at = #{now}
            WHERE id = #{id} AND status = 'pending'
            """)
    int claim(@Param("id") long id, @Param("now") Instant now);

    @Select("""
            SELECT id, user_id, conversation_id, from_message_id, to_message_id, attempts
            FROM ai_memory_extraction_jobs
            WHERE id = #{id}
            """)
    Optional<MemoryExtractionJob> findById(@Param("id") long id);

    /** 成功：任务行消失。不留历史——留痕由 ai_runs 遥测承担。 */
    @Delete("DELETE FROM ai_memory_extraction_jobs WHERE id = #{id}")
    int delete(@Param("id") long id);

    /** 失败：回到 pending 等退避后重试；达到上限则置 failed（不再被中继拾取，行保留供诊断）。 */
    @Update("""
            UPDATE ai_memory_extraction_jobs
            SET status = #{status}, next_attempt_at = #{nextAttemptAt}, last_error = #{lastError},
                locked_at = NULL
            WHERE id = #{id}
            """)
    int reschedule(@Param("id") long id,
                   @Param("status") String status,
                   @Param("nextAttemptAt") Instant nextAttemptAt,
                   @Param("lastError") String lastError);

    /** 到期待办（中继拾取）。 */
    @Select("""
            SELECT id, user_id, conversation_id, from_message_id, to_message_id, attempts
            FROM ai_memory_extraction_jobs
            WHERE status = 'pending' AND next_attempt_at <= #{now}
            ORDER BY next_attempt_at, id
            LIMIT #{limit}
            """)
    List<MemoryExtractionJob> due(@Param("now") Instant now, @Param("limit") int limit);

    /** 卡死回收：进程崩溃留在 running 的行回到 pending，等下一次拾取。 */
    @Update("""
            UPDATE ai_memory_extraction_jobs
            SET status = 'pending', locked_at = NULL
            WHERE status = 'running' AND locked_at < #{before}
            """)
    int reclaimStuck(@Param("before") Instant before);

    /** 注销级联（用户裁决 10：级联硬删）。 */
    @Delete("DELETE FROM ai_memory_extraction_jobs WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") long userId);

    /** failed 行的保留回收：按时间批量删，别让诊断用的行无限积。 */
    @Delete("""
            DELETE FROM ai_memory_extraction_jobs
            WHERE status = 'failed' AND created_at < #{before}
            LIMIT 500
            """)
    int dropFailed(@Param("before") Instant before);
}