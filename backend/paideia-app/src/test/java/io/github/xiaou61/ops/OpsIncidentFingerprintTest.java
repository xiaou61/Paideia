package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 事件指纹的稳定性（WORK-043）。 */
class OpsIncidentFingerprintTest {

    @Test
    @DisplayName("稳定标签相同则指纹相同，额外标签不影响")
    void stableLabelsOnly() {
        Map<String, String> base = Map.of("instance", "127.0.0.1:9100", "job", "node",
                "severity", "warning");
        Map<String, String> withNoise = Map.of("instance", "127.0.0.1:9100", "job", "node",
                "severity", "warning", "summary", "每次都不一样", "startsAt", "2026-10-27T13:00:00Z");

        assertThat(OpsIncidentService.fingerprint("disk-low", "warning", base, null))
                .isEqualTo(OpsIncidentService.fingerprint("disk-low", "warning", withNoise, null));
    }

    @Test
    @DisplayName("Grafana 提供的 fingerprint 优先；非法值退回计算")
    void grafanaFingerprintWins() {
        Map<String, String> labels = Map.of("instance", "x", "job", "y");

        assertThat(OpsIncidentService.fingerprint("disk-low", "warning", labels, "AbC123"))
                .isEqualTo("abc123");
        assertThat(OpsIncidentService.fingerprint("disk-low", "warning", labels, "bad value!"))
                .isEqualTo(OpsIncidentService.fingerprint("disk-low", "warning", labels, null));
    }

    @Test
    @DisplayName("带偏移的 startsAt 能解析成同一时刻")
    void offsetTimeIsParsed() {
        assertThat(OpsIncidentService.parseTime("2026-10-27T13:00:00+08:00", Instant.EPOCH))
                .isEqualTo(Instant.parse("2026-10-27T05:00:00Z"));
    }
}
