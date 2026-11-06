package io.github.xiaou61.ai.internal.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.BizException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** WORK-076 TASK-009：切片编辑服务（只重嵌单块 / 版本 / 回滚 / 冲突与孤儿 / 降级）。 */
class KnowledgeChunkEditingServiceTest {

    private final KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
    private final KnowledgeChunkEditMapper edits = mock(KnowledgeChunkEditMapper.class);

    private static KnowledgeChunk autoChunk(int index, String content, String hash) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setEntryId(1L);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setContentHash(hash);
        return chunk;
    }

    private static KnowledgeChunkEdit edit(int index, int version, String content, String baseHash) {
        KnowledgeChunkEdit e = new KnowledgeChunkEdit();
        e.setId(50L + version);
        e.setEntryId(1L);
        e.setChunkIndex(index);
        e.setVersion(version);
        e.setContent(content);
        e.setContentHash("h-" + version);
        e.setBaseContentHash(baseHash);
        return e;
    }

    private static EmbeddingClient enabled() {
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

    @Test
    @DisplayName("编辑单块：只对该块 embed 一次、版本递增、不重建整条目")
    void editsSingleChunkOnly() {
        when(chunks.findOne(1L, 2)).thenReturn(autoChunk(2, "自动内容", "base-hash"));
        when(edits.findMaxVersion(1L, 2)).thenReturn(4);

        var result = new KnowledgeChunkEditingService(chunks, edits, enabled())
                .edit(1L, 2, "改后的内容", 9L);

        assertThat(result.version()).isEqualTo(5);
        assertThat(result.embeddingPending()).isFalse();
        ArgumentCaptor<KnowledgeChunkEdit> captor = ArgumentCaptor.forClass(KnowledgeChunkEdit.class);
        verify(edits).insert(captor.capture());
        KnowledgeChunkEdit written = captor.getValue();
        assertThat(written.getContent()).isEqualTo("改后的内容");
        assertThat(written.getBaseContentHash()).isEqualTo("base-hash");
        assertThat(written.getContentHash()).hasSize(64);
        assertThat(written.getEmbedding()).isNotNull();
        // 不重建整条目
        verify(chunks, never()).deleteByEntry(anyLong());
        verify(chunks, never()).insert(any());
    }

    @Test
    @DisplayName("embedding 关闭：仍然保存，标记待补嵌（不抛异常）")
    void editWithEmbeddingDownIsPending() {
        when(chunks.findOne(1L, 0)).thenReturn(autoChunk(0, "a", "h"));
        when(edits.findMaxVersion(1L, 0)).thenReturn(0);

        var result = new KnowledgeChunkEditingService(chunks, edits, disabled())
                .edit(1L, 0, "内容", 9L);

        assertThat(result.embeddingPending()).isTrue();
        ArgumentCaptor<KnowledgeChunkEdit> captor = ArgumentCaptor.forClass(KnowledgeChunkEdit.class);
        verify(edits).insert(captor.capture());
        assertThat(captor.getValue().getEmbedding()).isNull();
    }

    @Test
    @DisplayName("空内容被拒")
    void rejectsEmptyContent() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new KnowledgeChunkEditingService(chunks, edits, enabled()).edit(1L, 0, "  ", 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("回滚：追加一个新版本，内容取自目标历史版本")
    void rollbackAppendsHistoricalContent() {
        when(edits.findRevisions(1L, 0)).thenReturn(List.of(
                edit(0, 3, "v3", "h"), edit(0, 2, "v2", "h"), edit(0, 1, "v1", "h")));
        when(edits.findMaxVersion(1L, 0)).thenReturn(3);

        int newVersion = new KnowledgeChunkEditingService(chunks, edits, enabled())
                .rollback(1L, 0, 1, 9L);

        assertThat(newVersion).isEqualTo(4);
        ArgumentCaptor<KnowledgeChunkEdit> captor = ArgumentCaptor.forClass(KnowledgeChunkEdit.class);
        verify(edits).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("v1");
        assertThat(captor.getValue().getVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("列表：手工覆盖标 manual 并检出冲突；无对应自动块的编辑标 orphaned")
    void listMergesAndFlags() {
        when(chunks.findByEntryId(1L)).thenReturn(List.of(
                autoChunk(0, "auto0", "h0"),
                autoChunk(1, "auto1", "h1")));
        when(edits.findCurrentByEntry(1L)).thenReturn(List.of(
                edit(0, 2, "manual0", "h0"),   // base 与自动块一致 → 无冲突
                edit(5, 1, "orphan", "hx")));  // 自动块没有 index 5 → orphaned

        List<KnowledgeChunkEditingService.ChunkView> views =
                new KnowledgeChunkEditingService(chunks, edits, enabled()).list(1L);

        assertThat(views).hasSize(3);
        assertThat(views.get(0).origin()).isEqualTo("manual");
        assertThat(views.get(0).conflict()).isFalse();
        assertThat(views.get(1).origin()).isEqualTo("auto");
        assertThat(views.get(2).orphaned()).isTrue();
    }

    @Test
    @DisplayName("冲突：编辑时的 base 与当前自动块 hash 不同 → conflict=true")
    void conflictDetected() {
        when(chunks.findByEntryId(1L)).thenReturn(List.of(autoChunk(0, "auto0-new", "h-new")));
        when(edits.findCurrentByEntry(1L)).thenReturn(List.of(edit(0, 2, "manual", "h-old")));

        List<KnowledgeChunkEditingService.ChunkView> views =
                new KnowledgeChunkEditingService(chunks, edits, enabled()).list(1L);

        assertThat(views.get(0).conflict()).isTrue();
    }
}
