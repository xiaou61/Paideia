package io.github.xiaou61.ai.internal.digest;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 速递点击记录（WORK-045）。
 *
 * <p><b>为什么单独一张表而不是给 `digest_items` 加计数列</b>：用户裁决只补这一项埋点，
 * 而且要回答的不只是「总共点了多少」，还有「谁点的、哪天点的、哪些条目没人点」。
 * 明细表能答全部三个问题，计数列只能答第一个。
 *
 * <p><b>没有外键</b>：`digest_items` 会被速递自身的保留期清理掉；点击记录要独立存活，
 * 否则删内容时连同历史点击一起消失，趋势图上会凭空掉一截。
 */
@Mapper
public interface DigestClickMapper {

    /**
     * 记一次点击。
     *
     * <p>不做 upsert、不判重：这张表回答的是**点击次数**，同一人反复点就是多次。
     * 「多少人点过」用 `COUNT(DISTINCT user_id)` 单独算。
     */
    @Insert("""
            INSERT INTO digest_item_clicks (user_id, item_id, clicked_at)
            VALUES (#{userId}, #{itemId}, #{now})
            """)
    int insert(@Param("userId") long userId, @Param("itemId") long itemId, @Param("now") Instant now);
}
