package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 三类异常的判定（WORK-085）：频次突增 / 新模板 / 消失，以及冷启动不误报。 */
class PatternAnomalyDetectorTest {

    private PatternMapper mapper;
    private PatternAnomalyDetector detector;
    private OpsProperties properties;
    private final Instant now = Instant.parse("2026-11-19T10:22:00Z");

    @BeforeEach
    void setUp() {
        properties = new OpsProperties();
        properties.getPatterns().setUnits(List.of("paideia"));
        mapper = mock(PatternMapper.class);
        detector = new PatternAnomalyDetector(properties, mapper);
        when(mapper.insertAnomaly(any())).thenAnswer(invocation -> {
            PatternMapper.AnomalyRow row = invocation.getArgument(0);
            row.setId(1L);
            return 1;
        });
        when(mapper.countRecent(anyString(), anyString(), anyString(), any())).thenReturn(0L);
    }

    private PatternMapper.Template template(String hash, Instant firstSeen) {
        return new PatternMapper.Template(hash, "paideia", "boom at {{id}}", firstSeen, now, 0, 0);
    }

    @Test
    @DisplayName("频次远超基线且 z 显著 → FREQ_SPIKE")
    void detectsFrequencySpike() {
        when(mapper.recentTemplates(anyString(), any(), anyInt()))
                .thenReturn(List.of(template("h1", now.minusSeconds(7 * 86400))));
        when(mapper.countBucketsBefore(anyString(), any())).thenReturn(100L);
        when(mapper.baselineCounts(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(List.of(10, 12, 9, 11));
        // 第一次是当前窗口，第二次是上一窗口。
        when(mapper.sumInWindow(eq("h1"), eq("paideia"), any(), any())).thenReturn(60L, 10L);

        List<PatternMapper.AnomalyRow> rows = detector.detect(now);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getKind()).isEqualTo("FREQ_SPIKE");
        assertThat(rows.get(0).getCurrentCount()).isEqualTo(60);
    }

    @Test
    @DisplayName("新窗口里首次出现且条数够 → NEW_TEMPLATE")
    void detectsNewTemplate() {
        when(mapper.recentTemplates(anyString(), any(), anyInt()))
                .thenReturn(List.of(template("h2", now)));
        when(mapper.countBucketsBefore(anyString(), any())).thenReturn(50L);
        when(mapper.baselineCounts(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(List.of());
        when(mapper.sumInWindow(eq("h2"), eq("paideia"), any(), any())).thenReturn(50L, 0L);

        List<PatternMapper.AnomalyRow> rows = detector.detect(now);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getKind()).isEqualTo("NEW_TEMPLATE");
    }

    @Test
    @DisplayName("基线够高但连续两窗口为 0 → DISAPPEARED")
    void detectsDisappeared() {
        when(mapper.recentTemplates(anyString(), any(), anyInt()))
                .thenReturn(List.of(template("h3", now.minusSeconds(7 * 86400))));
        when(mapper.countBucketsBefore(anyString(), any())).thenReturn(100L);
        when(mapper.baselineCounts(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(List.of(30, 25, 28, 27));
        when(mapper.sumInWindow(eq("h3"), eq("paideia"), any(), any())).thenReturn(0L, 0L);

        List<PatternMapper.AnomalyRow> rows = detector.detect(now);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getKind()).isEqualTo("DISAPPEARED");
    }

    @Test
    @DisplayName("频次比值达标但 z 小（平时波动大）不误报")
    void lowZNotAnomaly() {
        when(mapper.recentTemplates(anyString(), any(), anyInt()))
                .thenReturn(List.of(template("h5", now.minusSeconds(7 * 86400))));
        when(mapper.countBucketsBefore(anyString(), any())).thenReturn(100L);
        // 基线波动大：均值 50、标准差约 41.8；当前 150 → ratio=3.0 达标，但 z≈2.39 < 3。
        when(mapper.baselineCounts(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(List.of(10, 100, 20, 90, 30));
        when(mapper.sumInWindow(eq("h5"), eq("paideia"), any(), any())).thenReturn(150L, 0L);

        assertThat(detector.detect(now)).isEmpty();
    }
}
