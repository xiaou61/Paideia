package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 诊断证据里的历史与同期段（WORK-049 REQ-004/REQ-005）。 */
class OpsIncidentContextTest {

    private static final Instant NOW = Instant.parse("2026-10-28T04:00:00Z");

    private OpsIncidentMapper mapper;
    private OpsIncidentContext context;

    @BeforeEach
    void setUp() {
        mapper = mock(OpsIncidentMapper.class);
        context = new OpsIncidentContext(mapper);
        when(mapper.listOpenSince(any(), anyLong(), anyInt())).thenReturn(List.of());
        when(mapper.listHistoryByAlertName(anyString(), anyLong(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("历史同类带日期、持续时长、诊断号、评分与结论首行")
    void historyFormatted() {
        OpsIncident current = incident(9L, "fp-1", "HTTP 5xx 比例超过 5%");
        when(mapper.findById(9L)).thenReturn(Optional.of(current));
        when(mapper.listHistoryByFingerprint(eq("fp-1"), eq(9L), any(), eq(3))).thenReturn(List.of(
                new OpsIncidentMapper.HistoryRow(7L, "HTTP 5xx 比例超过 5%",
                        Instant.parse("2026-10-20T06:02:00Z"),
                        Instant.parse("2026-10-20T06:37:00Z"),
                        "RESOLVED", 7L, "## 结论\n发版后 5xx 突增，根因是 NPE", -1)));

        String text = context.build(9L, current.getAlertName(), NOW);

        assertThat(text)
                .contains("## 历史同类事件")
                .contains("2026-10-20 14:02")
                .contains("跨度 35 分钟")
                .contains("已恢复")
                .contains("诊断#7")
                .contains("评分=不准")
                .contains("结论：发版后 5xx 突增，根因是 NPE")
                .contains("## 同期其他事件")
                .contains("无同期事件");
    }

    @Test
    @DisplayName("没有历史时写显式文案，不返回空段")
    void emptyHistoryIsExplicit() {
        when(mapper.findById(9L)).thenReturn(Optional.of(incident(9L, "fp-1", "磁盘告警")));
        when(mapper.listHistoryByFingerprint(anyString(), anyLong(), any(), anyInt()))
                .thenReturn(List.of());

        String text = context.build(9L, "磁盘告警", NOW);

        assertThat(text).contains("无历史同类事件", "无同期事件");
    }

    @Test
    @DisplayName("同期事件只收其他 OPEN 事件，排除当前事件")
    void concurrentEventsExcludeSelf() {
        when(mapper.findById(9L)).thenReturn(Optional.of(incident(9L, "fp-1", "CPU 高")));
        when(mapper.listHistoryByFingerprint(anyString(), anyLong(), any(), anyInt()))
                .thenReturn(List.of());
        when(mapper.listOpenSince(any(), eq(9L), eq(5))).thenReturn(List.of(
                incident(11L, "fp-2", "HTTP 5xx 比例超过 5%")));

        String text = context.build(9L, "CPU 高", NOW);

        assertThat(text).contains("同期其他事件", "[OPEN] HTTP 5xx 比例超过 5%");
    }

    @Test
    @DisplayName("同指纹不足时按告警名补齐，重复 id 不重复列")
    void historyFallsBackToAlertName() {
        OpsIncident current = incident(9L, "fp-1", "磁盘告警");
        when(mapper.findById(9L)).thenReturn(Optional.of(current));
        when(mapper.listHistoryByFingerprint(eq("fp-1"), eq(9L), any(), eq(3))).thenReturn(List.of(
                new OpsIncidentMapper.HistoryRow(7L, "磁盘告警", NOW.minusSeconds(3600), NOW,
                        "RESOLVED", null, null, null)));
        when(mapper.listHistoryByAlertName(eq("磁盘告警"), eq(9L), any(), eq(2))).thenReturn(List.of(
                new OpsIncidentMapper.HistoryRow(7L, "磁盘告警", NOW.minusSeconds(3600), NOW,
                        "RESOLVED", null, null, null),
                new OpsIncidentMapper.HistoryRow(8L, "磁盘告警", NOW.minusSeconds(7200), NOW,
                        "RESOLVED", null, null, null)));

        String text = context.build(9L, "磁盘告警", NOW);

        assertThat(text).contains("未诊断").contains("无结论");
        assertThat(text.lines().filter(line -> line.startsWith("- ")).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("历史段先截断，总量不超过 2000 字符")
    void contextTruncatedWithinBudget() {
        when(mapper.findById(9L)).thenReturn(Optional.of(incident(9L, "fp-1", "CPU 高")));
        String longConclusion = "很长的结论".repeat(40);
        when(mapper.listHistoryByFingerprint(anyString(), anyLong(), any(), anyInt())).thenReturn(List.of(
                row(1L, longConclusion), row(2L, longConclusion)));
        when(mapper.listHistoryByAlertName(anyString(), anyLong(), any(), anyInt())).thenReturn(List.of(
                row(3L, longConclusion), row(4L, longConclusion)));
        when(mapper.listOpenSince(any(), anyLong(), anyInt())).thenReturn(List.of(
                incident(11L, "fp-2", "磁盘告警"), incident(12L, "fp-3", "备份陈旧")));

        String text = context.build(9L, "CPU 高", NOW);

        assertThat(text.length()).isLessThanOrEqualTo(OpsIncidentContext.MAX_CHARS);
        assertThat(text).contains("已截断");
    }

    private static OpsIncident incident(long id, String fingerprint, String alertName) {
        OpsIncident row = new OpsIncident();
        row.setId(id);
        row.setFingerprint(fingerprint);
        row.setAlertName(alertName);
        row.setStatus(OpsIncident.STATUS_OPEN);
        row.setFirstSeen(NOW.minusSeconds(600));
        row.setLastSeen(NOW);
        row.setAlertCount(1);
        return row;
    }

    private static OpsIncidentMapper.HistoryRow row(long id, String report) {
        return new OpsIncidentMapper.HistoryRow(id, "CPU 高", NOW.minusSeconds(3600), NOW,
                "RESOLVED", id, report, 1);
    }
}
