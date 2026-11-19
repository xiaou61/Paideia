package io.github.xiaou61.ai.internal.essay;

import java.math.BigDecimal;
import java.time.Instant;

/** `ai_essay_verifications` 一行（WORK-084）。JSON 列用 *Json 字符串承接，审计时才需要解析。 */
public class EssayVerificationRow {

    private Long id;
    private String status;
    private String questionTitle;
    private String draftAnswer;
    private String finalAnswer;
    private BigDecimal confidence;
    private String issuesJson;
    private String snippetsJson;
    private Long categoryId;
    private String fingerprint;
    private String sourceUrl;
    private String sourceTitle;
    private Long entryId;
    private String runId;
    private String promptVersion;
    private String model;
    private String reviewNote;
    private Long reviewedBy;
    private Instant reviewedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getQuestionTitle() { return questionTitle; }
    public void setQuestionTitle(String questionTitle) { this.questionTitle = questionTitle; }
    public String getDraftAnswer() { return draftAnswer; }
    public void setDraftAnswer(String draftAnswer) { this.draftAnswer = draftAnswer; }
    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getIssuesJson() { return issuesJson; }
    public void setIssuesJson(String issuesJson) { this.issuesJson = issuesJson; }
    public String getSnippetsJson() { return snippetsJson; }
    public void setSnippetsJson(String snippetsJson) { this.snippetsJson = snippetsJson; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getSourceTitle() { return sourceTitle; }
    public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }
    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
