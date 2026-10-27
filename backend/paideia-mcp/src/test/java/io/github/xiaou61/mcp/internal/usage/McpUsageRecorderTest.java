package io.github.xiaou61.mcp.internal.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 用量记录的四条纪律（WORK-044 TASK-002）。
 *
 * <p>每条都对应一个"错了不会报错、只会静默算错"的场景：写库失败炸掉调用、
 * 跨天把两天算成一天、没有行时返回 null 被当成 NPE。
 *
 * <p>用假 mapper 而不是真库：这些是纯判定；"删干净了没有"由集成测试盯。
 */
class McpUsageRecorderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    private final McpUsageMapper mapper = mock(McpUsageMapper.class);
    private final McpUsageRecorder recorder = new McpUsageRecorder(mapper);

    @Test
    void recordsErrorDeltaFromResult() {
        recorder.record(7L, TODAY, false);
        recorder.record(7L, TODAY, true);

        verify(mapper).increment(eq(7L), eq(TODAY), eq(0), any());
        verify(mapper).increment(eq(7L), eq(TODAY), eq(1), any());
    }

    @Test
    void callsTodayIsZeroWhenNoRow() {
        when(mapper.callsOn(7L, TODAY)).thenReturn(null);

        assertThat(recorder.callsToday(7L, TODAY)).isZero();
    }

    /** 计数是旁路，不能把一次成功的调用变成失败。 */
    @Test
    void recordSwallowsMapperFailure() {
        when(mapper.increment(anyLong(), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("数据库连接断了"));

        recorder.record(7L, TODAY, false);
    }

    @Test
    void totalsDefaultsToZero() {
        when(mapper.totals(7L)).thenReturn(null);

        McpUsageRecorder.Totals totals = recorder.totals(7L);

        assertThat(totals.calls()).isZero();
        assertThat(totals.errors()).isZero();
    }

    /** 跨天必须是两行：单行累计值要靠 updated_at 判断跨天，跨天那一刻有竞态。 */
    @Test
    void countsSplitByDay() {
        LocalDate tomorrow = TODAY.plusDays(1);

        recorder.record(7L, TODAY, false);
        recorder.record(7L, tomorrow, false);

        verify(mapper).increment(eq(7L), eq(TODAY), eq(0), any());
        verify(mapper).increment(eq(7L), eq(tomorrow), eq(0), any());
    }
}
