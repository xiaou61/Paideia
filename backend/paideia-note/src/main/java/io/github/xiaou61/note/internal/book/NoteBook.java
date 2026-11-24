package io.github.xiaou61.note.internal.book;

import java.time.Instant;

/** 一个知识库。可变类 + useGeneratedKeys，与项目其它实体一致。 */
class NoteBook {

    private Long id;
    private Long userId;
    private String name;
    private String description;
    private String visibility;
    private String accentColor;
    private String tags;
    private int sortOrder;
    private int documentCount;
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;

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

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    String getDescription() {
        return description;
    }

    void setDescription(String description) {
        this.description = description;
    }

    String getVisibility() {
        return visibility;
    }

    void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    String getAccentColor() {
        return accentColor;
    }

    void setAccentColor(String accentColor) {
        this.accentColor = accentColor;
    }

    String getTags() {
        return tags;
    }

    void setTags(String tags) {
        this.tags = tags;
    }

    int getDocumentCount() {
        return documentCount;
    }

    void setDocumentCount(int documentCount) {
        this.documentCount = documentCount;
    }

    int getSortOrder() {
        return sortOrder;
    }

    void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    Instant getDeletedAt() {
        return deletedAt;
    }

    void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
