package io.github.xiaou61.knowledge.internal.web;

import io.github.xiaou61.knowledge.internal.question.WrongBookService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 错题本（WORK-083 TASK-005）：返回当前用户最新一次答错的题，可按分类/概念过滤。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/learning")
class WrongBookController {

    private final WrongBookService service;

    WrongBookController(WrongBookService service) {
        this.service = service;
    }

    @GetMapping("/wrong-questions")
    ApiResponse<List<WrongBookService.WrongQuestionView>> wrongQuestions(
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "conceptId", required = false) Long conceptId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.list(CurrentUserId.require(), categoryId, conceptId, limit));
    }
}
