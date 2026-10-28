package io.github.xiaou61.ai.internal.memory;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 候选记忆的存取（WORK-050 TASK-009）。
 *
 * <p>与 {@link MemoryMapper} 同一纪律：**归属条件全在 SQL 里**。候选的 id 会从 URL 进来，
 * 那是最典型的不可信输入——拿别人的 id 点"接受"不该有任何效果。
 */
@Mapper
interface MemoryCandidateMapper {

    @Insert("""
            INSERT INTO ai_memory_candidates
                (user_id, store, kind, ref_id, content, content_bytes, source, conversation_id,
                 ref_message_id, reason, status, decision_reason, created_at, expires_at)
            VALUES
                (#{userId}, #{store}, #{kind}, #{refId}, #{content}, #{contentBytes}, #{source},
                 #{conversationId}, #{refMessageId}, #{reason}, #{status}, #{decisionReason},
                 #{createdAt}, #{expiresAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MemoryCandidateRow row);

    @Select("""
            SELECT id, user_id, store, kind, ref_id, content, content_bytes, source, conversation_id,
                   ref_message_id, reason, status, decision_reason, created_at, expires_at
            FROM ai_memory_candidates
            WHERE user_id = #{userId} AND status = #{status}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MemoryCandidateRow> list(@Param("userId") long userId, @Param("status") String status,
                                  @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT id, user_id, store, kind, ref_id, content, content_bytes, source, conversation_id,
                   ref_message_id, reason, status, decision_reason, created_at, expires_at
            FROM ai_memory_candidates
            WHERE user_id = #{userId} AND id = #{id}
            """)
    MemoryCandidateRow find(@Param("userId") long userId, @Param("id") long id);

    /**
     * 并发闸：`WHERE status='pending'` 让"两个人同时点接受"只有一个成功。
     * 影响 0 行说明别人已经处理过——调用方据此返回冲突，而不是再写一条正式记忆。
     */
    @Update("""
            UPDATE ai_memory_candidates SET status = 'accepted'
            WHERE user_id = #{userId} AND id = #{id} AND status = 'pending'
            """)
    int markAccepted(@Param("userId") long userId, @Param("id") long id);

    @Update("""
            UPDATE ai_memory_candidates
            SET status = 'rejected', decision_reason = 'user', expires_at = #{keepUntil}
            WHERE user_id = #{userId} AND id = #{id} AND status = 'pending'
            """)
    int markRejected(@Param("userId") long userId, @Param("id") long id,
                     @Param("keepUntil") Instant keepUntil);

    @Delete("""
            DELETE FROM ai_memory_candidates
            WHERE status IN ('pending', 'rejected', 'filtered') AND expires_at < #{now}
            LIMIT #{limit}
            """)
    int dropExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Delete("DELETE FROM ai_memory_candidates WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") long userId);
}
