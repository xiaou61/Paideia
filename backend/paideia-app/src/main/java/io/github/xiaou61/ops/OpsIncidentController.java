package io.github.xiaou61.ops;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端事件端点（WORK-043）。
 *
 * <p>权限由 `/api/v1/ops/admin/** → hasRole("ADMIN")` 兜住；用户 id 只从令牌取。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ops/admin/incidents")
class OpsIncidentController {

    private final OpsIncidentService incidents;

    OpsIncidentController(OpsIncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    ApiResponse<List<OpsIncidentService.View>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "sinceHours", required = false) Integer sinceHours,
            @RequestParam(name = "beforeLastSeen", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant beforeLastSeen,
            @RequestParam(name = "beforeId", required = false) Long beforeId) {
        return ApiResponse.ok(incidents.list(status, limit == null ? 50 : limit,
                sinceHours, beforeLastSeen, beforeId));
    }

    @GetMapping("/{id}")
    ApiResponse<OpsIncidentService.Detail> detail(@PathVariable("id") long id) {
        return ApiResponse.ok(incidents.detail(id));
    }

    @PostMapping("/{id}/notes")
    ApiResponse<OpsIncidentService.Detail> note(@PathVariable("id") long id,
                                                @RequestBody(required = false) NoteRequest request) {
        long userId = Long.parseLong(CurrentUser.subjectId());
        return ApiResponse.ok(incidents.addNote(
                id, request == null ? null : request.message(), userId));
    }

    /** 手动关闭（WORK-049）：resolved 漏投递或事件卡住时的兜底。 */
    @PostMapping("/{id}/close")
    ApiResponse<OpsIncidentService.Detail> close(@PathVariable("id") long id) {
        long userId = Long.parseLong(CurrentUser.subjectId());
        return ApiResponse.ok(incidents.close(id, userId));
    }

    record NoteRequest(String message) {
    }
}
