package io.github.xiaou61.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 告警事件的生命周期（WORK-043）。
 *
 * <p>指纹只取稳定标签（alertname/severity/instance/job）；Grafana 自带 fingerprint 时优先用它。
 * 同一指纹同时只能有一个 OPEN 事件（靠 `dedupe_key` 唯一键），恢复后再次触发是新事件。
 */
@Service
class OpsIncidentService {

    static final Set<String> LABEL_KEYS = Set.of(
            "alertname", "severity", "instance", "job", "unit",
            "mountpoint", "device", "fstype", "service", "route", "status");

    private static final int MAX_LABELS_JSON = 2_000;
    private static final int MAX_MESSAGE = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpsIncidentMapper incidents;
    private final OpsDiagnosisMapper diagnoses;

    OpsIncidentService(OpsIncidentMapper incidents, OpsDiagnosisMapper diagnoses) {
        this.incidents = incidents;
        this.diagnoses = diagnoses;
    }

    /** 指纹：Grafana 的 fingerprint 优先；否则 alertname|severity|instance|job 的 SHA-256。 */
    static String fingerprint(String alertName, String severity, Map<String, String> labels,
                              String grafanaFingerprint) {
        String provided = normalizeFingerprint(grafanaFingerprint);
        if (provided != null) {
            return provided;
        }
        Map<String, String> safe = labels == null ? Map.of() : labels;
        String base = safe(alertName) + "|" + safe(severity) + "|"
                + safe(safe.get("instance")) + "|" + safe(safe.get("job"));
        return sha256(base);
    }

    private static String normalizeFingerprint(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty() || value.length() > 64 || !value.matches("[a-z0-9_-]+")) {
            return null;
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    UpsertResult upsertFiring(String grafanaFingerprint, String alertName, String severity,
                              Map<String, String> labels, Instant firstSeen, Instant lastSeen) {
        if (alertName == null || alertName.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "告警缺少 alertname");
        }
        Instant now = lastSeen == null ? Instant.now() : lastSeen;
        Instant first = firstSeen == null ? now : firstSeen;
        String fingerprint = fingerprint(alertName, severity, labels, grafanaFingerprint);

        OpsIncident row = new OpsIncident();
        row.setFingerprint(fingerprint);
        row.setDedupeKey(fingerprint);
        row.setAlertName(trim(alertName, 128));
        row.setSeverity(trim(severity, 16));
        row.setStatus(OpsIncident.STATUS_OPEN);
        row.setFirstSeen(first);
        row.setLastSeen(now);
        row.setAlertCount(1);
        row.setLabelsJson(labelsJson(labels));
        row.setCreatedAt(first);
        row.setUpdatedAt(now);

        int affected = incidents.upsertFiring(row);
        if (row.getId() == null) {
            throw new IllegalStateException("事件插入后没有返回 id");
        }
        if (affected == 1) {
            appendEvent(row.getId(), OpsIncidentEvent.KIND_FIRED, "告警触发：" + alertName, null);
        }
        return new UpsertResult(row.getId(), fingerprint, affected == 1);
    }

    /** @return 被关闭的事件 id；没有 OPEN 事件时返回 -1 */
    long resolve(String grafanaFingerprint, String alertName, String severity,
                 Map<String, String> labels, Instant at) {
        Instant now = at == null ? Instant.now() : at;
        String fingerprint = fingerprint(alertName, severity, labels, grafanaFingerprint);
        Optional<OpsIncident> open = incidents.findOpen(fingerprint);
        if (open.isEmpty()) {
            return -1;
        }
        int updated = incidents.resolve(fingerprint, now, now);
        if (updated == 0) {
            return -1;
        }
        long id = open.get().getId();
        appendEvent(id, OpsIncidentEvent.KIND_RESOLVED, "告警恢复", null);
        return id;
    }

    /** 事件是否可以开始诊断：必须 OPEN，且上次诊断已过冷却（只读，不写）。 */
    boolean canDiagnose(long incidentId, Instant now, long cooldownSeconds) {
        OpsIncident incident = incidents.findById(incidentId).orElse(null);
        if (incident == null || !OpsIncident.STATUS_OPEN.equals(incident.getStatus())) {
            return false;
        }
        Instant last = incident.getLastDiagnosedAt();
        return last == null || !last.plusSeconds(cooldownSeconds).isAfter(now);
    }

    /** 在诊断行插入成功后调用；单飞保证不会有并发 claim。 */
    void markDiagnosed(long incidentId, Instant now) {
        incidents.markDiagnosed(incidentId, now);
    }

    void markStarted(long incidentId, long diagnosisId) {
        appendEvent(incidentId, OpsIncidentEvent.KIND_DIAGNOSIS_STARTED,
                "开始诊断 #" + diagnosisId, null);
    }

    void markDone(long incidentId, long diagnosisId, String model) {
        Instant now = Instant.now();
        incidents.linkDiagnosis(incidentId, diagnosisId, now);
        appendEvent(incidentId, OpsIncidentEvent.KIND_DIAGNOSIS_DONE,
                "诊断完成 #" + diagnosisId + (model == null ? "" : "（" + model + "）"), null);
    }

    void markFailed(long incidentId, long diagnosisId, String error) {
        Instant now = Instant.now();
        incidents.linkDiagnosis(incidentId, diagnosisId, now);
        appendEvent(incidentId, OpsIncidentEvent.KIND_DIAGNOSIS_FAILED,
                "诊断失败 #" + diagnosisId + "：" + trim(error, 500), null);
    }

    void appendEvent(long incidentId, String kind, String message, Long actorUserId) {
        OpsIncidentEvent event = new OpsIncidentEvent();
        event.setIncidentId(incidentId);
        event.setKind(kind);
        event.setMessage(trim(message, MAX_MESSAGE));
        event.setActorUserId(actorUserId);
        event.setCreatedAt(Instant.now());
        incidents.insertEvent(event);
    }

    Optional<OpsIncident> find(long id) {
        return incidents.findById(id);
    }

    List<View> list(String status, int limit) {
        String normalized = status == null || status.isBlank()
                ? null : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (normalized != null
                && !OpsIncident.STATUS_OPEN.equals(normalized)
                && !OpsIncident.STATUS_RESOLVED.equals(normalized)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "status 只能是 OPEN 或 RESOLVED");
        }
        int capped = Math.max(1, Math.min(100, limit));
        return incidents.listRecent(normalized, capped).stream().map(View::of).toList();
    }

    Detail detail(long id) {
        OpsIncident incident = incidents.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "事件不存在"));
        return new Detail(View.of(incident),
                incidents.listEvents(id, 200).stream().map(EventView::of).toList(),
                diagnoses.listByIncident(id, 50).stream().map(DiagnosisView::of).toList());
    }

    Detail addNote(long id, String message, long actorUserId) {
        if (message == null || message.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "备注不能为空");
        }
        incidents.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "事件不存在"));
        appendEvent(id, OpsIncidentEvent.KIND_NOTE, message, actorUserId);
        return detail(id);
    }

    private static String labelsJson(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        for (String key : LABEL_KEYS) {
            String value = labels.get(key);
            if (value != null && !value.isBlank()) {
                kept.put(key, trim(value, 200));
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        try {
            String json = JSON.writeValueAsString(kept);
            return json.length() <= MAX_LABELS_JSON ? json : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static Instant parseTime(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            // Grafana 的 startsAt 通常带 +08:00 偏移
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    record UpsertResult(long id, String fingerprint, boolean created) {
    }

    record View(long id, String fingerprint, String alertName, String severity, String status,
                Instant firstSeen, Instant lastSeen, Instant resolvedAt, int alertCount,
                Instant lastDiagnosedAt, Long lastDiagnosisId) {

        static View of(OpsIncident row) {
            return new View(row.getId(), row.getFingerprint(), row.getAlertName(), row.getSeverity(),
                    row.getStatus(), row.getFirstSeen(), row.getLastSeen(), row.getResolvedAt(),
                    row.getAlertCount(), row.getLastDiagnosedAt(), row.getLastDiagnosisId());
        }
    }

    record EventView(long id, String kind, String message, Long actorUserId, Instant createdAt) {

        static EventView of(OpsIncidentEvent row) {
            return new EventView(row.getId(), row.getKind(), row.getMessage(),
                    row.getActorUserId(), row.getCreatedAt());
        }
    }

    record DiagnosisView(long id, String triggerType, String status, String model,
                         Integer rating, String errorMessage, Instant createdAt, Instant finishedAt,
                         Integer inputTokens, Integer outputTokens) {

        static DiagnosisView of(OpsDiagnosisRow row) {
            return new DiagnosisView(row.getId(), row.getTriggerType(), row.getStatus(), row.getModel(),
                    row.getRating(), row.getErrorMessage(), row.getCreatedAt(), row.getFinishedAt(),
                    row.getInputTokens(), row.getOutputTokens());
        }
    }

    record Detail(View incident, List<EventView> events, List<DiagnosisView> diagnoses) {
    }
}
