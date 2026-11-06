package io.github.xiaou61.ai.internal.web;

import io.github.xiaou61.ai.internal.importing.KnowledgeImport;
import io.github.xiaou61.ai.internal.importing.KnowledgeImportService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档导入的管理端接口（WORK-076 TASK-007）。
 *
 * <p>鉴权靠路径规则：整个 {@code /api/v1/ai/admin/**} 由 {@code SecurityConfiguration} 的
 * {@code hasRole('ADMIN')} 兜住（须排在更宽的 {@code /api/v1/ai/**} 之前），所以本类不逐方法加注解。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ai/admin/knowledge-imports")
class KnowledgeImportController {

    private final KnowledgeImportService service;

    KnowledgeImportController(KnowledgeImportService service) {
        this.service = service;
    }

    /** 单个导入项：附件 id + 文件夹内的相对路径（保留目录树，可空）。 */
    record ImportItem(String fileId, String relativePath) {
    }

    /** 批量登记请求。{@code categoryId} 是单文件导入的目标分类；relativePath 非空时以路径为准。 */
    record CreateImportRequest(Long categoryId, List<ImportItem> items) {
    }

    @PostMapping
    ApiResponse<Map<String, Object>> create(@RequestBody CreateImportRequest body) {
        long operator = AiRequester.require();
        List<Long> importIds = new ArrayList<>();
        if (body.items() != null) {
            for (ImportItem item : body.items()) {
                importIds.add(service.enqueue(operator, body.categoryId(), item.fileId(), item.relativePath()));
            }
        }
        return ApiResponse.ok(Map.of("importIds", importIds));
    }

    @GetMapping
    ApiResponse<List<KnowledgeImport>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok(service.listRecent(status, limit));
    }

    @PostMapping("/{id}/retry")
    ApiResponse<Void> retry(@PathVariable("id") long id) {
        AiRequester.require();
        service.retry(id);
        return ApiResponse.ok(null);
    }
}
