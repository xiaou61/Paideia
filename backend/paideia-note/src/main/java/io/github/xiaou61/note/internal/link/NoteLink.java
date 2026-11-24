package io.github.xiaou61.note.internal.link;

import java.time.Instant;

/** 一条双链关系：source 文档引用 target（target 可能悬空，此时 targetNoteId 为 null）。 */
class NoteLink {

    private Long id;
    private Long userId;
    private Long sourceNoteId;
    private Long targetNoteId;
    private String targetTitle;
    private Instant createdAt;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    Long getUserId() {
        return userId;
    }

    void setUserId(Long userId) {
        this.userId = userId;
    }

    Long getSourceNoteId() {
        return sourceNoteId;
    }

    void setSourceNoteId(Long sourceNoteId) {
        this.sourceNoteId = sourceNoteId;
    }

    Long getTargetNoteId() {
        return targetNoteId;
    }

    void setTargetNoteId(Long targetNoteId) {
        this.targetNoteId = targetNoteId;
    }

    String getTargetTitle() {
        return targetTitle;
    }

    void setTargetTitle(String targetTitle) {
        this.targetTitle = targetTitle;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
