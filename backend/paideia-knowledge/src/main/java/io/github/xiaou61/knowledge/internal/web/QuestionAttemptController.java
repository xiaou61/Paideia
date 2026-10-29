package io.github.xiaou61.knowledge.internal.web;

import io.github.xiaou61.knowledge.internal.question.PrerequisiteDiagnosis;
import io.github.xiaou61.knowledge.internal.question.QuestionAttemptService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 逐题作答与掌握诊断（WORK-033）。
 *
 * <p>与学习记录同一条纪律：请求体里没有用户标识，归属取自令牌；对错由服务端判定，
 * 客户端只能上报"选了第几个"。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/learning")
class QuestionAttemptController {

    private final QuestionAttemptService service;

    QuestionAttemptController(QuestionAttemptService service) {
        this.service = service;
    }

    @PostMapping("/attempts")
    ApiResponse<QuestionAttemptService.AttemptResult> attempt(
            @RequestBody QuestionAttemptService.AttemptRequest body) {
        return ApiResponse.ok(service.record(CurrentUserId.require(), body));
    }

    @GetMapping("/mastery")
    ApiResponse<List<QuestionAttemptService.ConceptMasteryView>> mastery() {
        return ApiResponse.ok(service.mastery(CurrentUserId.require()));
    }

    /** 我在这条里已经答过的题：练习册打开时用它恢复上次的作答。 */
    @GetMapping("/entries/{id}/attempts")
    ApiResponse<List<QuestionAttemptService.AnsweredChoice>> answered(@PathVariable("id") long id) {
        return ApiResponse.ok(service.answered(CurrentUserId.require(), id));
    }

    /** 练习选题（REQ-008）：传概念 id 列表，返回一道信息量最大的题。 */
    @GetMapping("/practice/next")
    ApiResponse<Object> practiceNext(@RequestParam("conceptIds") String conceptIds) {
        List<Long> ids = java.util.Arrays.stream(conceptIds.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(Long::valueOf).toList();
        return ApiResponse.ok(service.nextPractice(CurrentUserId.require(), ids).orElse(null));
    }

    @GetMapping("/diagnosis")
    ApiResponse<List<PrerequisiteDiagnosis.Suggestion>> diagnosis(
            @RequestParam("conceptId") long conceptId) {
        return ApiResponse.ok(service.diagnosis(CurrentUserId.require(), conceptId));
    }
}
