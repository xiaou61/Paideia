package io.github.xiaou61.ops;

import java.time.Instant;

/**
 * 一行运维复盘（WORK-086）。
 *
 * <p>与 {@link OpsDiagnosisRow} 的区别：诊断是模型对一次事件的**推测**，复盘是人确认过的
 * **事实**。所以复盘有审批人与审批时间，只有 {@code APPROVED} 才进知识召回。
 */
class PostmortemRow {

    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_ARCHIVED = "ARCHIVED";

    static final String ORIGIN_MANUAL = "manual";
    static final String ORIGIN_AUTO = "auto";

    private Long id;
    private Long incidentId;
    private Long diagnosisId;
    private String alertName;
    private String title;
    private String severity;
    private String status;
    private String rootCause;
    private String impact;
    private String timelineMd;
    private String actionsMd;
    private String bodyMd;
    private String evidenceRefs;
    private String origin;
    private Long createdBy;
    private Long approvedBy;
    private Instant approvedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(Long incidentId) {
        this.incidentId = incidentId;
    }

    public Long getDiagnosisId() {
        return diagnosisId;
    }

    public void setDiagnosisId(Long diagnosisId) {
        this.diagnosisId = diagnosisId;
    }

    public String getAlertName() {
        return alertName;
    }

    public void setAlertName(String alertName) {
        this.alertName = alertName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getImpact() {
        return impact;
    }

    public void setImpact(String impact) {
        this.impact = impact;
    }

    public String getTimelineMd() {
        return timelineMd;
    }

    public void setTimelineMd(String timelineMd) {
        this.timelineMd = timelineMd;
    }

    public String getActionsMd() {
        return actionsMd;
    }

    public void setActionsMd(String actionsMd) {
        this.actionsMd = actionsMd;
    }

    public String getBodyMd() {
        return bodyMd;
    }

    public void setBodyMd(String bodyMd) {
        this.bodyMd = bodyMd;
    }

    public String getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(String evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(Long approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
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
