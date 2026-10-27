package io.github.xiaou61.knowledge.internal.entry;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 搜索记录（WORK-045）。
 *
 * <p>回答的是「用户在搜什么、**搜不到什么**」：`result_count = 0` 的那些行是最有价值的
 * 内容缺口信号。此前 `q` 参数只进 SQL，从不落库。
 */
@Mapper
public interface SearchLogMapper {

    @Insert("""
            INSERT INTO knowledge_search_log (user_id, keyword, result_count, searched_at)
            VALUES (#{userId}, #{keyword}, #{resultCount}, #{now})
            """)
    int insert(@Param("userId") long userId,
               @Param("keyword") String keyword,
               @Param("resultCount") int resultCount,
               @Param("now") Instant now);
}
