package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 只读端点的委派与参数绑定（WORK-085，TASK-010/011）。 */
class PatternControllerTest {

    @Test
    @DisplayName("知识检索把 q 与 limit 原样交给查询服务（参数不拼串）")
    void knowledgeSearchBindsParam() {
        PatternQueryService queryService = mock(PatternQueryService.class);
        PatternQueryService.SearchResult result = new PatternQueryService.SearchResult(List.of(), List.of());
        when(queryService.search(anyString(), anyInt())).thenReturn(result);
        PatternController controller = new PatternController(queryService);

        String tricky = "连接超时 \"boom\" OR 1=1 --中文";
        var response = controller.search(tricky, 20);

        assertThat(response).isNotNull();
        verify(queryService).search(tricky, 20);
    }

    @Test
    @DisplayName("概览返回 Top 模板与近期异常（空数据不抛）")
    void overviewReturnsEmptyWhenNoData() {
        PatternQueryService queryService = mock(PatternQueryService.class);
        when(queryService.top(any(), any())).thenReturn(List.of());
        when(queryService.recentAnomalies(any(), anyInt(), anyInt())).thenReturn(List.of());
        PatternController controller = new PatternController(queryService);

        var response = controller.overview(null, 24);

        assertThat(response).isNotNull();
        verify(queryService).top(any(Instant.class), any());
        verify(queryService).recentAnomalies(any(Instant.class), anyInt(), anyInt());
    }
}
