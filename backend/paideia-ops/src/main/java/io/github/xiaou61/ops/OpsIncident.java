package io.github.xiaou61.ops;

import java.time.Instant;

/**
 * 一条告警事件（WORK-043）。
 *
 * <p><b>`dedupe_key` 是"同一指纹只能有一个 OPEN 事件"的实现</b>：OPEN 时等于 fingerprint，
 * RESOLVED 时置 NULL；唯一键允许多条历史 RESOLVED 行，但不会出现两个同时 OPEN 的同一告警。
 *
 * <p>指纹只取稳定标签；时间、摘要、注释每次通知都可能变，含进去会让去重失效。
 */
class OpsIncident {

    static final String STATUS_OPEN = "OPEN";
    static final String STATUS_RESOLVED = "RESOLVED";

    private Long id;
    private String fingerprint;
    private String dedupeKey;
    private String alertName;
    private String severity;
    private String status;
    private Instant firstSeen;
    private Instant lastSeen;
    private Instant resolvedAt;
    private int alertCount;
    private Instant lastDiagnosedAt;
    private Long lastDiagnosisId;
    private String labelsJson;
    private Instant createdAt;
    private Instant updatedAt;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    String getFingerprint() {
        return fingerprint;
    }

    void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    String getDedupeKey() {
        return dedupeKey;
    }

    void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    String getAlertName() {
        return alertName;
    }

    void setAlertName(String alertName) {
        this.alertName = alertName;
    }

    String getSeverity() {
        return severity;
    }

    void setSeverity(String severity) {
        this.severity = severity;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    Instant getFirstSeen() {
        return firstSeen;
    }

    void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    Instant getLastSeen() {
        return lastSeen;
    }

    void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    Instant getResolvedAt() {
        return resolvedAt;
    }

    void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    int getAlertCount() {
        return alertCount;
    }

    void setAlertCount(int alertCount) {
        this.alertCount = alertCount;
    }

    Instant getLastDiagnosedAt() {
        return lastDiagnosedAt;
    }

    void setLastDiagnosedAt(Instant lastDiagnosedAt) {
        this.lastDiagnosedAt = lastDiagnosedAt;
    }

    Long getLastDiagnosisId() {
        return lastDiagnosisId;
    }

    void setLastDiagnosisId(Long lastDiagnosisId) {
        this.lastDiagnosisId = lastDiagnosisId;
    }

    String getLabelsJson() {
        return labelsJson;
    }

    void setLabelsJson(String labelsJson) {
        this.labelsJson = labelsJson;
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
