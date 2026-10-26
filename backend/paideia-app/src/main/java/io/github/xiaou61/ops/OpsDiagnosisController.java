package io.github.xiaou61.ops;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端运维诊断端点（WORK-042）。
 *
 * <p>权限由 `/api/v1/ops/admin/** → hasRole("ADMIN")` 兜住，与运维摘要同一条规则；
 * 本类不逐方法加注解。用户 id 只从令牌取，不接受请求参数——那会让"记谁的账"变成可篡改的输入。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ops/admin/diagnoses")
class OpsDiagnosisController {

    private final OpsDiagnosisService service;

    OpsDiagnosisController(OpsDiagnosisService service) {
        this.service = service;
    }

    /** 提交即返回，报告在后台生成；前端按 id 轮询。 */
    @PostMapping
    ResponseEntity<ApiResponse<Created>> create(@RequestBody(required = false) CreateRequest request) {
        long userId = Long.parseLong(CurrentUser.subjectId());
        String question = request == null ? null : request.question();
        long id = service.start("manual", null, question, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(new Created(id)));
    }

    @GetMapping
    ApiResponse<List<OpsDiagnosisService.View>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    ApiResponse<OpsDiagnosisService.View> get(@PathVariable("id") long id) {
        return ApiResponse.ok(service.get(id));
    }

    record CreateRequest(String question) {
    }

    record Created(long id) {
    }
}
