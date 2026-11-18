package io.github.xiaou61.knowledge.internal.web;

import io.github.xiaou61.knowledge.internal.question.PracticeService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 刷题会话接口（WORK-083 TASK-004）。只返回题面，作答走既有 /api/v1/learning/attempts。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/learning/practice")
class PracticeController {

    private final PracticeService service;

    PracticeController(PracticeService service) {
        this.service = service;
    }

    @PostMapping("/sessions")
    ApiResponse<List<PracticeService.PracticeQuestion>> session(
            @RequestBody(required = false) PracticeService.PracticeFilter body) {
        return ApiResponse.ok(service.listQuestions(CurrentUserId.require(), body));
    }
}
