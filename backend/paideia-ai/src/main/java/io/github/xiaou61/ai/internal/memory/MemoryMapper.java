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
 * 记忆两路的读写。
 *
 * <p>**归属写在 SQL 里**，与 {@code EntrySummaryMapper}、{@code ConversationMapper} 同一纪律：
 * 记忆是严格私有的（用户裁决 3），漏写一处 {@code user_id} 就等于把别人的记忆读出去。
 *
 * <p>全文那路的检索按 {@code SearchQuery} 判定走 `MATCH … AGAINST`（≥ ngram 分词长度）还是
 * `LIKE`（更短，否则**永远召回为零**）。两条路都用参数绑定；`LIKE` 的通配符转义在
 * {@code SearchQuery.likePattern} 里做。
 */
@Mapper
interface MemoryMapper {

    @Insert("""
            INSERT INTO ai_memories_structured
                (user_id, kind, ref_id, content, source, conversation_id, ref_message_id, reason)
            VALUES
                (#{userId}, #{kind}, #{refId}, #{content}, #{source}, #{conversationId}, #{refMessageId}, #{reason})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertStructured(MemoryRow memory);

    @Insert("""
            INSERT INTO ai_memories_text
                (user_id, content, source, conversation_id, ref_message_id, reason)
            VALUES
                (#{userId}, #{content}, #{source}, #{conversationId}, #{refMessageId}, #{reason})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertText(MemoryRow memory);

    /**
     * 结构化记忆按**最近**取：它们都是"关于这个学习者的事实"，时间新旧就是最自然的取舍。
     *
     * <p>`offset` 只服务面板的"加载更多"（WORK-013）；召回与判重都传 0。
     */
    @Select("""
            SELECT id, user_id, kind, ref_id, content, source, conversation_id, ref_message_id, reason, created_at
            FROM ai_memories_structured
            WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MemoryRow> selectStructuredRecent(@Param("userId") long userId,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    @Select("""
            SELECT id, user_id, content, source, conversation_id, ref_message_id, reason, created_at
            FROM ai_memories_text
            WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MemoryRow> selectTextRecent(@Param("userId") long userId,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    /**
     * 全文那一路的召回（WORK-050 REQ-002）。
     *
     * <p><b>排序变了、过滤没变</b>：仍然是"命中关键词的才回来"（这一路检索的就是"他说过的某句话"，
     * 不命中就不该进提示词），但顺序从 `id DESC` 改成**相关度优先、时间只做衰减**。
     * 改动前它按插入顺序倒排，"最近说的"压过"最相关的"。
     *
     * <p>半衰期 180 天：记忆的价值随时间衰减，但不该按天塌掉。
     */
    @Select("""
            <script>
            SELECT id, user_id, content, source, conversation_id, ref_message_id, reason, created_at
            FROM ai_memories_text
            WHERE user_id = #{userId}
              <choose>
                <when test="fulltext">AND MATCH(content) AGAINST(#{keyword})</when>
                <otherwise>AND content LIKE #{likePattern}</otherwise>
              </choose>
            ORDER BY
              <if test="fulltext">MATCH(content) AGAINST(#{keyword}) DESC,</if>
              POW(0.5, DATEDIFF(#{now}, created_at) / 180) DESC,
              id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MemoryRow> searchText(@Param("userId") long userId,
                               @Param("keyword") String keyword,
                               @Param("likePattern") String likePattern,
                               @Param("fulltext") boolean fulltext,
                               @Param("now") Instant now,
                               @Param("limit") int limit);

    /**
     * 结构化那一路的召回（WORK-050 REQ-003）。
     *
     * <p><b>不按关键词过滤</b>：这一路是"关于学习者的事实"，助手在任何话题下知道"他不懂 Redis"
     * 都有用（可以主动关联）。所以关键词命中只用来**提权**，不作为门槛。
     *
     * <p>三层排序，每一层都有理由：
     * <ol>
     *   <li>命中关键词的排前面——"少而准"的核心；</li>
     *   <li>命中之间按全文相关度；</li>
     *   <li>未命中之间按 `kind` 权重 × 时间衰减。权重是"哪类事实更该被想起"：
     *       弱项（1.5）与条目自评（1.3）比偏好（1.0）更能改变回答。</li>
     * </ol>
     *
     * <p>**已知限制**：中文单字（如"锁"）在 ngram 索引上召回为零，所以 tier 1/2 恒为 0，
     * 退化成 tier 3 的权重排序。短关键词走 LIKE 的那一支由 {@code fulltext} 分支覆盖。
     */
    @Select("""
            <script>
            SELECT id, user_id, kind, ref_id, content, source, conversation_id, ref_message_id,
                   reason, created_at
            FROM ai_memories_structured
            WHERE user_id = #{userId}
            ORDER BY
              <choose>
                <when test="keyword == null"></when>
                <when test="fulltext">
                  (MATCH(content) AGAINST(#{keyword}) &gt; 0) DESC,
                  MATCH(content) AGAINST(#{keyword}) DESC,
                </when>
                <otherwise>(content LIKE #{likePattern}) DESC,</otherwise>
              </choose>
              (CASE kind
                 WHEN 'weak_topic' THEN 1.5
                 WHEN 'entry_understanding' THEN 1.3
                 WHEN 'goal' THEN 1.2
                 ELSE 1.0 END) * POW(0.5, DATEDIFF(#{now}, created_at) / 180) DESC,
              id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MemoryRow> selectStructuredRelevant(@Param("userId") long userId,
                                             @Param("keyword") String keyword,
                                             @Param("likePattern") String likePattern,
                                             @Param("fulltext") boolean fulltext,
                                             @Param("now") Instant now,
                                             @Param("limit") int limit);

    /**
     * 刷新"最近一次被召回"（WORK-050 REQ-005）。
     *
     * <p>`GREATEST` 而不是直接赋值：并发召回时两次调用的 `now` 可能先后颠倒，
     * 直接赋值会把时间**往回写**，那样淘汰任务会误判它已经很久没用过。
     * 条件里带 `user_id` 与不变式一致——id 列表来自本人，但多一道不算成本。
     */
    @Update("""
            <script>
            UPDATE ai_memories_structured
            SET last_used_at = GREATEST(COALESCE(last_used_at, #{now}), #{now})
            WHERE user_id = #{userId} AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int touchStructured(@Param("userId") long userId, @Param("ids") List<Long> ids,
                        @Param("now") Instant now);

    @Update("""
            <script>
            UPDATE ai_memories_text
            SET last_used_at = GREATEST(COALESCE(last_used_at, #{now}), #{now})
            WHERE user_id = #{userId} AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int touchText(@Param("userId") long userId, @Param("ids") List<Long> ids,
                  @Param("now") Instant now);

    /**
     * 按时间淘汰（WORK-050 REQ-004）。
     *
     * <p>判据是 `COALESCE(last_used_at, created_at)`：**被持续召回的记忆不会被删**，
     * 哪怕它很旧。"记了但从来没被用上"的才会过期——这正是"少而准"想要的收敛方向。
     *
     * <p>`LIMIT` 分批：一次删太多会长时间持锁，而删除不急。删不完下次接着删。
     */
    @Delete("""
            DELETE FROM ai_memories_structured
            WHERE kind = #{kind} AND COALESCE(last_used_at, created_at) < #{before}
            LIMIT #{limit}
            """)
    int purgeStructured(@Param("kind") String kind, @Param("before") Instant before,
                        @Param("limit") int limit);

    @Delete("""
            DELETE FROM ai_memories_text
            WHERE COALESCE(last_used_at, created_at) < #{before}
            LIMIT #{limit}
            """)
    int purgeText(@Param("before") Instant before, @Param("limit") int limit);

    /**
     * 逐条删除（用户裁决 13）。
     *
     * <p>两路各一条语句：id 在两张表里各自独立，所以调用方必须说明删的是哪一路。
     * 归属条件同样在 SQL 里——否则删别人的记忆只是"传个 id"的事。
     */
    @Delete("DELETE FROM ai_memories_structured WHERE id = #{id} AND user_id = #{userId}")
    int deleteStructured(@Param("id") long id, @Param("userId") long userId);

    @Delete("DELETE FROM ai_memories_text WHERE id = #{id} AND user_id = #{userId}")
    int deleteText(@Param("id") long id, @Param("userId") long userId);

    /**
     * 覆盖一条已存在的记忆（WORK-013 的 {@code UPDATE} 动作：同一件事说第二次不该多出一条）。
     *
     * <p>覆盖正文与理由，并把 `source` 改成写入方给的值——写这句内容的手换了（见 {@link MemoryRow}）。
     * 归属条件仍在 SQL 里：模型给的 id 是**不可信输入**，它可能来自幻觉，可能指向别人的行。
     *
     * @return 影响行数；0 表示这条不存在或不属于该用户（当作"没这条"处理，不报错）
     */
    @Update("""
            UPDATE ai_memories_structured
            SET content = #{content}, source = #{source}, reason = #{reason},
                conversation_id = #{conversationId}, ref_message_id = #{refMessageId}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateStructured(MemoryRow memory);

    @Update("""
            UPDATE ai_memories_text
            SET content = #{content}, source = #{source}, reason = #{reason},
                conversation_id = #{conversationId}, ref_message_id = #{refMessageId}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateText(MemoryRow memory);

    /**
     * 自评桥的写入（WORK-010 T-08）：按 `(user_id, kind, ref_id)` **覆盖**，不追加。
     *
     * <p>依赖 V14 加的 `uk_ai_memory_structured_ref`。`ON DUPLICATE KEY UPDATE` 而不是
     * "先查后写"：一条语句既原子，也不需要事务包装（两次改自评并发时不会插出两条）。
     *
     * <p>这里**显式把 source 写成 auto**：内容由系统按自评生成，不是用户手写的那句话。
     */
    @Insert("""
            INSERT INTO ai_memories_structured
                (user_id, kind, ref_id, content, source)
            VALUES
                (#{userId}, #{kind}, #{refId}, #{content}, 'auto')
            ON DUPLICATE KEY UPDATE
                content = VALUES(content),
                source = VALUES(source),
                reason = NULL
            """)
    int upsertStructuredByRef(@Param("userId") long userId,
                              @Param("kind") String kind,
                              @Param("refId") long refId,
                              @Param("content") String content);

    /** 取消自评时删掉那条记忆（按 ref 定位，不需要记住它的 id）。 */
    @Delete("""
            DELETE FROM ai_memories_structured
            WHERE user_id = #{userId} AND kind = #{kind} AND ref_id = #{refId}
            """)
    int deleteStructuredByRef(@Param("userId") long userId,
                              @Param("kind") String kind,
                              @Param("refId") long refId);

    /** 清空一路（REQ-009 的"清空并关闭"）。返回删掉的条数，界面据此说清清掉了几条。 */
    @Delete("DELETE FROM ai_memories_structured WHERE user_id = #{userId}")
    int clearStructured(@Param("userId") long userId);

    @Delete("DELETE FROM ai_memories_text WHERE user_id = #{userId}")
    int clearText(@Param("userId") long userId);
}
