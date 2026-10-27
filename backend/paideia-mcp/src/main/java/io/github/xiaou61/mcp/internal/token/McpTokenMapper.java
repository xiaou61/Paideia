package io.github.xiaou61.mcp.internal.token;

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
 * PAT 的读写。
 *
 * <p><b>归属写在 SQL 里</b>：吊销与列表都带 `user_id`，拿不到就是 0 行——服务层据此返回统一 404。
 * 校验那一条按 `token_hash` 查（有唯一索引），不按 user 查：调用时还不知道是谁。
 */
@Mapper
interface McpTokenMapper {

    @Insert("""
            INSERT INTO mcp_access_tokens (user_id, name, token_prefix, token_hash, scopes, toolsets,
                                           daily_call_limit, expires_at, created_at)
            VALUES (#{userId}, #{name}, #{tokenPrefix}, #{tokenHash}, #{scopes}, #{toolsets},
                    #{dailyCallLimit}, #{expiresAt}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(McpAccessToken token);

    @Select("""
            SELECT id, user_id, name, token_prefix, token_hash, scopes, toolsets, daily_call_limit,
                   expires_at, last_used_at, revoked_at, created_at
            FROM mcp_access_tokens
            WHERE token_hash = #{tokenHash}
            """)
    McpAccessToken findByHash(@Param("tokenHash") String tokenHash);

    @Select("""
            SELECT id, user_id, name, token_prefix, token_hash, scopes, toolsets, daily_call_limit,
                   expires_at, last_used_at, revoked_at, created_at
            FROM mcp_access_tokens
            WHERE user_id = #{userId}
            ORDER BY created_at DESC
            """)
    List<McpAccessToken> listByUser(@Param("userId") long userId);

    /**
     * 未吊销的凭据数量（WORK-044）。
     *
     * <p><b>只数未吊销的</b>：吊销过的凭据不该占名额，否则用户每次换设备都得先清理历史。
     */
    @Select("SELECT COUNT(*) FROM mcp_access_tokens WHERE user_id = #{userId} AND revoked_at IS NULL")
    int countActiveByUser(@Param("userId") long userId);

    /** 吊销。返回 0 表示不存在或不是本人的——两种都当同一条 404。 */
    @Update("""
            UPDATE mcp_access_tokens
            SET revoked_at = #{revokedAt}
            WHERE id = #{id} AND user_id = #{userId} AND revoked_at IS NULL
            """)
    int revoke(@Param("id") long id, @Param("userId") long userId, @Param("revokedAt") Instant revokedAt);

    /**
     * 记录最后使用时间。
     *
     * <p>**节流在服务层**：每个请求都写一次会让这张表成为热点，而"最后用没用到"只需要分钟级精度。
     */
    @Update("""
            UPDATE mcp_access_tokens
            SET last_used_at = #{lastUsedAt}
            WHERE id = #{id}
            """)
    int touchLastUsed(@Param("id") long id, @Param("lastUsedAt") Instant lastUsedAt);

    /** 注销级联：这个人的全部凭据。**硬删**。 */
    @Delete("DELETE FROM mcp_access_tokens WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") long userId);
}
