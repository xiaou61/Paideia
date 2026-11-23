package io.github.xiaou61.mcp.internal.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * 一批凭据的历史累计（列表页用）。
     *
     * <p>**为什么不逐条调 {@link #totals}**：列表页一行一条 SQL，20 条凭据就是 40 次往返，
     * 实测 34 条时列表要 3.6 秒——慢到 e2e 的 5 秒断言都会红（2026-11-23）。一次 IN 查完即可。
     * 调用方要保证 `tokenIds` 非空（空 IN () 是语法错误）。
     */
    @Select("""
            <script>
            SELECT token_id AS tokenId, COALESCE(SUM(calls), 0) AS calls, COALESCE(SUM(errors), 0) AS errors
            FROM mcp_token_usage
            WHERE token_id IN
            <foreach collection="tokenIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY token_id
            </script>
            """)
    List<TokenTotals> totalsFor(@Param("tokenIds") List<Long> tokenIds);

    /** 一批凭据在某一天的调用次数（列表页用），语义同 {@link #callsOn}，只是批量。 */
    @Select("""
            <script>
            SELECT token_id AS tokenId, calls
            FROM mcp_token_usage
            WHERE day = #{day} AND token_id IN
            <foreach collection="tokenIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<TokenCalls> callsOnFor(@Param("tokenIds") List<Long> tokenIds, @Param("day") LocalDate day);

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

    record TokenTotals(long tokenId, long calls, long errors) {
    }

    record TokenCalls(long tokenId, int calls) {
    }
}
