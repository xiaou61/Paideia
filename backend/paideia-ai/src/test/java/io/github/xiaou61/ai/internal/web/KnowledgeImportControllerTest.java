package io.github.xiaou61.ai.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.internal.importing.KnowledgeImport;
import io.github.xiaou61.ai.internal.importing.KnowledgeImportService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WORK-076 TASK-007：导入接口的行为（列表；创建/重试的鉴权由集成 AuthorizationIsolationTest 覆盖）。 */
class KnowledgeImportControllerTest {

    private final KnowledgeImportService service = mock(KnowledgeImportService.class);
    private final KnowledgeImportController controller = new KnowledgeImportController(service);

    @Test
    @DisplayName("列表按状态透传给 service，并原样返回")
    void listsByStatus() {
        KnowledgeImport job = new KnowledgeImport();
        job.setId(1L);
        job.setStatus("pending");
        when(service.listRecent("pending", 10)).thenReturn(List.of(job));

        var response = controller.list("pending", 10);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).getId()).isEqualTo(1L);
    }
}
