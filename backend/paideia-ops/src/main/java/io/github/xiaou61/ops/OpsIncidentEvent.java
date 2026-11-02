package io.github.xiaou61.ops;

import java.time.Instant;

/** 事件时间线的一行（WORK-043）。只追加，不原地改。 */
class OpsIncidentEvent {

    static final String KIND_FIRED = "FIRED";
    static final String KIND_RESOLVED = "RESOLVED";
    static final String KIND_CLOSED = "CLOSED";
    static final String KIND_DIAGNOSIS_STARTED = "DIAGNOSIS_STARTED";
    static final String KIND_DIAGNOSIS_DONE = "DIAGNOSIS_DONE";
    static final String KIND_DIAGNOSIS_FAILED = "DIAGNOSIS_FAILED";
    static final String KIND_NOTE = "NOTE";

    private Long id;
    private Long incidentId;
    private String kind;
    private String message;
    private Long actorUserId;
    private String payloadJson;
    private Instant createdAt;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    Long getIncidentId() {
        return incidentId;
    }

    void setIncidentId(Long incidentId) {
        this.incidentId = incidentId;
    }

    String getKind() {
        return kind;
    }

    void setKind(String kind) {
        this.kind = kind;
    }

    String getMessage() {
        return message;
    }

    void setMessage(String message) {
        this.message = message;
    }

    Long getActorUserId() {
        return actorUserId;
    }

    void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }

    String getPayloadJson() {
        return payloadJson;
    }

    void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
