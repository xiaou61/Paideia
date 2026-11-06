package io.github.xiaou61.ai.internal.rag;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** `ai_knowledge_chunk_edits` 的读写（WORK-076）。 */
@Mapper
interface KnowledgeChunkEditMapper {

    String COLUMNS = "id, entry_id, chunk_index, version, content, content_hash, embedding, dimension, "
            + "base_content_hash, editor_id, created_at";

    /** 只取「每个 (entry_id, chunk_index) 的最大 version」——即当前生效的编辑。 */
    String CURRENT_JOIN = """
            JOIN (
                SELECT entry_id, chunk_index, MAX(version) AS mv
                FROM ai_knowledge_chunk_edits
                GROUP BY entry_id, chunk_index
            ) m ON c.entry_id = m.entry_id AND c.chunk_index = m.chunk_index AND c.version = m.mv
            """;

    @Insert("""
            INSERT INTO ai_knowledge_chunk_edits
                (entry_id, chunk_index, version, content, content_hash, embedding, dimension,
                 base_content_hash, editor_id)
            VALUES
                (#{entryId}, #{chunkIndex}, #{version}, #{content}, #{contentHash}, #{embedding}, #{dimension},
                 #{baseContentHash}, #{editorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeChunkEdit edit);

    @Select("SELECT COALESCE(MAX(version), 0) FROM ai_knowledge_chunk_edits "
            + "WHERE entry_id = #{entryId} AND chunk_index = #{chunkIndex}")
    int findMaxVersion(@Param("entryId") long entryId, @Param("chunkIndex") int chunkIndex);

    @Select("SELECT c.* FROM ai_knowledge_chunk_edits c " + CURRENT_JOIN
            + " WHERE c.entry_id = #{entryId} ORDER BY c.chunk_index")
    List<KnowledgeChunkEdit> findCurrentByEntry(@Param("entryId") long entryId);

    @Select("SELECT c.* FROM ai_knowledge_chunk_edits c " + CURRENT_JOIN
            + " ORDER BY c.entry_id, c.chunk_index")
    List<KnowledgeChunkEdit> findAllCurrent();

    @Select("SELECT c.* FROM ai_knowledge_chunk_edits c " + CURRENT_JOIN
            + " WHERE c.embedding IS NULL")
    List<KnowledgeChunkEdit> findPendingEmbedding();

    @Select("SELECT " + COLUMNS + " FROM ai_knowledge_chunk_edits "
            + "WHERE entry_id = #{entryId} AND chunk_index = #{chunkIndex} ORDER BY version DESC")
    List<KnowledgeChunkEdit> findRevisions(@Param("entryId") long entryId, @Param("chunkIndex") int chunkIndex);

    @Update("UPDATE ai_knowledge_chunk_edits SET embedding = #{embedding}, dimension = #{dimension} WHERE id = #{id}")
    int updateEmbedding(@Param("id") long id, @Param("embedding") byte[] embedding, @Param("dimension") Integer dimension);
}
