package io.github.xiaou61.knowledge.internal.category;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 分类表访问。
 *
 * <p>SQL 只写在这里（沿用本项目 {@code @Mapper} + 注解/文本块的写法，不用 XML）。
 *
 * <p>层级树的读取刻意是**一次全量查询 + 内存装树**（见 {@link CategoryTree}），
 * 而不是递归 CTE：分类数量级是几十，递归的复杂度换不来收益。
 */
@Mapper
public interface CategoryMapper {

    /** 全量，按展示顺序。装树与"取某分类的整棵子树"都基于这一份结果。 */
    @Select("""
            SELECT id, parent_id, name, slug, sort_order, line_kind, created_at, updated_at
            FROM knowledge_categories
            ORDER BY sort_order, id
            """)
    List<Category> findAll();

    @Select("""
            SELECT id, parent_id, name, slug, sort_order, line_kind, created_at, updated_at
            FROM knowledge_categories WHERE id = #{id}
            """)
    Category findById(@Param("id") Long id);

    /**
     * 该 slug 是否已被占用。
     *
     * <p>带 {@code excludeId} 是因为**改名时会把"自己"算进去**：不排除的话，把分类改回
     * 原来的 slug 会被判成冲突。
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM knowledge_categories WHERE slug = #{slug}
            <if test="excludeId != null">AND id &lt;&gt; #{excludeId}</if>
            </script>
            """)
    long countBySlug(@Param("slug") String slug, @Param("excludeId") Long excludeId);

    /** 兄弟节点里是否已有同名（同级重名会给管理员造成"到底是哪个"的困惑）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM knowledge_categories
            WHERE name = #{name}
            <choose>
              <when test="parentId == null">AND parent_id IS NULL</when>
              <otherwise>AND parent_id = #{parentId}</otherwise>
            </choose>
            <if test="excludeId != null">AND id &lt;&gt; #{excludeId}</if>
            </script>
            """)
    long countSiblingsWithName(@Param("parentId") Long parentId,
                               @Param("name") String name,
                               @Param("excludeId") Long excludeId);

    @Select("SELECT COUNT(*) FROM knowledge_categories WHERE parent_id = #{id}")
    long countChildren(@Param("id") Long id);

    /**
     * 按「父 + 名称」查分类（WORK-076：上传保留目录树时按层复用）。
     *
     * <p>用名称而不是 slug 定位：导入的目录名是中文，无法稳定映射成 latin slug；
     * 名称是用户可见的目录语义，同名同父即视为同一个分类。
     */
    @Select("""
            <script>
            SELECT id, parent_id, name, slug, sort_order, line_kind, created_at, updated_at
            FROM knowledge_categories
            WHERE name = #{name}
            <choose>
              <when test="parentId == null">AND parent_id IS NULL</when>
              <otherwise>AND parent_id = #{parentId}</otherwise>
            </choose>
            ORDER BY id
            LIMIT 1
            </script>
            """)
    Category findByParentAndName(@Param("parentId") Long parentId, @Param("name") String name);

    @Insert("""
            INSERT INTO knowledge_categories (parent_id, name, slug, sort_order, line_kind)
            VALUES (#{parentId}, #{name}, #{slug}, #{sortOrder}, #{lineKind})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Category category);

    @Update("""
            UPDATE knowledge_categories
            SET parent_id = #{parentId}, name = #{name}, slug = #{slug}, sort_order = #{sortOrder},
                line_kind = #{lineKind}
            WHERE id = #{id}
            """)
    int update(Category category);

    @Delete("DELETE FROM knowledge_categories WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
