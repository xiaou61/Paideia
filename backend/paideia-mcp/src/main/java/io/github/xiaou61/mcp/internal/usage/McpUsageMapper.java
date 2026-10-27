package io.github.xiaou61.mcp.internal.usage;

import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 凭据按天用量的读写（WORK-044 TASK-002）。
 *
 * <p><b>累加用一条 upsert</b>：先查再改会在两步之间留下并发窗口，而这里每次工具调用都要写一次。
 * `ON DUPLICATE KEY UPDATE` 既建行又累加，不需要事务包装。
 *
 * <p><b>没有 `user_id` 列</b>：归属只从 `mcp_access_tokens` 走。注销级联用 JOIN 删——
 * 注销是低频操作，代价可以接受；加一列反而会出现"凭据换主人时两处不一致"。
 */
@Mapper
interface McpUsageMapper {

    /** 累加一次调用。`errorDelta` 是 0 或 1。 */
    @Insert("""
            INSERT INTO mcp_token_usage (token_id, day, calls, errors, updated_at)
            VALUES (#{tokenId}, #{day}, 1, #{errorDelta}, #{now})
            ON DUPLICATE KEY UPDATE
                calls = calls + 1,
                errors = errors + #{errorDelta},
                updated_at = VALUES(updated_at)
            """)
    int increment(@Param("tokenId") long tokenId, @Param("day") LocalDate day,
                  @Param("errorDelta") int errorDelta, @Param("now") Instant now);

    /** 某天的调用次数。没有行返回 null，由服务层归一成 0。 */
    @Select("SELECT calls FROM mcp_token_usage WHERE token_id = #{tokenId} AND day = #{day}")
    Integer callsOn(@Param("tokenId") long tokenId, @Param("day") LocalDate day);

    /** 历史累计。 */
    @Select("""
            SELECT COALESCE(SUM(calls), 0) AS calls, COALESCE(SUM(errors), 0) AS errors
            FROM mcp_token_usage
            WHERE token_id = #{tokenId}
            """)
    Totals totals(@Param("tokenId") long tokenId);

    /** 注销级联：删掉这个人全部凭据的用量。 */
    @Delete("""
            DELETE u FROM mcp_token_usage u
            JOIN mcp_access_tokens t ON t.id = u.token_id
            WHERE t.user_id = #{userId}
            """)
    int deleteByUser(@Param("userId") long userId);

    /** 累计值。`long` 而不是 `int`：calls 是 INT UNSIGNED，SUM 之后会溢出 int。 */
    record Totals(long calls, long errors) {
    }
}
