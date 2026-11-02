package io.github.xiaou61.ops;

import java.time.Instant;

/**
 * 一条运维诊断记录（WORK-042）。
 *
 * <p>可变类 + `useGeneratedKeys`，与项目其它实体一致。报告全文放 `report_md`：
 * 诊断的价值就在于事后回看"当时我们是怎么判断的"，只留一行摘要把这件事抹掉了。
 */
class OpsDiagnosisRow {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_DONE = "DONE";
    static final String STATUS_FAILED = "FAILED";

    private Long id;
    private String triggerType;
    private String alertName;
    private String question;
    private String status;
    private String model;
    private Integer inputTokens;
    private Integer outputTokens;
    private String reportMd;
    private String errorMessage;
    private Long requestedBy;
    private Instant createdAt;
    private Instant finishedAt;
    private Long incidentId;
    private Integer rating;
    private String feedbackNote;
    private Instant feedbackAt;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    String getTriggerType() {
        return triggerType;
    }

    void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    String getAlertName() {
        return alertName;
    }

    void setAlertName(String alertName) {
        this.alertName = alertName;
    }

    String getQuestion() {
        return question;
    }

    void setQuestion(String question) {
        this.question = question;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
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

    String getReportMd() {
        return reportMd;
    }

    void setReportMd(String reportMd) {
        this.reportMd = reportMd;
    }

    String getErrorMessage() {
        return errorMessage;
    }

    void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    Long getRequestedBy() {
        return requestedBy;
    }

    void setRequestedBy(Long requestedBy) {
        this.requestedBy = requestedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getFinishedAt() {
        return finishedAt;
    }

    void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    Long getIncidentId() {
        return incidentId;
    }

    void setIncidentId(Long incidentId) {
        this.incidentId = incidentId;
    }

    Integer getRating() {
        return rating;
    }

    void setRating(Integer rating) {
        this.rating = rating;
    }

    String getFeedbackNote() {
        return feedbackNote;
    }

    void setFeedbackNote(String feedbackNote) {
        this.feedbackNote = feedbackNote;
    }

    Instant getFeedbackAt() {
        return feedbackAt;
    }

    void setFeedbackAt(Instant feedbackAt) {
        this.feedbackAt = feedbackAt;
    }
}
