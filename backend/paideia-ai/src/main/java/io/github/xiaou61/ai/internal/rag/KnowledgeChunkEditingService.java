package io.github.xiaou61.ai.internal.rag;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 检索切片的手工编辑与版本回滚（WORK-076 TASK-009）。
 *
 * <p>自动块仍由 {@link KnowledgeChunkMapper}（条目正文派生、可重建）；手工编辑写入
 * {@link KnowledgeChunkEditMapper}（只追加、按版本）。检索时由 {@link KnowledgeRagService}
 * 用“当前版本”覆盖同位置自动块。编辑只对**该块**重新 embedding，不重建整条目；embedding
 * 不可用时先存内容、标记待补嵌（不阻塞、不 500）。
 */
@Service
class KnowledgeChunkEditingService {

    private final KnowledgeChunkMapper chunks;
    private final KnowledgeChunkEditMapper edits;
    private final EmbeddingClient embedding;

    KnowledgeChunkEditingService(KnowledgeChunkMapper chunks, KnowledgeChunkEditMapper edits,
                                 EmbeddingClient embedding) {
        this.chunks = chunks;
        this.edits = edits;
        this.embedding = embedding;
    }

    /** 管理端展示用：自动块 + 手工覆盖合并后的视图。 */
    record ChunkView(int chunkIndex, String content, String origin, Integer version,
                     boolean conflict, boolean orphaned) {
    }

    record EditResult(int version, boolean embeddingPending) {
    }

    record Revision(int version, String content, Long editorId, Instant createdAt) {
    }

    List<ChunkView> list(long entryId) {
        List<KnowledgeChunk> autoChunks = orEmpty(chunks.findByEntryId(entryId));
        List<KnowledgeChunkEdit> current = orEmpty(edits.findCurrentByEntry(entryId));
        Map<Integer, KnowledgeChunkEdit> byIndex = new HashMap<>();
        for (KnowledgeChunkEdit edit : current) {
            byIndex.put(edit.getChunkIndex(), edit);
        }
        List<ChunkView> views = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (KnowledgeChunk auto : autoChunks) {
            int index = auto.getChunkIndex();
            seen.add(index);
            KnowledgeChunkEdit edit = byIndex.get(index);
            if (edit == null) {
                views.add(new ChunkView(index, auto.getContent(), "auto", null, false, false));
            } else {
                boolean conflict = edit.getBaseContentHash() != null
                        && !edit.getBaseContentHash().equals(auto.getContentHash());
                views.add(new ChunkView(index, edit.getContent(), "manual", edit.getVersion(), conflict, false));
            }
        }
        // 自动块里已不存在的编辑（正文缩短导致错位）——标注 orphaned，供管理端提示
        for (KnowledgeChunkEdit edit : current) {
            if (!seen.contains(edit.getChunkIndex())) {
                views.add(new ChunkView(edit.getChunkIndex(), edit.getContent(), "manual",
                        edit.getVersion(), false, true));
            }
        }
        views.sort(Comparator.comparingInt(ChunkView::chunkIndex));
        return views;
    }

    EditResult edit(long entryId, int chunkIndex, String content, long editorId) {
        String trimmed = requireContent(content);
        return appendVersion(entryId, chunkIndex, trimmed, editorId);
    }

    List<Revision> revisions(long entryId, int chunkIndex) {
        return orEmpty(edits.findRevisions(entryId, chunkIndex)).stream()
                .map(edit -> new Revision(edit.getVersion(), edit.getContent(), edit.getEditorId(),
                        edit.getCreatedAt()))
                .toList();
    }

    /** 回滚到某一历史版本：追加一个新版本（内容复制自目标），保持只追加、可追溯。 */
    int rollback(long entryId, int chunkIndex, int version, long editorId) {
        KnowledgeChunkEdit target = orEmpty(edits.findRevisions(entryId, chunkIndex)).stream()
                .filter(edit -> edit.getVersion() != null && edit.getVersion() == version)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "该版本不存在"));
        return appendVersion(entryId, chunkIndex, target.getContent(), editorId).version();
    }

    private EditResult appendVersion(long entryId, int chunkIndex, String content, long editorId) {
        KnowledgeChunk auto = chunks.findOne(entryId, chunkIndex);
        KnowledgeChunkEdit edit = new KnowledgeChunkEdit();
        edit.setEntryId(entryId);
        edit.setChunkIndex(chunkIndex);
        edit.setVersion(edits.findMaxVersion(entryId, chunkIndex) + 1);
        edit.setContent(content);
        edit.setContentHash(sha256(content));
        edit.setBaseContentHash(auto == null ? null : auto.getContentHash());
        edit.setEditorId(editorId);
        boolean pending = embedInto(edit, content);
        edits.insert(edit);
        return new EditResult(edit.getVersion(), pending);
    }

    /** 尝试把 content 的向量写进 edit；embedding 关闭或失败时返回 true（待补嵌）。 */
    private boolean embedInto(KnowledgeChunkEdit edit, String content) {
        if (!embedding.enabled()) {
            return true;
        }
        try {
            List<float[]> vectors = embedding.embed(List.of(content));
            if (vectors == null || vectors.isEmpty()) {
                return true;
            }
            float[] vector = vectors.getFirst();
            edit.setEmbedding(KnowledgeRagService.encode(vector));
            edit.setDimension(vector.length);
            return false;
        } catch (RuntimeException unavailable) {
            // 降级：先存内容，下一轮 reindex 补嵌；检索暂回退自动块
            return true;
        }
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "切片内容不能为空");
        }
        return content.trim();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
