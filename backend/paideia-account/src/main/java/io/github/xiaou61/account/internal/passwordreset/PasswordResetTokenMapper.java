package io.github.xiaou61.account.internal.passwordreset;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 重置口令令牌的状态访问。SQL 只写在这里。
 *
 * <p>与 {@code RefreshTokenMapper} 同一套约定：库里只有 SHA-256，明文只出现在邮件链接里。
 */
@Mapper
public interface PasswordResetTokenMapper {

    @Insert("""
            INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
            VALUES (#{userId}, #{tokenHash}, #{expiresAt})
            """)
    int insert(@Param("userId") long userId,
               @Param("tokenHash") String tokenHash,
               @Param("expiresAt") Instant expiresAt);

    /**
     * 作废除刚签发那枚之外的其余令牌。
     *
     * <p>发信成功之后才调用：发信失败时旧链接必须继续可用，否则用户手上既没有新链接、
     * 旧的也被作废，只能等 60 秒限流窗口过去再申请一次。
     */
    @Update("""
            UPDATE password_reset_tokens SET used_at = #{usedAt}
            WHERE user_id = #{userId} AND used_at IS NULL AND token_hash <> #{keepTokenHash}
            """)
    int invalidateOthersForUser(@Param("userId") long userId,
                                @Param("keepTokenHash") String keepTokenHash,
                                @Param("usedAt") Instant usedAt);

    /**
     * 原子消费一枚令牌：只有"未使用且未过期"才会被改成已使用，返回 1 的那次才算抢到。
     *
     * <p>用一条带条件的 UPDATE 而不是"先读再写"：两个并发请求拿着同一个链接时，
     * 先读再写会让两边都通过，这里只有一个能拿到 1。
     */
    @Update("""
            UPDATE password_reset_tokens SET used_at = #{now}
            WHERE token_hash = #{tokenHash} AND used_at IS NULL AND expires_at > #{now}
            """)
    int consume(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** 消费之后反查持有人；令牌已被本次请求占用，因此这里不需要再带条件。 */
    @Select("SELECT user_id FROM password_reset_tokens WHERE token_hash = #{tokenHash}")
    Optional<Long> findUserIdByHash(@Param("tokenHash") String tokenHash);

    /** 清理已过期的记录。已使用但未过期的行留着，用于回答"这枚令牌为什么不能用"。 */
    @Delete("DELETE FROM password_reset_tokens WHERE expires_at < #{before}")
    int deleteExpiredBefore(@Param("before") Instant before);
}
