package io.github.xiaou61.ops;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端「日志模板 / 模板异常 / 历史检索」只读端点（WORK-085）。
 *
 * <p>只读，不提供手动同步/手动触发——同步是周期任务的事，手动补跑用应用日志排查即可，
 * 多一个写入口只会让人怀疑「面板上的数是不是手点出来的」。权限沿用
 * {@code /api/v1/ops/admin/** → hasRole("ADMIN")}，本类不逐方法加注解。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ops/admin")
class PatternController {

    private final PatternQueryService queryService;

    PatternController(PatternQueryService queryService) {
        this.queryService = queryService;
    }

    /** 当前窗口 Top 模板 + 近期异常。 */
    @GetMapping("/patterns")
    ApiResponse<Overview> overview(@RequestParam(value = "unit", required = false) String unit,
                                  @RequestParam(value = "hours", required = false, defaultValue = "24") int hours) {
        Instant now = Instant.now();
        List<PatternQueryService.PatternView> top = queryService.top(now, unit);
        List<PatternQueryService.AnomalyView> anomalies =
                queryService.recentAnomalies(now, hours, 100);
        return ApiResponse.ok(new Overview(top, anomalies));
    }

    /** 模板异常列表（只读，供前端卡片单独拉取）。 */
    @GetMapping("/patterns/anomalies")
    ApiResponse<List<PatternQueryService.AnomalyView>> anomalies(
            @RequestParam(value = "hours", required = false, defaultValue = "24") int hours,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        return ApiResponse.ok(queryService.recentAnomalies(Instant.now(), hours, limit));
    }

    /** 历史诊断与历史模板异常的全文检索（REQ-007）。 */
    @GetMapping("/knowledge/search")
    ApiResponse<PatternQueryService.SearchResult> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return ApiResponse.ok(queryService.search(q, limit));
    }

    record Overview(List<PatternQueryService.PatternView> top,
                    List<PatternQueryService.AnomalyView> anomalies) {
    }
}
