package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 事件状态机的真实 SQL（WORK-043 AC-001/AC-002/AC-003）。
 *
 * <p>四条关键断言：重复 firing 不新建；resolved 后同一指纹是空；再次 firing 是新事件；
 * 事件级冷却只挡窗口内。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class OpsIncidentMapperTest {

    @Autowired
    OpsIncidentService incidents;

    @Autowired
    OpsIncidentMapper mapper;

    @Test
    @DisplayName("重复 firing 只更新计数；resolved 后再 firing 是新事件")
    void lifecycle() {
        String alert = "test-" + UUID.randomUUID();
        Map<String, String> labels = Map.of("instance", "127.0.0.1:9100", "job", "node");
        Instant now = Instant.now();

        OpsIncidentService.UpsertResult first = incidents.upsertFiring(
                null, alert, "warning", labels, now, now);
        assertThat(first.created()).isTrue();

        OpsIncidentService.UpsertResult duplicate = incidents.upsertFiring(
                null, alert, "warning", labels, now, now.plusSeconds(60));
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(duplicate.created()).isFalse();

        OpsIncident row = mapper.findById(first.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OpsIncident.STATUS_OPEN);
        assertThat(row.getAlertCount()).isEqualTo(2);
        assertThat(row.getLastSeen()).isAfterOrEqualTo(row.getFirstSeen());
        assertThat(mapper.listEvents(first.id(), 20))
                .filteredOn(event -> OpsIncidentEvent.KIND_FIRED.equals(event.getKind()))
                .hasSize(1);

        long resolved = incidents.resolve(null, alert, "warning", labels, now.plusSeconds(120));
        assertThat(resolved).isEqualTo(first.id());
        OpsIncident closed = mapper.findById(first.id()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(OpsIncident.STATUS_RESOLVED);
        assertThat(closed.getDedupeKey()).isNull();
        assertThat(closed.getResolvedAt()).isNotNull();

        OpsIncidentService.UpsertResult reopened = incidents.upsertFiring(
                null, alert, "warning", labels, now.plusSeconds(180), now.plusSeconds(180));
        assertThat(reopened.created()).isTrue();
        assertThat(reopened.id()).isNotEqualTo(first.id());
        assertThat(mapper.listEvents(reopened.id(), 20))
                .extracting(OpsIncidentEvent::getKind)
                .contains(OpsIncidentEvent.KIND_FIRED);
    }

    @Test
    @DisplayName("事件级冷却：窗口内不能再诊断，过了可以")
    void diagnosisCooldown() {
        String alert = "test-" + UUID.randomUUID();
        Instant now = Instant.now();
        OpsIncidentService.UpsertResult result = incidents.upsertFiring(
                null, alert, "warning", Map.of("instance", "x", "job", "y"), now, now);

        assertThat(incidents.canDiagnose(result.id(), now, 600)).isTrue();
        incidents.markDiagnosed(result.id(), now);
        assertThat(incidents.canDiagnose(result.id(), now.plusSeconds(599), 600)).isFalse();
        assertThat(incidents.canDiagnose(result.id(), now.plusSeconds(601), 600)).isTrue();
    }

    @Test
    @DisplayName("详情按时间线返回；备注只追加")
    void detailAndNote() {
        String alert = "test-" + UUID.randomUUID();
        Instant now = Instant.now();
        long id = incidents.upsertFiring(null, alert, "warning",
                Map.of("instance", "x", "job", "y"), now, now).id();

        OpsIncidentService.Detail detail = incidents.addNote(id, "已电话确认", 7L);

        assertThat(detail.incident().id()).isEqualTo(id);
        assertThat(detail.events())
                .extracting(OpsIncidentService.EventView::kind)
                .containsExactly(OpsIncidentEvent.KIND_FIRED, OpsIncidentEvent.KIND_NOTE);
        assertThat(detail.events().getLast().message()).isEqualTo("已电话确认");
        assertThat(detail.events().getLast().actorUserId()).isEqualTo(7L);
    }
}
