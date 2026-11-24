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
            INSERT INTO note_books (user_id, name, description, sort_order)
            VALUES (#{userId}, #{name}, #{description}, #{sortOrder})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NoteBook book);

    @Select("""
            SELECT id, user_id, name, description, sort_order, deleted_at, created_at, updated_at
            FROM note_books
            WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL
            """)
    Optional<NoteBook> findByIdAndUser(@Param("id") long id, @Param("userId") long userId);

    @Select("""
            SELECT id, user_id, name, description, sort_order, deleted_at, created_at, updated_at
            FROM note_books
            WHERE user_id = #{userId} AND deleted_at IS NULL
            ORDER BY sort_order, id
            """)
    List<NoteBook> listByUser(@Param("userId") long userId);

    @Update("""
            UPDATE note_books
            SET name = #{name}, description = #{description}, sort_order = #{sortOrder}
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
