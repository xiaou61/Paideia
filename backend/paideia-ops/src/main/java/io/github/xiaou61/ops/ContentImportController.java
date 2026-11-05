package io.github.xiaou61.ops;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容导入运行的记录与查询（WORK-074 C3/C4）。
 *
 * <p>{@code /api/v1/ops/admin/**} 由 SecurityConfiguration 的 {@code hasRole("ADMIN")} 兜住；
 * 上报由导入脚本（管理员凭据）发起，查询给管理端/漂移检查脚本读"最近一次导入的哈希与时间"。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ops/admin/content-import")
class ContentImportController {

    private final ContentImportRunRegistry registry;

    ContentImportController(ContentImportRunRegistry registry) {
        this.registry = registry;
    }

    /** 导入脚本上报的字段（宽松：缺省按 0 / 空）。 */
    record Report(String source, String sourceHash, Integer created, Integer updated, Integer relations) {
    }

    @PostMapping
    ApiResponse<Map<String, Integer>> record(@RequestBody Report report) {
        registry.record(
                report.source() == null ? "" : report.source(),
                report.sourceHash(),
                nz(report.created()), nz(report.updated()), nz(report.relations()));
        return ApiResponse.ok(Map.of("recent", registry.size()));
    }

    @GetMapping
    ApiResponse<List<ContentImportRun>> recent() {
        return ApiResponse.ok(registry.recent());
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
