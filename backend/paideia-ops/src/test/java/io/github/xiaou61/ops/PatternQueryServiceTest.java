package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 证据摘要拼装：拓扑行、截断、关闭降级（WORK-085，REQ-005/REQ-008）。 */
class PatternQueryServiceTest {

    private final Instant now = Instant.parse("2026-11-19T10:22:00Z");

    private OpsProperties props() {
        OpsProperties properties = new OpsProperties();
        properties.getPatterns().setUnits(List.of("paideia"));
        return properties;
    }

    private static PatternMapper.TopTemplate top(String pattern, long count) {
        return new PatternMapper.TopTemplate("h-" + pattern, "paideia", pattern, count);
    }

    @Test
    @DisplayName("摘要含最小拓扑一行（REQ-008）")
    void summaryContainsTopologyLine() {
        PatternMapper mapper = mock(PatternMapper.class);
        when(mapper.topTemplates(anyString(), any(), any(), anyInt())).thenReturn(List.of(top("boom", 3)));
        PatternQueryService service = new PatternQueryService(props(), mapper);

        String text = service.evidenceText(now);

        assertThat(text).contains("依赖拓扑：");
        assertThat(text).contains("app → MySQL/Redis/RustFS/Laya/Tempo");
    }

    @Test
    @DisplayName("超过 maxPatternChars 截断并标记")
    void summaryTruncatedToMaxChars() {
        OpsProperties properties = props();
        properties.getPatterns().setMaxPatternChars(20);
        PatternMapper mapper = mock(PatternMapper.class);
        when(mapper.topTemplates(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(top("pattern-with-a-very-long-name", 99)));
        PatternQueryService service = new PatternQueryService(properties, mapper);

        String text = service.evidenceText(now);

        assertThat(text).contains("已截断");
        assertThat(text.length()).isLessThan(400);
    }

    @Test
    @DisplayName("关闭时返回空串（prompt 会写「不可达或无数据」）")
    void summaryEmptyWhenDisabled() {
        OpsProperties properties = props();
        properties.getPatterns().setEnabled(false);
        PatternQueryService service = new PatternQueryService(properties, mock(PatternMapper.class));

        assertThat(service.evidenceText(now)).isEmpty();
    }
}
