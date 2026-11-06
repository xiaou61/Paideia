package io.github.xiaou61.ai.internal.importing;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** `ai_knowledge_imports` 的读写（WORK-076）。 */
@Mapper
interface KnowledgeImportMapper {

    String COLUMNS = "id, file_id, relative_path, target_category_id, status, entry_id, error, operator_id, created_at, updated_at";

    @Insert("""
            INSERT INTO ai_knowledge_imports
                (file_id, relative_path, target_category_id, status, operator_id)
            VALUES
                (#{fileId}, #{relativePath}, #{targetCategoryId}, #{status}, #{operatorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeImport job);

    @Select("SELECT " + COLUMNS + " FROM ai_knowledge_imports WHERE id = #{id}")
    KnowledgeImport findById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM ai_knowledge_imports WHERE status = 'pending' ORDER BY id LIMIT #{limit}")
    List<KnowledgeImport> findPending(@Param("limit") int limit);

    @Select("""
            <script>
            SELECT id, file_id, relative_path, target_category_id, status, entry_id, error,
                   operator_id, created_at, updated_at
            FROM ai_knowledge_imports
            <if test="status != null">WHERE status = #{status}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<KnowledgeImport> findRecent(@Param("status") String status, @Param("limit") int limit);

    /** 抢占：只有仍是 pending 才置 processing，返回 0 表示被别的 worker 拿走了。 */
    @Update("UPDATE ai_knowledge_imports SET status = 'processing' WHERE id = #{id} AND status = 'pending'")
    int markProcessing(@Param("id") long id);

    @Update("""
            UPDATE ai_knowledge_imports
            SET status = #{status}, entry_id = #{entryId}, error = #{error}
            WHERE id = #{id}
            """)
    int updateResult(@Param("id") long id, @Param("status") String status,
                     @Param("entryId") Long entryId, @Param("error") String error);

    /** 重试：把 failed 退回 pending（error 清空）。 */
    @Update("UPDATE ai_knowledge_imports SET status = 'pending', error = NULL WHERE id = #{id} AND status = 'failed'")
    int markPending(@Param("id") long id);
}
