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
