package io.github.xiaou61.knowledge.internal.entry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 知识条目访问。
 *
 * <p>SQL 只写在这里（沿用本项目 {@code @Mapper} + 注解/文本块的写法，不用 XML）。
 *
 * <p><b>学习者侧的每一条查询都在 SQL 里写死 {@code status = 'published'}</b>，
 * 不在控制器里"查完再判断"。这样漏改一处的后果只是少一个接口，而不是把草稿泄露出去。
 *
 * <p>检索走两条路（由 {@code SearchQuery} 判定），都用参数绑定：
 * 长查询走 `MATCH … AGAINST (… IN BOOLEAN MODE)`（ngram 全文索引），短查询退回 `LIKE`。
 *
 * <p><b>2026-11-10 之前是自然语言模式</b>：ngram 下它的语义是"任意一个二字片段命中就算命中"，
 * 搜「不存在的词啊」能因"存在"召回 209 条。改成 BOOLEAN 模式后，检索串由
 * {@code SearchQuery.booleanMode} 生成——**每个词加引号做短语匹配、词之间用 `+` 要求同时出现**。
 * 用户输入的 `+ - * "` 在那一步就被处理掉了，不会当语法解析。
 */
@Mapper
public interface EntryMapper {

    /** 见 {@link #findLineKindOfEntry}。抽成常量只因为注解里的文本块与引号太容易写坏。 */
    String ENTRY_LINE_KIND_SQL = "SELECT c.line_kind FROM knowledge_entries e "
            + "JOIN knowledge_categories c ON c.id = e.category_id WHERE e.id = #{id}";

    @Select("""
            <script>
            SELECT id, category_id, sort_order, title, status, published_at, updated_at,
                   LEFT(body, 200) AS excerpt
            FROM knowledge_entries
            WHERE status = 'published'
            <if test="categoryIds != null">
              AND category_id IN
              <foreach item="cid" collection="categoryIds" open="(" separator="," close=")">#{cid}</foreach>
            </if>
            <if test="keyword != null">
              <choose>
                <when test="fulltext">AND MATCH(title, body) AGAINST(#{booleanText} IN BOOLEAN MODE)</when>
                <otherwise>AND (title LIKE #{likePattern} OR body LIKE #{likePattern})</otherwise>
              </choose>
            </if>
            <choose>
              <when test="byChapter">ORDER BY sort_order ASC, published_at ASC, id ASC</when>
              <otherwise>ORDER BY published_at DESC, id DESC</otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Entry> pagePublished(@Param("categoryIds") List<Long> categoryIds,
                              @Param("keyword") String keyword,
                              @Param("booleanText") String booleanText,
                              @Param("fulltext") boolean fulltext,
                              @Param("likePattern") String likePattern,
                              @Param("byChapter") boolean byChapter,
                              @Param("limit") int limit,
                              @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM knowledge_entries
            WHERE status = 'published'
            <if test="categoryIds != null">
              AND category_id IN
              <foreach item="cid" collection="categoryIds" open="(" separator="," close=")">#{cid}</foreach>
            </if>
            <if test="keyword != null">
              <choose>
                <when test="fulltext">AND MATCH(title, body) AGAINST(#{booleanText} IN BOOLEAN MODE)</when>
                <otherwise>AND (title LIKE #{likePattern} OR body LIKE #{likePattern})</otherwise>
              </choose>
            </if>
            </script>
            """)
    long countPublished(@Param("categoryIds") List<Long> categoryIds,
                        @Param("keyword") String keyword,
                        @Param("booleanText") String booleanText,
                        @Param("fulltext") boolean fulltext,
                        @Param("likePattern") String likePattern);

    @Select("""
            SELECT id, category_id, sort_order, title, body, source_url, fingerprint, status,
                   published_at, created_at, updated_at
            FROM knowledge_entries WHERE id = #{id} AND status = 'published'
            """)
    Optional<Entry> findPublishedById(@Param("id") Long id);

    /** RAG 索引使用的全量已发布正文（WORK-072）。 */
    @Select("""
            SELECT id, title, body, updated_at
            FROM knowledge_entries
            WHERE status = 'published'
            ORDER BY id
            """)
    List<Entry> findAllPublishedWithBody();

    /**
     * 同分类内的上一篇／下一篇，按 (published_at, id) 排序。
     *
     * <p>两个条件都要比：同一毫秒发布的两条只能靠 id 分先后，只比时间会重复或跳过。
     *
     * <p><b>为什么拆成 `a < 时间 OR (a = 时间 AND id < 参数)` 而不是行值比较
     * `(published_at, id) < (时间, id)`</b>（2026-10-19 压测改）：两种写法结果完全等价，
     * 但 MySQL 对行值比较**用不上索引范围**——只能把同分类的行全读出来再逐行过滤，实测
     * 3.14ms/次；拆开后能走 idx_knowledge_entries_category_order 的区间扫描，0.17ms。
     * 详情页每请求要跑两次（上一篇、下一篇），这一项占了它耗时的大头。
     *
     * <p><b>这里写的是裸 `&lt;` 而不是 `&amp;lt;`</b>：MyBatis 只在 `<script>` 块里按 XML 解析
     * 注解文本，普通注解里的实体不会被解码——写成 `&amp;lt;` 会原样发给 MySQL 变成语法错误。
     */
    @Select("""
            SELECT id, title FROM knowledge_entries
            WHERE status = 'published' AND category_id = #{categoryId}
              AND (published_at < #{publishedAt}
                   OR (published_at = #{publishedAt} AND id < #{id}))
            ORDER BY published_at DESC, id DESC LIMIT 1
            """)
    Optional<Entry> findPreviousInCategory(@Param("categoryId") Long categoryId,
                                           @Param("publishedAt") Instant publishedAt,
                                           @Param("id") Long id);

    /** 见 {@link #findPreviousInCategory} 关于裸 `<` 与条件拆写的说明。 */
    @Select("""
            SELECT id, title FROM knowledge_entries
            WHERE status = 'published' AND category_id = #{categoryId}
              AND (published_at > #{publishedAt}
                   OR (published_at = #{publishedAt} AND id > #{id}))
            ORDER BY published_at ASC, id ASC LIMIT 1
            """)
    Optional<Entry> findNextInCategory(@Param("categoryId") Long categoryId,
                                       @Param("publishedAt") Instant publishedAt,
                                       @Param("id") Long id);

    /**
     * 一条学习线的全部站，**按声明顺序**（WORK-020）。
     *
     * <p>这是"线的顺序"的唯一实现：`sort_order` 为 NULL 的排到最后
     * （`(sort_order IS NULL)` 在 MySQL 里得到 0/1），声明过的按声明排，同值用
     * `(published_at, id)` 兜底。别的查询（列表、上一篇/下一篇）仍按原有的
     * `(published_at, id)` —— 它们是"同分类相邻内容"，不承担线的顺序。
     */
    @Select("""
            SELECT id, category_id, sort_order, title, status, published_at, updated_at
            FROM knowledge_entries
            WHERE status = 'published' AND category_id = #{categoryId}
            ORDER BY (sort_order IS NULL), sort_order, published_at, id
            """)
    List<Entry> findPublishedByCategoryOrdered(@Param("categoryId") Long categoryId);

    /** 反向链接：把当前条目当作**前置**的那些条目（只列已发布的）。 */
    // 关系类查询统一放在 RelationMapper（前置／反向／相关三种视角在一处更好读）。

    // ---- 管理端：可以看见草稿 ----

    @Select("""
            <script>
            SELECT id, category_id, title, status, published_at, updated_at
            FROM knowledge_entries
            <where>
              <if test="status != null">status = #{status}</if>
            </where>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Entry> pageAdmin(@Param("status") String status,
                          @Param("limit") int limit,
                          @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM knowledge_entries
            <where>
              <if test="status != null">status = #{status}</if>
            </where>
            </script>
            """)
    long countAdmin(@Param("status") String status);

    /** 管理端详情：**不过滤状态**，草稿预览靠它。 */
    @Select("""
            SELECT id, category_id, sort_order, title, body, source_url, fingerprint, status,
                   published_at, created_at, updated_at
            FROM knowledge_entries WHERE id = #{id}
            """)
    Optional<Entry> findByIdIncludingDraft(@Param("id") Long id);

    /**
     * 条目所属分类的学习线类别（WORK-020）：course / reading / null。
     *
     * <p>前端据此决定这一页的助手用哪种行为——课程页用导师，其它页用通用助手。
     * 一次 JOIN 就够，不必为它另开一个端点。
     */
    @Select(ENTRY_LINE_KIND_SQL)
    String findLineKindOfEntry(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM knowledge_entries WHERE category_id = #{id}")
    long countByCategory(@Param("id") Long id);

    /**
     * 若干分类下**已发布**条目数（2026-11-09，首页面经四个方向的入口显示条数用）。
     *
     * <p>一次查完而不是逐分类 COUNT：首页那条横幅有四个方向，逐个查会把一次渲染变成四次往返。
     *
     * <p>返回 {@code Map} 而不是实体/DTO：本项目**没有开启 MyBatis 的构造器自动映射**
     * （全仓没有 {@code @ConstructorArgs}，也没有 {@code argNameBasedConstructorAutoMapping}），
     * 拿 record 当结果类型并不可靠；这里只有两列，用 Map 最省且不会静默映射错。
     */
    @Select("""
            <script>
            SELECT category_id, COUNT(*) AS total
            FROM knowledge_entries
            WHERE status = 'published' AND category_id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY category_id
            </script>
            """)
    List<Map<String, Object>> countPublishedByCategoryIds(@Param("ids") List<Long> ids);

    /**
     * 按 id 批量取已发布条目的标题（WORK-021）。
     *
     * <p>图谱一次要显示成百个概念的载体条目，逐条 {@code findPublishedById} 会把一次查询
     * 变成几百次。只取标题——图上只需要它能点进去。
     */
    @Select("""
            <script>
            SELECT id, category_id, title
            FROM knowledge_entries
            WHERE status = 'published' AND id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Entry> findPublishedTitlesByIds(@Param("ids") List<Long> ids);

    /**
     * 概念 → 已发布载体条目（WORK-029）。
     *
     * <p>一次查完而不是按概念逐条查：图谱与计划都要"每个概念下面有哪些内容"，
     * 逐条查会把一次查询变成几百次。
     *
     * <p>{@code hasBlocks} 用 {@code LIKE '%```quiz%'} 约等于判断有没有 quiz 围栏块。
     * 这条近似刻意留着：精确解析 markdown 围栏的成本远高于收益（它只影响计划里多给 10 分钟），
     * 漏判的后果只是少给 10 分钟。
     *
     * <p>{@code bodyChars} 用 {@code CHAR_LENGTH} 而不是 {@code LENGTH}：后者按字节算，
     * 中文正文会虚高 3 倍，阅读时长直接顶到上限。
     */
    @Select("""
            <script>
            SELECT c.concept_id, e.id AS entry_id, e.title,
                   CHAR_LENGTH(e.body) AS body_chars,
                   (e.body LIKE '%```quiz%' OR e.body LIKE '%```challenge%') AS has_blocks
            FROM knowledge_entry_concepts c
            JOIN knowledge_entries e ON e.id = c.entry_id
            WHERE e.status = 'published' AND c.concept_id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY c.concept_id, e.id
            </script>
            """)
    List<CarrierRow> findPublishedCarriersByConceptIds(@Param("ids") List<Long> ids);

    /**
     * 某人到期该复习的条目（WORK-029）。
     *
     * <p>只返回**已发布**的：下架条目不能作为计划项被点开。{@code concept_id} 取该条目挂的
     * 最小概念 id——一条内容可能挂多个概念，复习项只需要能归到某处（可空）。
     */
    @Select("""
            SELECT p.entry_id, e.title,
                   (SELECT MIN(c.concept_id) FROM knowledge_entry_concepts c WHERE c.entry_id = e.id)
                       AS concept_id
            FROM learning_progress p
            JOIN knowledge_entries e ON e.id = p.entry_id
            WHERE p.user_id = #{userId} AND e.status = 'published'
              AND p.review_due_at IS NOT NULL AND p.review_due_at <= UTC_TIMESTAMP(3)
            ORDER BY p.review_due_at, p.entry_id
            LIMIT #{limit}
            """)
    List<DueRow> findDueReviews(@Param("userId") long userId, @Param("limit") int limit);

    /** 训练营用的载体行。`hasBlocks` 由 MySQL 的布尔结果映射。 */
    record CarrierRow(long conceptId, long entryId, String title, int bodyChars, boolean hasBlocks) {
    }

    /** 到期复习行。`conceptId` 可空：条目可能没挂任何概念。 */
    record DueRow(Long conceptId, long entryId, String title) {
    }

    @Insert("""
            INSERT INTO knowledge_entries (category_id, sort_order, title, body, source_url, fingerprint,
                                           status, published_at)
            VALUES (#{categoryId}, #{sortOrder}, #{title}, #{body}, #{sourceUrl}, #{fingerprint},
                    #{status}, #{publishedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Entry entry);

    /**
     * 按指纹查是否已存在（WORK-025，2026-11-06 改为**跨分类**）。
     *
     * <p><b>为什么不再限定分类</b>：指纹是「来源站点 + 上游内容 id」的哈希，**一篇帖子一个指纹**，
     * 不是题目文本的哈希。同一篇帖子会同时出现在多个牛客合集里（实测 Java 合集与「软件开发」合集
     * 撞帖），而多方向抓取会把它分别写进不同分类——限定分类判重的话，同一篇帖子会在库里有两份。
     * 原注释担心的"同一道题出现在不同分类里"在指纹改成帖子 id 之后已不成立：两道不同的帖子
     * 本来就是两个指纹。
     */
    @Select("""
            SELECT id FROM knowledge_entries
            WHERE fingerprint = #{fingerprint}
            LIMIT 1
            """)
    Long findIdByFingerprint(@Param("fingerprint") String fingerprint);

    @Update("""
            UPDATE knowledge_entries
            SET category_id = #{categoryId}, sort_order = #{sortOrder}, title = #{title}, body = #{body},
                status = #{status}, published_at = #{publishedAt}
            WHERE id = #{id}
            """)
    int update(Entry entry);

    @Delete("DELETE FROM knowledge_entries WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
