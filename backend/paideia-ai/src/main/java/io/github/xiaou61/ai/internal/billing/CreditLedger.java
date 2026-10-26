package io.github.xiaou61.ai.internal.billing;

import java.time.Instant;

/**
 * 一条积分流水。
 *
 * <p>可变类 + {@code useGeneratedKeys}，与项目其它实体一致。
 *
 * <p>它**自带计量量**（model / input_tokens / output_tokens），不靠 join 别的表：精简那一路的用量
 * 本来就没有落库的地方，若计费记录要从别处 join，那一半消耗就永远查不到。
 */
class CreditLedger {

    /** 流水种类。字符串入库而不是数据库枚举：种类会增，改枚举要迁移。 */
    static final String KIND_ADJUST = "ADJUST";
    static final String KIND_RESERVE = "RESERVE";
    static final String KIND_SETTLE = "SETTLE";
    static final String KIND_REFUND = "REFUND";
    /**
     * 自动整理记忆的消耗（WORK-013）。
     *
     * <p>与 {@link #KIND_SETTLE} 分开是**需求**：它是"没聊几次却花了这么多"的唯一解释处。
     * 合成一类会让用户对着账目算不明白，也没法在面板上单独显示"最近一次自动整理花了多少"。
     */
    static final String KIND_MEMORY_EXTRACT = "MEMORY_EXTRACT";
    /**
     * 面试点评的消耗（WORK-010）。
     *
     * <p>与对话的消耗分开记：一场面试是"多轮 + 一次点评"，混成一类的话用户看不出
     * "点评那一下花了多少"，而它恰恰是单次最贵的一笔（要读整场对话）。
     */
    static final String KIND_INTERVIEW_REVIEW = "INTERVIEW_REVIEW";
    /**
     * 简历相关的消耗（WORK-018）。
     *
     * <p>与另外几类分开：一次求职 = 解析 + 分析 + 提案三笔，用户要能看出"改一份简历到底花了多少"。
     * 与面试点评混成一类的话，"最近改简历花了 30 分"这句话就没法回答。
     */
    static final String KIND_RESUME = "RESUME";

    /**
     * 外部 MCP 问答（WORK-019）。**没有 ref_message_id**：那次调用不落会话消息，
     * 混进 SETTLE 会让对账时出现"一笔没有对应消息的扣费"。
     */
    static final String KIND_MCP_ASK = "MCP_ASK";

    /**
     * 训练营出卷与复测（WORK-029）。
     *
     * <p>与简历分开：一个营是"出诊断卷 + 出复测卷"两笔，用户要能单独回答
     * "这个营到底花了多少"。两笔内部不再细分——它们在用户眼里是同一件事。
     */
    static final String KIND_CAMP = "CAMP";

    /** 复习会话出题（WORK-034）：一次会话一次调用，与训练营分开记，便于看单次复习成本。 */
    static final String KIND_REVIEW = "REVIEW";

    /**
     * 运维诊断（WORK-042）。它由告警或管理员触发，不是学习行为；与 SETTLE 分开记账，
     * 才能回答"这套 AIOps 一个月花了多少"。
     */
    static final String KIND_OPS_DIAGNOSIS = "OPS_DIAGNOSIS";

    private Long id;
    private Long userId;
    private long delta;
    private String kind;
    private String model;
    private Integer inputTokens;
    private Integer outputTokens;
    private Long refMessageId;
    private Long operatorUserId;
    private String note;
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

    long getDelta() {
        return delta;
    }

    void setDelta(long delta) {
        this.delta = delta;
    }

    String getKind() {
        return kind;
    }

    void setKind(String kind) {
        this.kind = kind;
    }

    String getModel() {
        return model;
    }

    void setModel(String model) {
        this.model = model;
    }

    Integer getInputTokens() {
        return inputTokens;
    }

    void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    Integer getOutputTokens() {
        return outputTokens;
    }

    void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    Long getRefMessageId() {
        return refMessageId;
    }

    void setRefMessageId(Long refMessageId) {
        this.refMessageId = refMessageId;
    }

    Long getOperatorUserId() {
        return operatorUserId;
    }

    void setOperatorUserId(Long operatorUserId) {
        this.operatorUserId = operatorUserId;
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
}
