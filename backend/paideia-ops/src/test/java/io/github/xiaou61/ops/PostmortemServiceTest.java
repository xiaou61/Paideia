package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** WORK-086 复盘：从诊断起草、闸门式状态机、同一事件只留一份生效复盘。 */
class PostmortemServiceTest {

    private PostmortemMapper postmortems;
    private OpsDiagnosisMapper diagnoses;
    private OpsIncidentService incidents;
    private PostmortemService service;

    private static final Instant T0 = Instant.parse("2026-11-19T06:14:00Z");

    private static final String REPORT = """
            ## 结论

            应用在 14:14–14:30 被外部重启四次，`HikariPool` 因连接池只有 2 条而反复等待超时。

            ## 证据

            - 日志窗口 14:07–14:23
            - process_start_time_seconds changes = 4

            ## 可能原因

            另一会话在反复部署，`systemctl restart` 打断了进行中的请求。
            **不是**崩溃。

            ## 建议动作

            1. 部署改为停旧起新，避免同一分钟多次重启
            2. 连接池上限从 2 提到 8

            ## 需要人工确认

            需要确认 14:16 那次重启的发起人。
            """;

    @BeforeEach
    void setUp() {
        postmortems = mock(PostmortemMapper.class);
        diagnoses = mock(OpsDiagnosisMapper.class);
        incidents = mock(OpsIncidentService.class);
        // 真实 MyBatis 会把自增主键回填到 row.id；这里是 mock，手动模拟回填。
        when(postmortems.insert(any())).thenAnswer(invocation -> {
            ((PostmortemRow) invocation.getArgument(0)).setId(100L);
            return 1;
        });
        service = new PostmortemService(postmortems, diagnoses, incidents);
    }

    @Test
    @DisplayName("从诊断起草：根因/处置搬报告章节，时间线搬事件表，状态是草稿")
    void createDraft_copiesSectionsAndTimeline() {
        when(diagnoses.findById(17L)).thenReturn(Optional.of(doneDiagnosis(17L, 42L)));
        when(incidents.detail(42L)).thenReturn(new OpsIncidentService.Detail(
                incidentView("warning"),
                List.of(new OpsIncidentService.EventView(1L, OpsIncidentEvent.KIND_FIRED,
                                "告警触发：日志模板异常待处理", null, T0),
                        new OpsIncidentService.EventView(2L, OpsIncidentEvent.KIND_NOTE,
                                "已确认是磁盘", 9L, T0.plusSeconds(600))),
                List.of()));

        assertThat(service.createDraft(17L, 9L)).isEqualTo(100L);

        ArgumentCaptor<PostmortemRow> captor = ArgumentCaptor.forClass(PostmortemRow.class);
        verify(postmortems).insert(captor.capture());
        PostmortemRow row = captor.getValue();
        assertThat(row.getStatus()).isEqualTo(PostmortemRow.STATUS_DRAFT);
        assertThat(row.getOrigin()).isEqualTo(PostmortemRow.ORIGIN_MANUAL);
        assertThat(row.getSeverity()).isEqualTo("warning");
        assertThat(row.getRootCause()).contains("systemctl restart").doesNotContain("需要人工确认");
        assertThat(row.getActionsMd()).contains("连接池上限从 2 提到 8");
        assertThat(row.getImpact()).isEmpty();
        assertThat(row.getTimelineMd()).contains("告警触发：日志模板异常待处理")
                .contains("已确认是磁盘");
        assertThat(row.getEvidenceRefs()).contains("diagnosis:17").contains("incident:42");
        assertThat(row.getBodyMd()).startsWith("# 日志模板异常待处理 复盘")
                .contains("## 根因").contains("## 时间线").contains("## 处置与待办");
    }

    @Test
    @DisplayName("未完成的诊断不能起草")
    void createDraft_rejectsRunningDiagnosis() {
        OpsDiagnosisRow row = doneDiagnosis(18L, null);
        row.setStatus(OpsDiagnosisRow.STATUS_RUNNING);
        when(diagnoses.findById(18L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.createDraft(18L, 9L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(postmortems, never()).insert(any());
    }

    @Test
    @DisplayName("自动起草：同一事件已有草稿或生效复盘时不再起草")
    void createDraftIfAbsent_skipsWhenIncidentAlreadyHasOne() {
        when(diagnoses.findById(17L)).thenReturn(Optional.of(doneDiagnosis(17L, 42L)));
        when(postmortems.findLiveByIncident(42L)).thenReturn(Optional.of(draft(7L)));

        assertThat(service.createDraftIfAbsent(17L)).isEmpty();
        verify(postmortems, never()).insert(any());
    }

    @Test
    @DisplayName("自动起草：没有复盘时生成 origin=auto 的草稿")
    void createDraftIfAbsent_createsAutoDraft() {
        when(diagnoses.findById(17L)).thenReturn(Optional.of(doneDiagnosis(17L, 42L)));
        when(postmortems.findLiveByIncident(42L)).thenReturn(Optional.empty());
        when(incidents.detail(42L)).thenReturn(new OpsIncidentService.Detail(
                incidentView("warning"), List.of(), List.of()));

        assertThat(service.createDraftIfAbsent(17L)).contains(100L);
        ArgumentCaptor<PostmortemRow> captor = ArgumentCaptor.forClass(PostmortemRow.class);
        verify(postmortems).insert(captor.capture());
        assertThat(captor.getValue().getOrigin()).isEqualTo(PostmortemRow.ORIGIN_AUTO);
        assertThat(captor.getValue().getCreatedBy()).isNull();
    }

    @Test
    @DisplayName("编辑已确认的复盘会退回草稿并清掉审批人")
    void update_approvedGoesBackToDraft() {
        PostmortemRow row = draft(7L);
        row.setStatus(PostmortemRow.STATUS_APPROVED);
        row.setApprovedBy(3L);
        row.setApprovedAt(T0);
        when(postmortems.findById(7L)).thenReturn(Optional.of(row));

        service.update(7L, new PostmortemService.PostmortemDraft("新标题", null, null,
                "根因改写", null, null, null, null, null), 9L);

        ArgumentCaptor<PostmortemRow> captor = ArgumentCaptor.forClass(PostmortemRow.class);
        verify(postmortems).updateFields(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PostmortemRow.STATUS_DRAFT);
        assertThat(captor.getValue().getApprovedBy()).isNull();
        assertThat(captor.getValue().getTitle()).isEqualTo("新标题");
        assertThat(captor.getValue().getBodyMd()).contains("根因改写").contains("## 根因");
    }

    @Test
    @DisplayName("编辑时会脱敏：正文里的口令不落库")
    void update_redactsSecrets() {
        when(postmortems.findById(7L)).thenReturn(Optional.of(draft(7L)));

        service.update(7L, new PostmortemService.PostmortemDraft(null, null, null,
                "连不上库，password=abc123 被写进日志", null, null, null, null, null), 9L);

        ArgumentCaptor<PostmortemRow> captor = ArgumentCaptor.forClass(PostmortemRow.class);
        verify(postmortems).updateFields(captor.capture());
        assertThat(captor.getValue().getRootCause()).contains("***").doesNotContain("abc123");
    }

    @Test
    @DisplayName("归档后不能再编辑")
    void update_rejectsArchived() {
        PostmortemRow row = draft(7L);
        row.setStatus(PostmortemRow.STATUS_ARCHIVED);
        when(postmortems.findById(7L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.update(7L, new PostmortemService.PostmortemDraft(
                "改不动", null, null, null, null, null, null, null, null), 9L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("确认后同一事件旧的有效复盘被退役")
    void approve_retiresOlderApprovedOfSameIncident() {
        when(postmortems.findById(7L)).thenReturn(Optional.of(draft(7L)));
        when(postmortems.markApproved(eq(7L), eq(9L), any())).thenReturn(1);
        when(postmortems.archiveOthersOfIncident(eq(42L), eq(7L), any())).thenReturn(1);

        service.approve(7L, 9L);

        verify(postmortems).markApproved(eq(7L), eq(9L), any());
        verify(postmortems).archiveOthersOfIncident(eq(42L), eq(7L), any());
    }

    @Test
    @DisplayName("只有草稿能确认；重复确认是冲突")
    void approve_conflictsWhenAlreadyApproved() {
        when(postmortems.findById(7L)).thenReturn(Optional.of(draft(7L)));
        when(postmortems.markApproved(anyLong(), anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.approve(7L, 9L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("章节提取：只取指定标题到下一个标题之间")
    void section_stopsAtNextHeading() {
        assertThat(PostmortemService.section(REPORT, "可能原因"))
                .contains("另一会话在反复部署")
                .doesNotContain("建议动作")
                .doesNotContain("## 证据");
        assertThat(PostmortemService.section(REPORT, "不存在")).isEmpty();
    }

    @Test
    @DisplayName("正文渲染固定顺序且空章节不出现")
    void renderBody_skipsEmptySections() {
        PostmortemRow row = draft(7L);
        row.setImpact("");
        row.setEvidenceRefs(null);

        String body = PostmortemService.renderBody(row);

        assertThat(body).contains("## 根因").contains("## 时间线").contains("## 处置与待办");
        assertThat(body).doesNotContain("## 影响").doesNotContain("## 证据");
        assertThat(body.indexOf("## 根因")).isLessThan(body.indexOf("## 时间线"));
    }

    // ---------------------------------------------------------------- helpers

    private static OpsDiagnosisRow doneDiagnosis(long id, Long incidentId) {
        OpsDiagnosisRow row = new OpsDiagnosisRow();
        row.setId(id);
        row.setStatus(OpsDiagnosisRow.STATUS_DONE);
        row.setTriggerType("alert");
        row.setAlertName("日志模板异常待处理");
        row.setIncidentId(incidentId);
        row.setReportMd(REPORT);
        row.setCreatedAt(T0);
        row.setFinishedAt(T0.plusSeconds(45));
        return row;
    }

    private static OpsIncidentService.View incidentView(String severity) {
        return new OpsIncidentService.View(42L, "fp-42", "日志模板异常待处理", severity, "OPEN",
                T0, T0.plusSeconds(600), null, 1, null, null);
    }

    private static PostmortemRow draft(long id) {
        PostmortemRow row = new PostmortemRow();
        row.setId(id);
        row.setIncidentId(42L);
        row.setDiagnosisId(17L);
        row.setAlertName("日志模板异常待处理");
        row.setTitle("日志模板异常待处理 复盘");
        row.setSeverity("warning");
        row.setStatus(PostmortemRow.STATUS_DRAFT);
        row.setRootCause("反复重启");
        row.setTimelineMd("- 11-19 14:14 告警触发");
        row.setActionsMd("1. 改成停旧起新");
        row.setOrigin(PostmortemRow.ORIGIN_MANUAL);
        row.setCreatedAt(T0);
        row.setUpdatedAt(T0);
        row.setBodyMd("# 标题\n\n## 根因\n\n反复重启\n");
        return row;
    }
}

