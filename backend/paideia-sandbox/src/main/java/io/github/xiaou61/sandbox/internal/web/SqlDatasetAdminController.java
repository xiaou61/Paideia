package io.github.xiaou61.sandbox.internal.web;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetMapper;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据集的管理端（WORK-035 T-11）。
 *
 * <p>权限由 `@ApiAccess(ADMIN)` 在类上统一给——不逐方法加，避免"新加一个方法忘了加注解"。
 *
 * <p>**只有两件事**：看社区数据集与举报、下架/恢复。不做"编辑别人的数据集"——
 * 内容属于作者，管理员能做的只是让它不可见（与 `note_reports` 的治理口径一致）。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/sandbox/admin/sql-datasets")
class SqlDatasetAdminController {

    private final SqlDatasetMapper mapper;
    private final io.github.xiaou61.sandbox.DatasetOwnerNames owners;

    SqlDatasetAdminController(SqlDatasetMapper mapper,
                              io.github.xiaou61.sandbox.DatasetOwnerNames owners) {
        this.mapper = mapper;
        this.owners = owners;
    }

    /**
     * 社区数据集清单。
     *
     * @param status 可选：draft / published / taken_down；不传就是全部
     */
    @GetMapping
    ApiResponse<List<CommunityView>> list(@RequestParam(required = false) String status) {
        List<SqlDatasetMapper.Summary> rows = mapper.listCommunity(status, 200);
        java.util.Set<Long> ownerIds = new java.util.LinkedHashSet<>();
        for (SqlDatasetMapper.Summary row : rows) {
            if (row.ownerUserId() != null) {
                ownerIds.add(row.ownerUserId());
            }
        }
        java.util.Map<Long, String> names = ownerIds.isEmpty()
                ? java.util.Map.of()
                : owners.usernames(ownerIds);
        List<CommunityView> views = rows.stream()
                .map(row -> new CommunityView(row.datasetKey(), row.name(), row.ownerUserId(),
                        io.github.xiaou61.sandbox.internal.dataset.SqlDatasetCatalogService.ownerName(
                                row.ownerUserId(), names),
                        row.tableCount(), row.rowCount(), mapper.countReports(row.datasetKey())))
                .toList();
        return ApiResponse.ok(views);
    }

    /**
     * 下架。
     *
     * <p>同时把未处理的举报标成 `taken_down`——否则它们会一直挂在待处理列表里，
     * 管理员看到的是"已下架但还有三条待处理"。
     */
    @PostMapping("/{key}/take-down")
    ApiResponse<Void> takeDown(@PathVariable String key) {
        requireCommunity(key);
        mapper.updateStatus(key, "taken_down", java.time.Instant.now());
        mapper.markReportsHandled(key, "taken_down", java.time.Instant.now());
        return ApiResponse.ok(null);
    }

    /** 恢复上架。举报记录保持已处理状态——它们已经处理过了。 */
    @PostMapping("/{key}/restore")
    ApiResponse<Void> restore(@PathVariable String key) {
        requireCommunity(key);
        mapper.updateStatus(key, "published", java.time.Instant.now());
        return ApiResponse.ok(null);
    }

    /** 举报清单。 */
    @GetMapping("/reports")
    ApiResponse<List<SqlDatasetMapper.ReportRow>> reports(@RequestParam(required = false) String status) {
        return ApiResponse.ok(mapper.listReports(status, 200));
    }

    private void requireCommunity(String key) {
        SqlDatasetMapper.Row row = mapper.findByKey(key)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "没有这个数据集"));
        if (!"community".equals(row.source())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "只有社区数据集可以被下架");
        }
    }

    record CommunityView(String datasetKey, String name, Long ownerUserId, String ownerName,
                         int tableCount, int rowCount, int reportCount) {
    }
}
