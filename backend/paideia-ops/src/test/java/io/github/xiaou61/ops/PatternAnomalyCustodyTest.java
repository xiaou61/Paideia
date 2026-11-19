package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 异常账本收尾（2026-11-19）：诊断完成即关闭，超时无人处理则归档。 */
class PatternAnomalyCustodyTest {

    private final Instant now = Instant.parse("2026-11-19T08:00:00Z");

    @Test
    @DisplayName("诊断已完成 + 超时未处理，都会被关闭；超时阈值按配置换算")
    void closesDiagnosedAndStale() {
        OpsProperties properties = new OpsProperties();
        properties.getPatterns().setAutoResolveMinutes(120);
        PatternMapper mapper = mock(PatternMapper.class);
        when(mapper.resolveDiagnosed(any())).thenReturn(2);
        when(mapper.closeStale(any(), any())).thenReturn(1);

        int closed = new PatternAnomalyCustody(properties, mapper).close(now);

        assertThat(closed).isEqualTo(3);
        verify(mapper).resolveDiagnosed(now);
        verify(mapper).closeStale(eq(now.minusSeconds(120 * 60L)), eq(now));
    }

    @Test
    @DisplayName("单步失败不影响另一步，也不抛出")
    void failureInOneStepDoesNotStopTheOther() {
        OpsProperties properties = new OpsProperties();
        PatternMapper mapper = mock(PatternMapper.class);
        when(mapper.resolveDiagnosed(any())).thenThrow(new IllegalStateException("db down"));
        when(mapper.closeStale(any(), any())).thenReturn(1);

        assertThat(new PatternAnomalyCustody(properties, mapper).close(now)).isEqualTo(1);
    }
}
