package io.github.xiaou61.ai.internal.memory;

import java.time.Instant;

/**
 * 一行记忆整理任务（WORK-061）。
 *
 * <p>只存**切片边界**（会话 + 从哪条到哪条消息），不存消息正文：重试时按边界重读，
 * 与抢锁那一刻读到的是同一段（切片上限固定，读出来再按 {@code <= toMessageId} 截断）。
 *
 * <p>可变类 + {@code useGeneratedKeys}，与项目其它实体一致。
 *
 * <p>类与读方法为 public 是给 app 模块的集成测试用的（同仓既有做法：{@code MemoryStore}、
 * {@code ConversationService} 等同理）；**模块边界不受影响**——它仍在 {@code internal} 包下，
 * spring-modulith 依然禁止别的模块引用。
 */
public class MemoryExtractionJob {

    private Long id;
    private Long userId;
    private Long conversationId;
    /** 切片起点（**不含**）：抢锁时读到的旧游标。 */
    private Long fromMessageId;
    /** 切片终点（**含**）：抢锁推进到的新游标。 */
    private Long toMessageId;
    private Integer attempts;
    /** 仅入队时用：本行创建时间（同时作为首次的 next_attempt_at）。 */
    private Instant createdAt;
    private Instant nextAttemptAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public Long getFromMessageId() {
        return fromMessageId;
    }

    public Long getToMessageId() {
        return toMessageId;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    void setId(Long id) {
        this.id = id;
    }

    void setUserId(Long userId) {
        this.userId = userId;
    }

    void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    void setFromMessageId(Long fromMessageId) {
        this.fromMessageId = fromMessageId;
    }

    void setToMessageId(Long toMessageId) {
        this.toMessageId = toMessageId;
    }

    void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }
}