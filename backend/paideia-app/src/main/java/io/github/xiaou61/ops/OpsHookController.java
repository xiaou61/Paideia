package io.github.xiaou61.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Grafana 告警 webhook（WORK-042；WORK-043 扩为事件 upsert/resolve）。
 *
 * <p>两道门在控制器之外：安全链要求回环来源；本类再校验共享令牌（常量时间比较）。
 * 令牌为空时一律拒绝——"没配置"不能等于"不设防"。
 *
 * <p>一条通知最多处理 {@code max-alerts-per-notification} 个 alert；至多触发一次诊断，
 * 避免一次告警风暴把积分打穿。critical 存在时，同通知的 warning 只记时间线。
 */
@RestController
@RequestMapping("/api/v1/ops/hooks")
class OpsHookController {

    private static final Logger log = LoggerFactory.getLogger(OpsHookController.class);

    /** Grafana 的 webhook 载荷很小；超过这个上限的请求直接拒绝。 */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private final OpsDiagnosisService diagnosis;
    private final OpsIncidentService incidents;
    private final OpsProperties properties;

    OpsHookController(OpsDiagnosisService diagnosis,
                      OpsIncidentService incidents,
                      OpsProperties properties) {
        this.diagnosis = diagnosis;
        this.incidents = incidents;
        this.properties = properties;
    }

    @PostMapping("/grafana")
    ResponseEntity<Map<String, Object>> grafana(
            @RequestHeader(name = "X-Paideia-Ops-Token", required = false) String token,
            @RequestBody(required = false) GrafanaPayload payload,
            jakarta.servlet.http.HttpServletRequest request) {

        if (!tokenMatches(token)) {
            log.warn("ops.hook.rejected reason=token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(body(false, "unauthorized", List.of(), null));
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(body(false, "too-large", List.of(), null));
        }
        if (payload == null || payload.alerts() == null || payload.alerts().isEmpty()) {
            return ResponseEntity.ok(body(false, "no-firing", List.of(), null));
        }

        Instant now = Instant.now();
        OpsProperties.Incidents config = properties.getIncidents();
        int maxAlerts = Math.max(1, config.getMaxAlertsPerNotification());
        List<Long> incidentIds = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        boolean hasCritical = false;
        int processed = 0;

        for (GrafanaPayload.Alert alert : payload.alerts()) {
            if (alert == null || processed >= maxAlerts) {
                break;
            }
            String name = label(alert.labels(), "alertname");
            if (name == null) {
                continue;
            }
            processed++;
            String severity = label(alert.labels(), "severity");
            String status = statusOf(alert, payload);

            if ("resolved".equals(status)) {
                long closed = incidents.resolve(alert.fingerprint(), name, severity,
                        alert.labels(), now);
                if (closed > 0) {
                    incidentIds.add(closed);
                }
                continue;
            }
            if (!"firing".equals(status)) {
                continue;
            }

            OpsIncidentService.UpsertResult result = incidents.upsertFiring(
                    alert.fingerprint(), name, severity, alert.labels(),
                    OpsIncidentService.parseTime(alert.startsAt(), now), now);
            incidentIds.add(result.id());
            if ("critical".equalsIgnoreCase(severity)) {
                hasCritical = true;
            }
            candidates.add(new Candidate(result.id(), name, severity, result.created()));
        }

        if (candidates.isEmpty()) {
            return ResponseEntity.ok(body(false, "no-firing", incidentIds, null));
        }

        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (hasCritical && config.isSuppressWarningWhenCritical()
                    && !"critical".equalsIgnoreCase(candidate.severity())) {
                incidents.appendEvent(candidate.id(), OpsIncidentEvent.KIND_NOTE,
                        "同通知存在 critical 事件，抑制本次诊断", null);
                continue;
            }
            eligible.add(candidate);
        }
        if (eligible.isEmpty()) {
            return ResponseEntity.ok(body(false, "suppressed", incidentIds, null));
        }

        Candidate chosen = eligible.stream()
                .filter(Candidate::created)
                .findFirst()
                .orElse(eligible.getFirst());

        if (!properties.getDiagnosis().isEnabled()) {
            return ResponseEntity.ok(body(false, "disabled", incidentIds, null));
        }
        long userId = properties.getDiagnosis().getUserId();
        if (userId <= 0) {
            log.warn("ops.hook.skipped reason=no-user alerts={}", incidentIds);
            return ResponseEntity.ok(body(false, "no-user", incidentIds, null));
        }

        long diagnosisId = diagnosis.start("alert", chosen.id(), chosen.alertName(), null, userId);
        if (diagnosisId < 0) {
            return ResponseEntity.ok(body(false, "cooldown-or-busy", incidentIds, null));
        }
        log.info("ops.hook.accepted id={} incident={} alerts={}",
                diagnosisId, chosen.id(), incidentIds);
        return ResponseEntity.ok(body(true, null, incidentIds, diagnosisId));
    }

    private boolean tokenMatches(String token) {
        String expected = properties.getHookToken();
        if (expected == null || expected.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static String statusOf(GrafanaPayload.Alert alert, GrafanaPayload payload) {
        String value = alert.status();
        if (value == null || value.isBlank()) {
            value = payload.status();
        }
        if (value == null || value.isBlank()) {
            return "firing";
        }
        return value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String label(Map<String, String> labels, String key) {
        if (labels == null) {
            return null;
        }
        String value = labels.get(key);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Map<String, Object> body(boolean accepted, String skipped,
                                            List<Long> incidentIds, Long diagnosisId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", accepted);
        if (skipped != null) {
            body.put("skipped", skipped);
        }
        if (diagnosisId != null) {
            body.put("id", diagnosisId);
        }
        body.put("incidentIds", incidentIds);
        return body;
    }

    private record Candidate(long id, String alertName, String severity, boolean created) {
    }

    /** Grafana 的 webhook 载荷只取需要的字段；多余字段由 Jackson 忽略。 */
    record GrafanaPayload(String status, List<Alert> alerts) {

        record Alert(String status, Map<String, String> labels, String fingerprint,
                     String startsAt, String endsAt) {
        }
    }
}
