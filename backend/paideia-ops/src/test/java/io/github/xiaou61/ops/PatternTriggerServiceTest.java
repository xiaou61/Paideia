package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 异常触发既有诊断：冷却抑制、忙时跳过不抛（WORK-085，REQ-006）。 */
class PatternTriggerServiceTest {

    private final Instant now = Instant.parse("2026-11-19T10:22:00Z");
    private PatternMapper mapper;
    private OpsDiagnosisService diagnosisService;
    private PatternTriggerService service;

    @BeforeEach
    void setUp() {
        OpsProperties properties = new OpsProperties();
        properties.getPatterns().setUnits(List.of("paideia"));
        properties.getDiagnosis().setUserId(7L);
        mapper = mock(PatternMapper.class);
        diagnosisService = mock(OpsDiagnosisService.class);
        service = new PatternTriggerService(properties, mapper, diagnosisService);
    }

    private PatternMapper.AnomalyRow row() {
        PatternMapper.AnomalyRow r = new PatternMapper.AnomalyRow();
        r.setId(11L);
        r.setKind("FREQ_SPIKE");
        r.setTemplateHash("h1");
        r.setUnit("paideia");
        r.setDetail("模板频次突增");
        return r;
    }

    @Test
    @DisplayName("冷却窗口内不再触发，标 TRIGGER_SKIPPED")
    void cooldownSuppressesSecondTrigger() {
        when(mapper.lastTriggeredAt("FREQ_SPIKE", "h1", "paideia")).thenReturn(now.minusSeconds(30));

        int triggered = service.triggerAll(List.of(row()), now);

        assertThat(triggered).isZero();
        verify(diagnosisService, never()).start(anyString(), any(), anyString(), anyString(), anyLong());
        verify(mapper).updateStatus(11L, "TRIGGER_SKIPPED", "模板频次突增（同模板同类型在冷却窗口内，已跳过诊断）");
    }

    @Test
    @DisplayName("诊断服务忙抛异常时跳过、不中断本轮")
    void busySkipsWithoutException() {
        when(mapper.lastTriggeredAt(anyString(), anyString(), anyString())).thenReturn(null);
        when(diagnosisService.start(anyString(), any(), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("TOO_MANY_REQUESTS"));

        assertThatCode(() -> assertThat(service.triggerAll(List.of(row()), now)).isZero())
                .doesNotThrowAnyException();
        verify(mapper).updateStatus(anyLong(), anyString(), anyString());
    }
}
