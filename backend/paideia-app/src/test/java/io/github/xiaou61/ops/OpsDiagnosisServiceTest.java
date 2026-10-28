package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.OpsDiagnoser;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

/**
 * 诊断编排的本地语义（WORK-042 AC-011/AC-013/评审焦点"重启僵尸"）。
 *
 * <p>数据库与模型都打桩；这里只测开关、冷却、单飞与启动清理，跑得足够快，
 * 不需要测试库。
 */
class OpsDiagnosisServiceTest {

    private static final long USER = 7L;

    private OpsDiagnosisMapper mapper;
    private OpsEvidenceCollector evidence;
    private OpsDiagnoser diagnoser;
    private OpsIncidentService incidents;
    private OpsIncidentContext contexts;
    private OpsProperties properties;
    private AtomicReference<Runnable> pending;
    private TaskExecutor executor;
    private OpsDiagnosisService service;

    @BeforeEach
    void setUp() {
        mapper = mock(OpsDiagnosisMapper.class);
        evidence = mock(OpsEvidenceCollector.class);
        diagnoser = mock(OpsDiagnoser.class);
        incidents = mock(OpsIncidentService.class);
        contexts = mock(OpsIncidentContext.class);
        properties = new OpsProperties();
        properties.getDiagnosis().setCooldownSeconds(600);
        pending = new AtomicReference<>();
        executor = pending::set;
        service = new OpsDiagnosisService(mapper, evidence, diagnoser, incidents, contexts,
                properties, executor);
        when(contexts.build(any(), any(), any())).thenReturn("无历史同类事件\n无同期事件\n");

        org.mockito.Mockito.doAnswer(invocation -> {
            OpsDiagnosisRow row = invocation.getArgument(0);
            row.setId(99L);
            return 1;
        }).when(mapper).insert(any());
        when(evidence.collect(anyString(), any(), any(), any())).thenReturn(new OpsDiagnoser.Evidence(
                "manual", null, null, "summary", "metrics", "baselines", "ctx",
                "alerts", "logs", null, "deploys"));
        when(diagnoser.diagnose(anyLong(), any())).thenReturn(new OpsDiagnoser.Diagnosis(
                "report", new TokenUsage(1, 2, 3), "deepseek-flash"));
    }

    @Test
    @DisplayName("关闭开关后拒绝提交，不落行")
    void disabledRejects() {
        properties.getDiagnosis().setEnabled(false);

        assertThatThrownBy(() -> service.start("manual", null, null, null, USER))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("同一告警在冷却窗口内只建一行，第二次返回 -1")
    void cooldownSkipsSecondAlert() {
        assertThat(service.start("alert", null, "disk-low", null, USER)).isEqualTo(99L);
        pending.get().run();

        assertThat(service.start("alert", null, "disk-low", null, USER)).isEqualTo(-1L);
        verify(mapper, times(1)).insert(any());
    }

    @Test
    @DisplayName("手动触发不受冷却限制")
    void manualIsNotCooledDown() {
        assertThat(service.start("manual", null, null, null, USER)).isEqualTo(99L);
        pending.get().run();

        assertThat(service.start("manual", null, null, null, USER)).isEqualTo(99L);
        verify(mapper, times(2)).insert(any());
    }

    @Test
    @DisplayName("已有诊断在跑时，手动第二次提交返回 429")
    void concurrentManualIsRejected() {
        service.start("manual", null, null, null, USER);
        // 任务尚未执行（executor 只是收下 Runnable），单飞标志仍为 true
        assertThatThrownBy(() -> service.start("manual", null, null, null, USER))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

        pending.get().run();
        // 跑完后可以再次提交
        assertThat(service.start("manual", null, null, null, USER)).isEqualTo(99L);
    }

    @Test
    @DisplayName("事件级冷却：canDiagnose=false 时不建行")
    void incidentCooldownSkips() {
        when(incidents.canDiagnose(eq(5L), any(), anyLong())).thenReturn(false);

        assertThat(service.start("alert", 5L, "disk-low", null, USER)).isEqualTo(-1L);

        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("事件诊断：写回冷却时间并追加时间线")
    void incidentDiagnosisMarksTimeline() {
        when(incidents.canDiagnose(eq(5L), any(), anyLong())).thenReturn(true);

        assertThat(service.start("alert", 5L, "disk-low", null, USER)).isEqualTo(99L);

        verify(incidents).markDiagnosed(eq(5L), any());
        verify(incidents).markStarted(5L, 99L);
    }

    @Test
    @DisplayName("启动时把陈旧的 PENDING/RUNNING 收成 FAILED")
    void cleanupStaleRows() {
        when(mapper.markStale(any(Instant.class), anyString(), any(Instant.class))).thenReturn(2);

        service.cleanupStale();

        verify(mapper).markStale(any(Instant.class), anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("同实例窗口内有 DONE 诊断时复用，不新开")
    void reuseWithinWindowSkips() {
        when(incidents.canDiagnose(eq(5L), any(), anyLong())).thenReturn(true);
        when(incidents.recentDoneDiagnosisForInstance(eq(5L), any(), eq(600L)))
                .thenReturn(java.util.Optional.of(42L));

        assertThat(service.start("alert", 5L, "http-5xx", null, USER)).isEqualTo(-1L);

        verify(mapper, never()).insert(any());
        verify(incidents).appendEvent(eq(5L), eq(OpsIncidentEvent.KIND_NOTE),
                org.mockito.ArgumentMatchers.contains("复用诊断 #42"), isNull());
    }

    @Test
    @DisplayName("关联窗口配 0 时不做复用检查")
    void reuseDisabledWhenZero() {
        properties.getDiagnosis().setRelatedWindowSeconds(0);
        when(incidents.canDiagnose(eq(5L), any(), anyLong())).thenReturn(true);

        assertThat(service.start("alert", 5L, "http-5xx", null, USER)).isEqualTo(99L);

        verify(incidents, never()).recentDoneDiagnosisForInstance(anyLong(), any(), anyLong());
        verify(mapper).insert(any());
    }

    @Test
    @DisplayName("重启后对无 DONE 的 OPEN 事件补一次诊断")
    void catchUpPicksOpenWithoutDone() {
        properties.getDiagnosis().setUserId(1);
        OpsIncident incident = new OpsIncident();
        incident.setId(5L);
        incident.setAlertName("disk-low");
        when(incidents.catchUpCandidate(any(), any())).thenReturn(java.util.Optional.of(incident));
        when(incidents.canDiagnose(eq(5L), any(), anyLong())).thenReturn(true);

        service.cleanupStale();

        verify(incidents).appendEvent(eq(5L), eq(OpsIncidentEvent.KIND_NOTE), eq("重启补诊断"), isNull());
        verify(mapper).insert(any());
    }

    @Test
    @DisplayName("诊断账号未配置时不补诊断")
    void catchUpSkipsWhenUserMissing() {
        service.cleanupStale();

        verify(incidents, never()).catchUpCandidate(any(), any());
        verify(mapper, never()).insert(any());
    }
}
