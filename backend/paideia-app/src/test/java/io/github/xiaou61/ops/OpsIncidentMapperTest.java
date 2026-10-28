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

    @Autowired
    OpsDiagnosisMapper diagnoses;

    @Test
    @DisplayName("补诊断候选只有一条，且跳过已有 DONE 的事件")
    void catchUpCandidateIsSingleAndSkipsDone() {
        // 用远超其他用例的未来时间戳隔离共享测试库（分页用例用的是 2099）
        Instant now = Instant.parse("2102-01-01T00:00:00Z");
        Instant since = now.minusSeconds(60);
        Instant diagnosedBefore = now.minusSeconds(600);
        long first = incidents.upsertFiring(null, "catch-" + UUID.randomUUID(), "warning",
                Map.of("instance", "a", "job", "j"), now, now).id();
        long second = incidents.upsertFiring(null, "catch-" + UUID.randomUUID(), "warning",
                Map.of("instance", "b", "job", "j"), now, now).id();

        var candidate = mapper.findCatchUpCandidate(since, diagnosedBefore);
        assertThat(candidate).isPresent();
        assertThat(candidate.get().getId()).isIn(first, second);

        OpsDiagnosisRow done = new OpsDiagnosisRow();
        done.setTriggerType("alert");
        done.setIncidentId(first);
        done.setStatus(OpsDiagnosisRow.STATUS_PENDING);
        done.setRequestedBy(1L);
        done.setCreatedAt(now);
        diagnoses.insert(done);
        diagnoses.markDone(done.getId(), "deepseek-flash", 1, 1, "## 结论\n已确认", now);

        var after = mapper.findCatchUpCandidate(since, diagnosedBefore);
        assertThat(after).isPresent();
        assertThat(after.get().getId()).isEqualTo(second);
    }

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
    @DisplayName("手动关闭只对 OPEN 生效，第二次返回 0")
    void resolveByIdOnlyOpen() {
        String alert = "test-" + UUID.randomUUID();
        Instant now = Instant.now();
        long id = incidents.upsertFiring(null, alert, "warning",
                Map.of("instance", "x", "job", "y"), now, now).id();

        assertThat(mapper.resolveById(id, now.plusSeconds(1))).isEqualTo(1);
        OpsIncident closed = mapper.findById(id).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(OpsIncident.STATUS_RESOLVED);
        assertThat(closed.getDedupeKey()).isNull();
        assertThat(mapper.resolveById(id, now.plusSeconds(2))).isZero();
    }

    @Test
    @DisplayName("相同 last_seen 翻页不丢行：keyset 用 id 破平")
    void pageWithEqualLastSeenDoesNotSkip() {
        // 三条用 2101、补诊断用例用 2102，互不进入对方的查询区间
        Instant same = Instant.parse("2101-01-01T00:00:00Z");
        long first = incidents.upsertFiring(null, "page-" + UUID.randomUUID(), "warning",
                Map.of("instance", "p1", "job", "j"), same, same).id();
        long second = incidents.upsertFiring(null, "page-" + UUID.randomUUID(), "warning",
                Map.of("instance", "p2", "job", "j"), same, same).id();
        long third = incidents.upsertFiring(null, "page-" + UUID.randomUUID(), "warning",
                Map.of("instance", "p3", "job", "j"), same, same).id();

        // beforeLastSeen 同时充当上界，把其他用例的行挡在外面
        var page1 = mapper.listPage(null, same.minusSeconds(60),
                same.plusSeconds(1), Long.MAX_VALUE, 2);
        assertThat(page1).hasSize(2);
        assertThat(page1).extracting(OpsIncident::getId).containsExactly(third, second);

        OpsIncident last = page1.getLast();
        var page2 = mapper.listPage(null, same.minusSeconds(60),
                last.getLastSeen(), last.getId(), 2);
        assertThat(page2).extracting(OpsIncident::getId).containsExactly(first);
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
