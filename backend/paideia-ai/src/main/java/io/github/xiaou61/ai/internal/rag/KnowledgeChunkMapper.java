package io.github.xiaou61.ai.internal.rag;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** `ai_knowledge_chunks` 的读写（WORK-072）。 */
@Mapper
interface KnowledgeChunkMapper {

    @Select("""
            SELECT id, entry_id, chunk_index, title, content, content_hash, embedding, dimension, updated_at
            FROM ai_knowledge_chunks
            """)
    List<KnowledgeChunk> findAll();

    @Select("""
            SELECT content_hash
            FROM ai_knowledge_chunks
            WHERE entry_id = #{entryId}
            ORDER BY chunk_index
            """)
    List<String> findHashesByEntry(@Param("entryId") long entryId);

    @Select("SELECT DISTINCT entry_id FROM ai_knowledge_chunks")
    List<Long> findEntryIds();

    @Delete("DELETE FROM ai_knowledge_chunks WHERE entry_id = #{entryId}")
    int deleteByEntry(@Param("entryId") long entryId);

    @Insert("""
            INSERT INTO ai_knowledge_chunks
                (entry_id, chunk_index, title, content, content_hash, embedding, dimension, updated_at)
            VALUES
                (#{entryId}, #{chunkIndex}, #{title}, #{content}, #{contentHash},
                 #{embedding}, #{dimension}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeChunk chunk);
}
