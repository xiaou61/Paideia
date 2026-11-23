package io.github.xiaou61.ai.internal.essay;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** `ai_essay_verifications` 读写（WORK-084）。注解式 + text block，风格同 AiRunSpanMapper。 */
@Mapper
public interface EssayVerificationMapper {

    @Insert("""
            INSERT INTO ai_essay_verifications
                (status, question_title, draft_answer, final_answer, confidence, issues, snippets,
                 category_id, fingerprint, source_url, source_title, run_id, prompt_version, model)
            VALUES
                (#{status}, #{questionTitle}, #{draftAnswer}, #{finalAnswer}, #{confidence}, #{issuesJson},
                 #{snippetsJson}, #{categoryId}, #{fingerprint}, #{sourceUrl}, #{sourceTitle}, #{runId},
                 #{promptVersion}, #{model})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(EssayVerificationRow row);

    @Select("""
            SELECT id, status, question_title, draft_answer, final_answer, confidence,
                   issues AS issuesJson, snippets AS snippetsJson, category_id, fingerprint,
                   source_url, source_title, entry_id, run_id, prompt_version, model,
                   review_note, reviewed_by, reviewed_at, created_at, updated_at
            FROM ai_essay_verifications
            WHERE id = #{id}
            """)
    Optional<EssayVerificationRow> findById(@Param("id") long id);

    @Select("""
            <script>
            SELECT id, status, question_title, draft_answer, final_answer, confidence,
                   issues AS issuesJson, snippets AS snippetsJson, category_id, fingerprint,
                   source_url, source_title, entry_id, run_id, prompt_version, model,
                   review_note, reviewed_by, reviewed_at, created_at, updated_at
            FROM ai_essay_verifications
            <where>
              <if test="status != null and status != ''">status = #{status}</if>
            </where>
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<EssayVerificationRow> findPage(@Param("status") String status,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_essay_verifications
            <where>
              <if test="status != null and status != ''">status = #{status}</if>
            </where>
            </script>
            """)
    long count(@Param("status") String status);

    @Update("""
            UPDATE ai_essay_verifications
               SET status = #{status},
                   entry_id = #{entryId},
                   reviewed_by = #{reviewedBy},
                   review_note = #{note},
                   reviewed_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status IN ('pending_review', 'revised')
            """)
    int markReviewed(@Param("id") long id,
                     @Param("status") String status,
                     @Param("entryId") Long entryId,
                     @Param("reviewedBy") Long reviewedBy,
                     @Param("note") String note);
}
