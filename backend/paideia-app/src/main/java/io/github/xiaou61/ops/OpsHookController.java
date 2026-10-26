package io.github.xiaou61.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Grafana 告警 webhook（WORK-042 REQ-008）。
 *
 * <p>两道门都在这里之外：安全链用 {@code LocalOnlyAuthorizationManager} 要求回环来源；
 * 本类再校验共享令牌（常量时间比较）。令牌为空时**一律拒绝**——"没配置"不能等于"不设防"。
 *
 * <p>只对 firing 告警动作；resolved 不建行。忙碌与冷却都返回 200：Grafana 会对失败的目标
 * 重试，返回 5xx 只会在告警风暴时再叠一层重试风暴。
 */
@RestController
@RequestMapping("/api/v1/ops/hooks")
class OpsHookController {

    private static final Logger log = LoggerFactory.getLogger(OpsHookController.class);

    /** Grafana 的 webhook 载荷很小；超过这个上限的请求直接拒绝。 */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final int MAX_ALERTS_IN_NAME = 3;

    private final OpsDiagnosisService service;
    private final OpsProperties properties;

    OpsHookController(OpsDiagnosisService service, OpsProperties properties) {
        this.service = service;
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
                    .body(Map.of("accepted", false, "skipped", "unauthorized"));
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("accepted", false, "skipped", "too-large"));
        }

        List<String> names = firingAlertNames(payload);
        if (names.isEmpty()) {
            return ResponseEntity.ok(Map.of("accepted", false, "skipped", "no-firing"));
        }

        OpsProperties.Diagnosis config = properties.getDiagnosis();
        if (!config.isEnabled()) {
            return ResponseEntity.ok(Map.of("accepted", false, "skipped", "disabled"));
        }
        long userId = config.getUserId();
        if (userId <= 0) {
            log.warn("ops.hook.skipped reason=no-user alerts={}", names);
            return ResponseEntity.ok(Map.of("accepted", false, "skipped", "no-user"));
        }

        String alertName = trim(String.join(", ", names), 128);
        long id = service.start("alert", alertName, null, userId);
        if (id < 0) {
            return ResponseEntity.ok(Map.of("accepted", false, "skipped", "cooldown-or-busy"));
        }
        log.info("ops.hook.accepted id={} alerts={}", id, names);
        return ResponseEntity.ok(Map.of("accepted", true, "id", id));
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

    private static List<String> firingAlertNames(GrafanaPayload payload) {
        if (payload == null || payload.alerts() == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (GrafanaPayload.Alert alert : payload.alerts()) {
            if (alert == null || !"firing".equalsIgnoreCase(alert.status())) {
                continue;
            }
            if (alert.labels() == null) {
                continue;
            }
            String name = alert.labels().get("alertname");
            if (name != null && !name.isBlank()) {
                names.add(name.strip());
            }
            if (names.size() >= MAX_ALERTS_IN_NAME) {
                break;
            }
        }
        return List.copyOf(names);
    }

    private static String trim(String value, int max) {
        String strip = Objects.requireNonNullElse(value, "").strip();
        return strip.length() <= max ? strip : strip.substring(0, max);
    }

    /** Grafana 的 webhook 载荷只取需要的两个字段；多余字段由 Jackson 忽略。 */
    record GrafanaPayload(String status, List<Alert> alerts) {

        record Alert(String status, Map<String, String> labels) {
        }
    }
}
