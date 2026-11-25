package io.github.xiaou61.ai.internal.importing;

import java.time.Instant;

/**
 * 文档导入任务（WORK-076）。
 *
 * <p>可变类而不是 record：MyBatis 用 {@code useGeneratedKeys} 回写自增 id，要求实体可写字段。
 */
public class KnowledgeImport {

    private Long id;
    private String fileId;
    /** 上传时文件夹内的相对路径（保留目录树）；单文件上传为 null。 */
    private String relativePath;
    /** 单文件导入时指定的目标分类；relativePath 非空时以路径为准。 */
    private Long targetCategoryId;
    /** pending | processing | succeeded | failed */
    private String status;
    /** 成功时生成的条目 id。 */
    private Long entryId;
    /** 失败原因（对用户可见，已截断）。 */
    private String error;
    private Long operatorId;
    /**
     * 当前处理者的领取令牌（WORK-092 TASK-006）：领取时写入，写结果时按它做 fencing，
     * 防止"过期 worker 回来覆盖新 worker 的结果"。未在 processing 时为 null。
     */
    private String claimToken;
    /** 领取时间（UTC）：恢复任务据此判断是否超时未完成。 */
    private Instant processingSince;
    /** 已尝试处理的次数（每次领取 +1），便于观测反复失败的任务。 */
    private Integer attempts;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public Long getTargetCategoryId() {
        return targetCategoryId;
    }

    public void setTargetCategoryId(Long targetCategoryId) {
        this.targetCategoryId = targetCategoryId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getEntryId() {
        return entryId;
    }

    public void setEntryId(Long entryId) {
        this.entryId = entryId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
    }

    public Instant getProcessingSince() {
        return processingSince;
    }

    public void setProcessingSince(Instant processingSince) {
        this.processingSince = processingSince;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
