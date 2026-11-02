package io.github.xiaou61.ops;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维摘要（WORK-028 REQ-028）。
 *
 * <p>{@code /api/v1/ops/admin/**} 由 `SecurityConfiguration` 的 `hasRole("ADMIN")` 兜住，
 * 且那条规则排在更宽的规则之前。本类**只读**：运维面不该成为第二个能被写坏的地方。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ops/admin")
class OpsSummaryController {

    private final OpsSummaryService service;

    OpsSummaryController(OpsSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    ApiResponse<OpsSummary> summary() {
        return ApiResponse.ok(service.summary());
    }
}
