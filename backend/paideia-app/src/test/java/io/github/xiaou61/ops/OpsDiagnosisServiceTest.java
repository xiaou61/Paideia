package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
    private OpsProperties properties;
    private AtomicReference<Runnable> pending;
    private TaskExecutor executor;
    private OpsDiagnosisService service;

    @BeforeEach
    void setUp() {
        mapper = mock(OpsDiagnosisMapper.class);
        evidence = mock(OpsEvidenceCollector.class);
        diagnoser = mock(OpsDiagnoser.class);
        properties = new OpsProperties();
        properties.getDiagnosis().setCooldownSeconds(600);
        pending = new AtomicReference<>();
        executor = pending::set;
        service = new OpsDiagnosisService(mapper, evidence, diagnoser, properties, executor);

        org.mockito.Mockito.doAnswer(invocation -> {
            OpsDiagnosisRow row = invocation.getArgument(0);
            row.setId(99L);
            return 1;
        }).when(mapper).insert(any());
        when(evidence.collect(anyString(), any(), any())).thenReturn(new OpsDiagnoser.Evidence(
                "manual", null, null, "summary", "metrics", "alerts", "logs", "deploys"));
        when(diagnoser.diagnose(anyLong(), any())).thenReturn(new OpsDiagnoser.Diagnosis(
                "report", new TokenUsage(1, 2, 3), "deepseek-flash"));
    }

    @Test
    @DisplayName("关闭开关后拒绝提交，不落行")
    void disabledRejects() {
        properties.getDiagnosis().setEnabled(false);

        assertThatThrownBy(() -> service.start("manual", null, null, USER))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("同一告警在冷却窗口内只建一行，第二次返回 -1")
    void cooldownSkipsSecondAlert() {
        assertThat(service.start("alert", "disk-low", null, USER)).isEqualTo(99L);
        pending.get().run();

        assertThat(service.start("alert", "disk-low", null, USER)).isEqualTo(-1L);
        verify(mapper, times(1)).insert(any());
    }

    @Test
    @DisplayName("手动触发不受冷却限制")
    void manualIsNotCooledDown() {
        assertThat(service.start("manual", null, null, USER)).isEqualTo(99L);
        pending.get().run();

        assertThat(service.start("manual", null, null, USER)).isEqualTo(99L);
        verify(mapper, times(2)).insert(any());
    }

    @Test
    @DisplayName("已有诊断在跑时，手动第二次提交返回 429")
    void concurrentManualIsRejected() {
        service.start("manual", null, null, USER);
        // 任务尚未执行（executor 只是收下 Runnable），单飞标志仍为 true
        assertThatThrownBy(() -> service.start("manual", null, null, USER))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

        pending.get().run();
        // 跑完后可以再次提交
        assertThat(service.start("manual", null, null, USER)).isEqualTo(99L);
    }

    @Test
    @DisplayName("启动时把陈旧的 PENDING/RUNNING 收成 FAILED")
    void cleanupStaleRows() {
        when(mapper.markStale(any(Instant.class), anyString(), any(Instant.class))).thenReturn(2);

        service.cleanupStale();

        verify(mapper).markStale(any(Instant.class), anyString(), any(Instant.class));
    }
}
