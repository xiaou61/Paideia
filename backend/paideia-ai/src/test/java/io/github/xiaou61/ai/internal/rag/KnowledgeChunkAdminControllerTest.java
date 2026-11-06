package io.github.xiaou61.ai.internal.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WORK-076 TASK-010：块编辑接口的列表行为（编辑/回滚的鉴权由集成测试覆盖）。 */
class KnowledgeChunkAdminControllerTest {

    private final KnowledgeChunkEditingService service = mock(KnowledgeChunkEditingService.class);
    private final KnowledgeChunkAdminController controller = new KnowledgeChunkAdminController(service);

    @Test
    @DisplayName("列表原样返回 service 的视图")
    void listsChunks() {
        when(service.list(1L)).thenReturn(List.of(
                new KnowledgeChunkEditingService.ChunkView(0, "内容", "auto", null, false, false)));

        var response = controller.list(1L);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).origin()).isEqualTo("auto");
    }
}
