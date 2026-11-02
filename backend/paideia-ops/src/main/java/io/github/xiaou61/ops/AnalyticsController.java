package io.github.xiaou61.ops;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据大屏端点（WORK-045）。
 *
 * <p><b>只给管理员</b>（用户 2026-10-27 裁决）：它看的是平台整体数据，而学习者看到的应该是
 * 自己的学习进度。权限由 `/api/v1/ops/admin/** → hasRole("ADMIN")` 那条规则兜住，
 * 与运维摘要、诊断端点同门禁。
 *
 * <p>没有查询参数：窗口固定（14 天时序、7 天增量、当前总量）。加参数会让缓存的语义变复杂，
 * 而"看一屏"这个动作本来就没有筛选需求。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ops/admin/analytics")
class AnalyticsController {

    private final AnalyticsService service;

    AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    ApiResponse<AnalyticsView> overview() {
        return ApiResponse.ok(service.overview());
    }
}
