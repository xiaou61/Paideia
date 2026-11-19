package io.github.xiaou61.account.internal.token;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** refresh 令牌的状态访问。SQL 只写在这里。 */
@Mapper
public interface RefreshTokenMapper {

    @Insert("""
            INSERT INTO refresh_tokens (user_id, token_hash, family_id, expires_at)
            VALUES (#{userId}, #{tokenHash}, #{familyId}, #{expiresAt})
            """)
    int insert(@Param("userId") Long userId,
               @Param("tokenHash") String tokenHash,
               @Param("familyId") String familyId,
               @Param("expiresAt") Instant expiresAt);

    @Select("""
            SELECT id, user_id, token_hash, family_id, expires_at, revoked_at
            FROM refresh_tokens WHERE token_hash = #{tokenHash}
            """)
    Optional<RefreshToken> findByHash(@Param("tokenHash") String tokenHash);

    /** 轮换：把用掉的那一条作废。带 {@code revoked_at IS NULL} 是为了并发下只成功一次。 */
    @Update("UPDATE refresh_tokens SET revoked_at = #{revokedAt} WHERE id = #{id} AND revoked_at IS NULL")
    int revokeById(@Param("id") Long id, @Param("revokedAt") Instant revokedAt);

    /** 吊销整条链：登出，以及检测到令牌重用时终止该会话。 */
    @Update("""
            UPDATE refresh_tokens SET revoked_at = #{revokedAt}
            WHERE family_id = #{familyId} AND revoked_at IS NULL
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("revokedAt") Instant revokedAt);

    /**
     * 吊销这个人**全部**会话（所有链）。用于改口令：口令都换了，旧会话不该还能续期。
     *
     * <p>只置 {@code revoked_at}、不删行：留着才能让重用检测认出"这把钥匙已经被收回了"。
     * 过期的行由 {@code deleteExpiredBefore} 定期清掉。
     */
    @Update("""
            UPDATE refresh_tokens SET revoked_at = #{revokedAt}
            WHERE user_id = #{userId} AND revoked_at IS NULL
            """)
    int revokeAllForUser(@Param("userId") long userId, @Param("revokedAt") Instant revokedAt);

    @Select("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = #{familyId} AND revoked_at IS NULL")
    int countAliveInFamily(@Param("familyId") String familyId);

    /**
     * 清理已过期的记录。
     *
     * <p>只删 {@code expires_at} 已过的：过期的 refresh 无论是否被吊销都不可能再用，
     * 留着只会让这张表无界增长（{@code idx_refresh_expires} 就是为此准备的）。
     * <b>不删"已吊销但未过期"的行</b>——那些行必须留着，重用检测靠它们才能认出
     * "这枚令牌已经被换过了"并吊销整条链。
     */
    /** 注销时把这个人所有 refresh 记录删掉：留着等于留着一把能换新令牌的钥匙。 */
    @Delete("DELETE FROM refresh_tokens WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") long userId);

    @Delete("DELETE FROM refresh_tokens WHERE expires_at < #{before}")
    int deleteExpiredBefore(@Param("before") Instant before);
}
