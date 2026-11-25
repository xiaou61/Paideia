package io.github.xiaou61.ai.internal.billing;

import java.time.Instant;

/**
 * 一次调用的积分预约（WORK-092 TASK-005）。
 *
 * <p>它是「预留—结算/退回」的**持久化凭据**：{@code reserve} 在从余额扣预扣额的同一个事务里插入一行
 * {@code RESERVED}，之后无论结算成功、失败还是进程崩溃，都能凭 {@code round_id} 找到这笔预留并把它
 * 推进到终态。这正是旧实现缺的那一环——旧代码只扣余额不留痕，一旦崩在结算之前，那一笔预扣就再也查不到。
 *
 * <p>{@code round_id} 唯一：重复结算/重复恢复都靠它做幂等判据（{@code status} 的 CAS）。
 * 状态只有三态：
 * <ul>
 *   <li>{@code RESERVED}：已从余额扣款、等待结算；超时未结算由恢复任务退回。</li>
 *   <li>{@code SETTLED}：已按真实用量结算（可能补退或补扣），{@code round_id} 也写进了那条结算流水。</li>
 *   <li>{@code REFUNDED}：全额退回（未产生消耗），余额回到预留前。</li>
 * </ul>
 */
class CreditReservation {

    static final String STATUS_RESERVED = "RESERVED";
    static final String STATUS_SETTLED = "SETTLED";
    static final String STATUS_REFUNDED = "REFUNDED";

    private Long id;
    private String roundId;
    private long userId;
    private long amount;
    private String status;
    private Long refMessageId;
    private String note;
    private Instant createdAt;
    private Instant updatedAt;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    String getRoundId() {
        return roundId;
    }

    void setRoundId(String roundId) {
        this.roundId = roundId;
    }

    long getUserId() {
        return userId;
    }

    void setUserId(long userId) {
        this.userId = userId;
    }

    long getAmount() {
        return amount;
    }

    void setAmount(long amount) {
        this.amount = amount;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    Long getRefMessageId() {
        return refMessageId;
    }

    void setRefMessageId(Long refMessageId) {
        this.refMessageId = refMessageId;
    }

    String getNote() {
        return note;
    }

    void setNote(String note) {
        this.note = note;
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
