package io.github.xiaou61.ai.internal.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.knowledge.EntryContent;
import io.github.xiaou61.knowledge.KnowledgeApi;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeRagServiceTest {

    @Test
    void disabledReturnsEmpty() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(false);
        KnowledgeRagService service = new KnowledgeRagService(
                mock(KnowledgeApi.class), mock(KnowledgeChunkMapper.class), disabled(), properties);

        assertThat(service.retrieveContext("Java 并发")).isEmpty();
    }

    @Test
    void ranksMoreSimilarChunkFirst() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.setTopK(1);
        KnowledgeApi knowledge = mock(KnowledgeApi.class);
        KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
        KnowledgeChunk close = chunk(1L, "并发", KnowledgeRagService.encode(new float[]{1, 0}));
        KnowledgeChunk far = chunk(2L, "并发", KnowledgeRagService.encode(new float[]{0, 1}));
        when(chunks.findAll()).thenReturn(List.of(far, close));

        KnowledgeRagService service = new KnowledgeRagService(knowledge, chunks,
                new EmbeddingClient() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public List<float[]> embed(List<String> texts) {
                        return List.of(new float[]{1, 0});
                    }
                }, properties);

        assertThat(service.retrieveContext("并发")).contains("[[entry:1]]");
    }

    @Test
    void reindexesChangedEntryAndDropsUnpublishedChunks() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        KnowledgeApi knowledge = mock(KnowledgeApi.class);
        KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
        when(knowledge.publishedContents()).thenReturn(List.of(
                new EntryContent(1L, "并发", "线程安全", Instant.now())));
        when(chunks.findEntryIds()).thenReturn(List.of(1L, 99L));
        when(chunks.findHashesByEntry(1L)).thenReturn(List.of("old-hash"));

        KnowledgeRagService service = new KnowledgeRagService(knowledge, chunks,
                enabledWithVector(), properties);
        service.reindexChanged();

        verify(chunks).deleteByEntry(99L);
        verify(chunks).deleteByEntry(1L);
        verify(chunks).insert(any(KnowledgeChunk.class));
    }

    private static EmbeddingClient disabled() {
        return new EmbeddingClient() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return List.of();
            }
        };
    }

    private static EmbeddingClient enabledWithVector() {
        return new EmbeddingClient() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> new float[]{1, 0}).toList();
            }
        };
    }

    private static KnowledgeChunk chunk(long entryId, String title, byte[] vector) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setEntryId(entryId);
        chunk.setTitle(title);
        chunk.setContent("内容");
        chunk.setEmbedding(vector);
        chunk.setDimension(2);
        return chunk;
    }
}
