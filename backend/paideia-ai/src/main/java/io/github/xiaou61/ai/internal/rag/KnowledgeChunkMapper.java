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

    /** 某条目的全部自动块，按序号（WORK-076 块编辑查看用）。 */
    @Select("""
            SELECT id, entry_id, chunk_index, title, content, content_hash, embedding, dimension, updated_at
            FROM ai_knowledge_chunks
            WHERE entry_id = #{entryId}
            ORDER BY chunk_index
            """)
    List<KnowledgeChunk> findByEntryId(@Param("entryId") long entryId);

    /** 某条目某位置的一个自动块；不存在返回 null（手工新增/错位时）。 */
    @Select("""
            SELECT id, entry_id, chunk_index, title, content, content_hash, embedding, dimension, updated_at
            FROM ai_knowledge_chunks
            WHERE entry_id = #{entryId} AND chunk_index = #{chunkIndex}
            """)
    KnowledgeChunk findOne(@Param("entryId") long entryId, @Param("chunkIndex") int chunkIndex);

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
