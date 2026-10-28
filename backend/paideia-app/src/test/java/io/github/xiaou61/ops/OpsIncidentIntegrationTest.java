package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.security.AuthPort;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 管理端事件端点（WORK-043 AC-002/AC-015）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class OpsIncidentIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthPort authPort;

    @Autowired
    OpsIncidentService incidents;

    @Autowired
    OpsDiagnosisMapper diagnoses;

    @Test
    @DisplayName("复用窗口只认 DONE：FAILED 不拦后续诊断")
    void reuseIgnoresFailedDiagnosis() {
        Instant now = Instant.now();
        String instance = "reuse-" + UUID.randomUUID();
        String alert = "reuse-" + UUID.randomUUID();
        long oldId = incidents.upsertFiring(null, alert, "warning",
                Map.of("instance", instance, "job", "j"), now, now).id();
        long newId = incidents.upsertFiring(null, alert + "-next", "warning",
                Map.of("instance", instance, "job", "j"), now, now).id();

        OpsDiagnosisRow failed = diagnosis(oldId, now);
        diagnoses.markFailed(failed.getId(), "上游超时", now);
        assertThat(incidents.recentDoneDiagnosisForInstance(newId, now, 600)).isEmpty();

        OpsDiagnosisRow done = diagnosis(oldId, now);
        diagnoses.markDone(done.getId(), "deepseek-flash", 1, 1, "## 结论\n已确认", now);
        assertThat(incidents.recentDoneDiagnosisForInstance(newId, now, 600))
                .contains(done.getId());
    }

    private OpsDiagnosisRow diagnosis(long incidentId, Instant now) {
        OpsDiagnosisRow row = new OpsDiagnosisRow();
        row.setTriggerType("alert");
        row.setIncidentId(incidentId);
        row.setStatus(OpsDiagnosisRow.STATUS_PENDING);
        row.setRequestedBy(1L);
        row.setCreatedAt(now);
        diagnoses.insert(row);
        return row;
    }

    @Test
    @DisplayName("管理员能看列表/详情/时间线并可追加备注")
    void adminCanReadAndNote() throws Exception {
        long id = newIncident("integration-" + UUID.randomUUID());

        mockMvc.perform(get("/api/v1/ops/admin/incidents").header("Authorization", tokenFor("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/v1/ops/admin/incidents/{id}", id)
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident.id").value(id))
                .andExpect(jsonPath("$.data.events[*].kind").value(
                        org.hamcrest.Matchers.hasItem(OpsIncidentEvent.KIND_FIRED)));

        mockMvc.perform(post("/api/v1/ops/admin/incidents/{id}/notes", id)
                        .header("Authorization", tokenFor("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"已确认\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[*].kind").value(
                        org.hamcrest.Matchers.hasItem(OpsIncidentEvent.KIND_NOTE)));
    }

    @Test
    @DisplayName("学习者 403、匿名 401")
    void nonAdminIsRejected() throws Exception {
        long id = newIncident("integration-" + UUID.randomUUID());

        mockMvc.perform(get("/api/v1/ops/admin/incidents").header("Authorization", tokenFor("learner")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ops/admin/incidents")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ops/admin/incidents/{id}", id)
                        .header("Authorization", tokenFor("learner")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("不存在的事件返回 404")
    void missingIncidentIs404() throws Exception {
        mockMvc.perform(get("/api/v1/ops/admin/incidents/{id}", 999_999_999L)
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("手动关闭幂等：第二次不追加 CLOSED 时间线")
    void closeIsIdempotent() throws Exception {
        long id = newIncident("integration-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/ops/admin/incidents/{id}/close", id)
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident.status").value(OpsIncident.STATUS_RESOLVED));

        mockMvc.perform(post("/api/v1/ops/admin/incidents/{id}/close", id)
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isOk());

        var events = incidents.detail(id).events();
        assertThat(events).filteredOn(event -> OpsIncidentEvent.KIND_CLOSED.equals(event.kind()))
                .hasSize(1)
                .first()
                .satisfies(event -> assertThat(event.actorUserId()).isEqualTo(1L));
    }

    @Test
    @DisplayName("手动关闭后同一告警再触发是新事件")
    void closeThenFiringCreatesNewIncident() throws Exception {
        String alert = "integration-" + UUID.randomUUID();
        long id = newIncident(alert);

        mockMvc.perform(post("/api/v1/ops/admin/incidents/{id}/close", id)
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isOk());

        long next = newIncident(alert);
        assertThat(next).isNotEqualTo(id);
        assertThat(incidents.find(id).orElseThrow().getStatus())
                .isEqualTo(OpsIncident.STATUS_RESOLVED);
        assertThat(incidents.find(next).orElseThrow().getStatus())
                .isEqualTo(OpsIncident.STATUS_OPEN);
    }

    @Test
    @DisplayName("游标只给一半或 sinceHours 非法时返回 400")
    void halfCursorIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/ops/admin/incidents?beforeId=1")
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/ops/admin/incidents?sinceHours=0")
                        .header("Authorization", tokenFor("admin")))
                .andExpect(status().isBadRequest());
    }

    private long newIncident(String alert) {
        Instant now = Instant.now();
        return incidents.upsertFiring(null, alert, "warning",
                Map.of("instance", "127.0.0.1:9100", "job", "node"), now, now).id();
    }

    private String tokenFor(String role) {
        return "Bearer " + authPort.issue(new AuthPort.Subject("1", role)).value();
    }
}
