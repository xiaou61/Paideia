package io.github.xiaou61.note.internal.note;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 笔记（目录/文档）的读写。
 *
 * <p><b>归属写在 SQL 里</b>：每一条语句都带 {@code user_id}。读取用 {@code Optional}，
 * 拿不到就当"不存在"，**不区分**"没有这条"与"不是你的"。
 *
 * <p>WORK-087 起 notes 承载树结构：{@code book_id / parent_id / kind / sort_order / deleted_at}
 * 与正文/标题同表。存活判断统一为 {@code deleted_at IS NULL}。
 */
@Mapper
public interface NoteMapper {

    @Insert("""
            INSERT INTO notes (user_id, book_id, kind, parent_id, sort_order, entry_id, title, body)
            VALUES (#{userId}, #{bookId}, #{kind}, #{parentId}, #{sortOrder}, #{entryId}, #{title}, #{body})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Note note);

    @Select("""
            SELECT id, user_id, book_id, kind, parent_id, sort_order, entry_id, title, body,
                   created_at, updated_at, deleted_at
            FROM notes
            WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL
            """)
    Optional<Note> findByIdAndUser(@Param("id") long id, @Param("userId") long userId);

    /** 列表带一段**截断的正文**（前 200 字），不带全文。 */
    @Select("""
            SELECT id, user_id, book_id, kind, parent_id, sort_order, entry_id, title,
                   LEFT(body, 200) AS excerpt, created_at, updated_at, deleted_at
            FROM notes
            WHERE user_id = #{userId} AND deleted_at IS NULL
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<Note> listByUser(@Param("userId") long userId, @Param("limit") int limit);

    /** 某知识库的全部存活节点（树查询用，由服务层组装成层级）。 */
    @Select("""
            SELECT id, user_id, book_id, kind, parent_id, sort_order, entry_id, title,
                   created_at, updated_at, deleted_at
            FROM notes
            WHERE user_id = #{userId} AND book_id = #{bookId} AND deleted_at IS NULL
            ORDER BY parent_id, sort_order, id
            """)
    List<Note> listByBook(@Param("userId") long userId, @Param("bookId") long bookId);

    /**
     * 本人文档全文检索。长查询走 ngram 全文索引，短查询（单字）退回 LIKE——
     * 判定与转义都在 {@code SearchQuery} 里，这里只按 {@code fulltext} 分支。
     */
    @Select("""
            <script>
            SELECT id, user_id, book_id, kind, parent_id, sort_order, entry_id, title,
                   LEFT(body, 200) AS excerpt, created_at, updated_at, deleted_at
            FROM notes
            WHERE user_id = #{userId} AND deleted_at IS NULL AND kind = 'doc'
            <choose>
                <when test="fulltext">AND MATCH(title, body) AGAINST(#{booleanText} IN BOOLEAN MODE)</when>
                <otherwise>AND (title LIKE #{likePattern} OR body LIKE #{likePattern})</otherwise>
            </choose>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Note> search(@Param("userId") long userId,
                      @Param("booleanText") String booleanText,
                      @Param("fulltext") boolean fulltext,
                      @Param("likePattern") String likePattern,
                      @Param("limit") int limit);

    @Update("""
            UPDATE notes
            SET title = #{title}, body = #{body}, entry_id = #{entryId}
            WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL
            """)
    int update(Note note);

    @Update("""
            UPDATE notes
            SET parent_id = #{parentId}, sort_order = #{sortOrder}
            WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL
            """)
    int move(@Param("id") long id,
             @Param("userId") long userId,
             @Param("parentId") Long parentId,
             @Param("sortOrder") int sortOrder);

    /** 软删单个节点。 */
    @Update("""
            UPDATE notes SET deleted_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") long id, @Param("userId") long userId);

    /** 软删一个知识库下的全部节点（删库时连带）。 */
    @Update("""
            UPDATE notes SET deleted_at = CURRENT_TIMESTAMP(3)
            WHERE user_id = #{userId} AND book_id = #{bookId} AND deleted_at IS NULL
            """)
    int softDeleteByBook(@Param("userId") long userId, @Param("bookId") long bookId);

    /** 某库存活节点数（上限校验）。 */
    @Select("""
            SELECT COUNT(1) FROM notes
            WHERE user_id = #{userId} AND book_id = #{bookId} AND deleted_at IS NULL
            """)
    int countByBook(@Param("userId") long userId, @Param("bookId") long bookId);

    /** 硬删单条（旧扁平删除路径保留）。 */
    @Delete("DELETE FROM notes WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") long id, @Param("userId") long userId);
}
