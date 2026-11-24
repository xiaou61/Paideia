package io.github.xiaou61.note.internal.book;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 知识库读写。每条语句带 user_id；存活判断 deleted_at IS NULL。 */
@Mapper
interface NoteBookMapper {

    @Insert("""
            INSERT INTO note_books (user_id, name, description, visibility, accent_color, tags, sort_order)
            VALUES (#{userId}, #{name}, #{description}, #{visibility}, #{accentColor}, #{tags}, #{sortOrder})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NoteBook book);

    @Select("""
            SELECT b.id, b.user_id, b.name, b.description, b.visibility, b.accent_color, b.tags,
                   b.sort_order, b.deleted_at, b.created_at, b.updated_at,
                   (SELECT COUNT(1) FROM notes n
                     WHERE n.book_id = b.id AND n.deleted_at IS NULL AND n.kind = 'doc') AS document_count
            FROM note_books b
            WHERE b.id = #{id} AND b.user_id = #{userId} AND b.deleted_at IS NULL
            """)
    Optional<NoteBook> findByIdAndUser(@Param("id") long id, @Param("userId") long userId);

    @Select("""
            SELECT b.id, b.user_id, b.name, b.description, b.visibility, b.accent_color, b.tags,
                   b.sort_order, b.deleted_at, b.created_at, b.updated_at,
                   (SELECT COUNT(1) FROM notes n
                     WHERE n.book_id = b.id AND n.deleted_at IS NULL AND n.kind = 'doc') AS document_count
            FROM note_books b
            WHERE b.user_id = #{userId} AND b.deleted_at IS NULL
            ORDER BY b.sort_order, b.id
            """)
    List<NoteBook> listByUser(@Param("userId") long userId);

    @Update("""
            UPDATE note_books
            SET name = #{name}, description = #{description}, visibility = #{visibility},
                accent_color = #{accentColor}, tags = #{tags}, sort_order = #{sortOrder}
            WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL
            """)
    int update(NoteBook book);

    @Update("""
            UPDATE note_books SET deleted_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") long id, @Param("userId") long userId);

    @Select("""
            SELECT COUNT(1) FROM note_books WHERE user_id = #{userId} AND deleted_at IS NULL
            """)
    int countByUser(@Param("userId") long userId);
}
