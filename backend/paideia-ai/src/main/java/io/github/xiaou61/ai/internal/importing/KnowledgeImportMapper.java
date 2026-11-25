package io.github.xiaou61.ai.internal.importing;

import java.time.Instant;
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

    String COLUMNS = "id, file_id, relative_path, target_category_id, status, entry_id, error, operator_id, "
            + "claim_token, processing_since, attempts, created_at, updated_at";

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
                   operator_id, claim_token, processing_since, attempts, created_at, updated_at
            FROM ai_knowledge_imports
            <if test="status != null">WHERE status = #{status}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<KnowledgeImport> findRecent(@Param("status") String status, @Param("limit") int limit);

    /**
     * 领取：只有仍是 pending 才置 processing，写入本次领取令牌与时间、次数 +1；返回 0 表示被别的 worker 拿走了。
     *
     * <p>令牌（claim_token）是 fencing 的根：后续写结果必须带上同一个令牌，否则被判为过期写入。
     */
    @Update("""
            UPDATE ai_knowledge_imports
            SET status = 'processing', claim_token = #{claimToken},
                processing_since = UTC_TIMESTAMP(3), attempts = attempts + 1
            WHERE id = #{id} AND status = 'pending'
            """)
    int markClaimed(@Param("id") long id, @Param("claimToken") String claimToken);

    /**
     * 写结果（fencing）：只有 {@code claim_token} 仍等于本次领取令牌时才落，返回 0 表示自己已过期、
     * 结果被丢弃——避免"进程被杀后恢复、旧 worker 又回来把新结果覆盖"。
     */
    @Update("""
            UPDATE ai_knowledge_imports
            SET status = #{status}, entry_id = #{entryId}, error = #{error}
            WHERE id = #{id} AND claim_token = #{claimToken}
            """)
    int updateResult(@Param("id") long id, @Param("status") String status,
                     @Param("entryId") Long entryId, @Param("error") String error,
                     @Param("claimToken") String claimToken);

    /** 重试：把 failed 退回 pending（error 清空）。 */
    @Update("""
            UPDATE ai_knowledge_imports
            SET status = 'pending', error = NULL, claim_token = NULL, processing_since = NULL
            WHERE id = #{id} AND status = 'failed'
            """)
    int markPending(@Param("id") long id);

    /**
     * 回收卡死的 processing 行（WORK-092 TASK-006）：{@code processing_since} 早于阈值的退回 pending，
     * 清空领取令牌，让下一轮 worker 重新领取。返回被回收的行数。
     */
    @Update("""
            UPDATE ai_knowledge_imports
            SET status = 'pending', claim_token = NULL, processing_since = NULL
            WHERE status = 'processing' AND processing_since < #{before}
            """)
    int requeueStale(@Param("before") Instant before);
}
