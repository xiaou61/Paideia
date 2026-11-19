package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 证据窗口（WORK-086 TASK-001）：事件优先、默认跨度、上限夹取、保留期标注。 */
class OpsEvidenceWindowTest {

    /** 钉住"现在"= 2026-11-19 15:00 CST，测试里所有相对时间都以它为准。 */
    private final Instant now = Instant.parse("2026-11-19T07:00:00Z");

    @Test
    @DisplayName("有事件时用 first_seen/last_seen 锚定，而不是触发时刻")
    void prefers_incident_first_seen() {
        // 事件 14:17:40–14:18:15（CST），诊断到 14:31 才被触发
        OpsEvidenceWindow window = OpsEvidenceWindow.of(
                Instant.parse("2026-11-19T06:17:40Z"),
                Instant.parse("2026-11-19T06:18:15Z"),
                Instant.parse("2026-11-19T06:31:00Z"),
                30,
                60,
                now);

        assertThat(window.start()).isEqualTo(Instant.parse("2026-11-19T06:07:40Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-11-19T06:23:15Z"));
        assertThat(window.withSource("事件 #17").label())
                .isEqualTo("证据窗口 14:07–14:23（事件 #17）");
        assertThat(window.outOfRetention()).isFalse();
    }

    @Test
    @DisplayName("没有事件时从触发时刻向前取默认跨度")
    void without_incident_uses_default_minutes() {
        OpsEvidenceWindow window = OpsEvidenceWindow.of(
                null, null, Instant.parse("2026-11-19T06:31:00Z"), 30, 60, now);

        assertThat(window.minutes()).isEqualTo(30);
        assertThat(window.end()).isEqualTo(Instant.parse("2026-11-19T06:31:00Z"));
        assertThat(window.start()).isEqualTo(Instant.parse("2026-11-19T06:01:00Z"));
        assertThat(window.label()).isEqualTo("证据窗口 14:01–14:31");
    }

    @Test
    @DisplayName("事件跨度再长也不超过上限，且尾巴只多给 5 分钟")
    void clamps_to_max_minutes() {
        OpsEvidenceWindow window = OpsEvidenceWindow.of(
                Instant.parse("2026-11-19T01:00:00Z"),
                Instant.parse("2026-11-19T04:00:00Z"),
                Instant.parse("2026-11-19T06:31:00Z"),
                30,
                60,
                now);

        assertThat(window.end()).isEqualTo(Instant.parse("2026-11-19T04:05:00Z"));
        assertThat(Duration.between(window.start(), window.end())).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("首末时间只差几秒时至少留 10 分钟观察面")
    void keeps_minimum_span() {
        Instant single = Instant.parse("2026-11-19T06:18:00Z");

        OpsEvidenceWindow window = OpsEvidenceWindow.of(single, single, single, 30, 60, now);

        assertThat(Duration.between(window.start(), window.end()))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("窗口早于 Loki 保留期时必须标注，不能装作有日志")
    void out_of_retention_is_marked() {
        OpsEvidenceWindow window = OpsEvidenceWindow.of(
                Instant.parse("2026-08-01T06:00:00Z"),
                Instant.parse("2026-08-01T06:05:00Z"),
                Instant.parse("2026-08-01T06:06:00Z"),
                30,
                60,
                now);

        assertThat(window.outOfRetention()).isTrue();
        assertThat(window.withSource("事件 #3").label())
                .contains("超出 Loki 保留期");
    }

    @Test
    @DisplayName("起点晚于终点是编程错误，构造时就该炸")
    void rejects_backwards_window() {
        assertThatThrownBy(() -> new OpsEvidenceWindow(
                        Instant.parse("2026-11-19T06:10:00Z"),
                        Instant.parse("2026-11-19T06:00:00Z"),
                        "",
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("起点不能晚于终点");
    }
}
