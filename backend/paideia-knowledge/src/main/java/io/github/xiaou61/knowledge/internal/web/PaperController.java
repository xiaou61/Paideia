package io.github.xiaou61.knowledge.internal.web;

import io.github.xiaou61.knowledge.internal.paper.PaperAssembler;
import io.github.xiaou61.knowledge.internal.paper.PaperService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 试卷接口（WORK-083 TASK-002/003）。学习者私有：归属取自令牌，他人 id 一律 404。
 * 卷面响应不含 answerIndex/解析；成绩在交卷后才返回。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/learning/papers")
class PaperController {

    private final PaperService service;

    PaperController(PaperService service) {
        this.service = service;
    }

    @PostMapping
    ApiResponse<PaperService.PaperView> create(@RequestBody PaperAssembler.PaperRule body) {
        return ApiResponse.ok(service.create(CurrentUserId.require(), body));
    }

    @GetMapping
    ApiResponse<List<PaperService.PaperSummary>> list() {
        return ApiResponse.ok(service.list(CurrentUserId.require()));
    }

    @GetMapping("/{id}")
    ApiResponse<PaperService.PaperView> get(@PathVariable("id") long id) {
        return ApiResponse.ok(service.get(CurrentUserId.require(), id));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable("id") long id) {
        service.delete(CurrentUserId.require(), id);
        return ApiResponse.<Void>ok(null);
    }

    @PostMapping("/{id}/submit")
    ApiResponse<PaperService.ResultView> submit(@PathVariable("id") long id,
                                                @RequestBody(required = false) Map<Long, Integer> answers) {
        return ApiResponse.ok(service.submit(CurrentUserId.require(), id, answers));
    }

    @GetMapping("/{id}/result")
    ApiResponse<PaperService.ResultView> result(@PathVariable("id") long id) {
        return ApiResponse.ok(service.result(CurrentUserId.require(), id));
    }
}
