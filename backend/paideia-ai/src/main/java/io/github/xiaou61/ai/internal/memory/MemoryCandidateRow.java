package io.github.xiaou61.ai.internal.memory;

import java.time.Instant;

/** 候选表的一行（WORK-050）。字段与迁移里的列一一对应。 */
class MemoryCandidateRow {

    private Long id;
    private long userId;
    private String store;
    private String kind;
    private Long refId;
    private String content;
    private int contentBytes;
    private String source;
    private Long conversationId;
    private Long refMessageId;
    private String reason;
    private String status;
    private String decisionReason;
    private Instant createdAt;
    private Instant expiresAt;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    long getUserId() {
        return userId;
    }

    void setUserId(long userId) {
        this.userId = userId;
    }

    String getStore() {
        return store;
    }

    void setStore(String store) {
        this.store = store;
    }

    String getKind() {
        return kind;
    }

    void setKind(String kind) {
        this.kind = kind;
    }

    Long getRefId() {
        return refId;
    }

    void setRefId(Long refId) {
        this.refId = refId;
    }

    String getContent() {
        return content;
    }

    void setContent(String content) {
        this.content = content;
    }

    int getContentBytes() {
        return contentBytes;
    }

    void setContentBytes(int contentBytes) {
        this.contentBytes = contentBytes;
    }

    String getSource() {
        return source;
    }

    void setSource(String source) {
        this.source = source;
    }

    Long getConversationId() {
        return conversationId;
    }

    void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    Long getRefMessageId() {
        return refMessageId;
    }

    void setRefMessageId(Long refMessageId) {
        this.refMessageId = refMessageId;
    }

    String getReason() {
        return reason;
    }

    void setReason(String reason) {
        this.reason = reason;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    String getDecisionReason() {
        return decisionReason;
    }

    void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
