package io.github.xiaou61.ai.internal.billing;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 积分预约的读写（WORK-092 TASK-005）。
 *
 * <p>{@link #lockByRoundId} 用 {@code SELECT … FOR UPDATE} 锁住这一行，让「结算 / 退回 / 恢复」三条
 * 路径在同一个 {@code round_id} 上串行：谁先拿到锁谁推进状态，后到的读到终态就幂等退出。
 */
@Mapper
interface CreditReservationMapper {

    @Insert("""
            INSERT INTO ai_credit_reservations
              (round_id, user_id, amount, status, ref_message_id, note)
            VALUES
              (#{roundId}, #{userId}, #{amount}, #{status}, #{refMessageId}, #{note})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CreditReservation row);

    @Select("""
            SELECT id, round_id, user_id, amount, status, ref_message_id, note, created_at, updated_at
            FROM ai_credit_reservations
            WHERE round_id = #{roundId}
            FOR UPDATE
            """)
    CreditReservation lockByRoundId(@Param("roundId") String roundId);

    /** 把 {@code RESERVED} 推进到终态；已不在 {@code RESERVED} 时影响 0 行（幂等）。 */
    @Update("""
            UPDATE ai_credit_reservations
            SET status = #{status}, ref_message_id = #{refMessageId}, note = #{note}
            WHERE id = #{id} AND status = 'RESERVED'
            """)
    int complete(@Param("id") long id, @Param("status") String status,
                 @Param("refMessageId") Long refMessageId, @Param("note") String note);

    /** 恢复用：找出超时仍停在 {@code RESERVED} 的预约（按创建时间，先进先出）。 */
    @Select("""
            SELECT id, round_id, user_id, amount, status, ref_message_id, note, created_at, updated_at
            FROM ai_credit_reservations
            WHERE status = 'RESERVED' AND created_at < #{before}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<CreditReservation> findStaleReserved(@Param("before") Instant before, @Param("limit") int limit);
}
