package io.github.xiaou61.ai.internal.rag;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.security.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索切片的管理端接口（WORK-076 TASK-010）。
 *
 * <p>放在 {@code internal.rag} 而不是 {@code internal.web}：它直接用同包的
 * {@link KnowledgeChunkEditingService}（包内可见），避免为了一个控制器把服务改成 public。
 * 鉴权仍由 {@code /api/v1/ai/admin/**} 的 ADMIN 路径规则兜住。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ai/admin/knowledge-entries/{entryId}/chunks")
class KnowledgeChunkAdminController {

    private final KnowledgeChunkEditingService service;

    KnowledgeChunkAdminController(KnowledgeChunkEditingService service) {
        this.service = service;
    }

    record EditBody(String content) {
    }

    record RollbackBody(int version) {
    }

    @GetMapping
    ApiResponse<List<KnowledgeChunkEditingService.ChunkView>> list(@PathVariable("entryId") long entryId) {
        return ApiResponse.ok(service.list(entryId));
    }

    @PutMapping("/{chunkIndex}")
    ApiResponse<Map<String, Object>> edit(@PathVariable("entryId") long entryId,
                                          @PathVariable("chunkIndex") int chunkIndex,
                                          @RequestBody EditBody body) {
        KnowledgeChunkEditingService.EditResult result =
                service.edit(entryId, chunkIndex, body.content(), CurrentUser.userId());
        return ApiResponse.ok(Map.of("version", result.version(), "embeddingPending", result.embeddingPending()));
    }

    @GetMapping("/{chunkIndex}/revisions")
    ApiResponse<List<KnowledgeChunkEditingService.Revision>> revisions(@PathVariable("entryId") long entryId,
                                                                       @PathVariable("chunkIndex") int chunkIndex) {
        return ApiResponse.ok(service.revisions(entryId, chunkIndex));
    }

    @PostMapping("/{chunkIndex}/rollback")
    ApiResponse<Map<String, Object>> rollback(@PathVariable("entryId") long entryId,
                                              @PathVariable("chunkIndex") int chunkIndex,
                                              @RequestBody RollbackBody body) {
        int version = service.rollback(entryId, chunkIndex, body.version(), CurrentUser.userId());
        return ApiResponse.ok(Map.of("version", version));
    }
}
