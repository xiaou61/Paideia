package io.github.xiaou61.ai.internal.web;

import io.github.xiaou61.ai.internal.essay.EssayReviewService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面经复核队列的管理端接口（WORK-084 TASK-006）。
 *
 * <p>鉴权靠路径规则：整个 {@code /api/v1/ai/admin/**} 由 {@code SecurityConfiguration} 的
 * {@code hasRole('ADMIN')} 兜住（须排在更宽的 {@code /api/v1/ai/**} 之前），所以本类不逐方法加注解。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/ai/admin/essay-verifications")
class EssayReviewAdminController {

    private final EssayReviewService service;

    EssayReviewAdminController(EssayReviewService service) {
        this.service = service;
    }

    /** 采纳请求：可覆盖题目/答案；{@code publish} 决定入库为发布还是草稿。 */
    record ApproveRequest(String title, String answer, Boolean publish) {
    }

    /** 驳回请求：备注可选。 */
    record RejectRequest(String note) {
    }

    @GetMapping
    ApiResponse<PageResult<EssayReviewService.ReviewItem>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return ApiResponse.ok(service.page(status, PageQuery.of(page, size, null)));
    }

    @PostMapping("/{id}/approve")
    ApiResponse<Map<String, Object>> approve(@PathVariable("id") long id,
                                             @RequestBody(required = false) ApproveRequest body) {
        long reviewer = AiRequester.require();
        boolean publish = body != null && Boolean.TRUE.equals(body.publish());
        long entryId = service.approve(id, body == null ? null : body.title(),
                body == null ? null : body.answer(), publish, reviewer);
        return ApiResponse.ok(Map.of("entryId", entryId));
    }

    @PostMapping("/{id}/reject")
    ApiResponse<Void> reject(@PathVariable("id") long id,
                             @RequestBody(required = false) RejectRequest body) {
        long reviewer = AiRequester.require();
        service.reject(id, body == null ? null : body.note(), reviewer);
        return ApiResponse.ok(null);
    }
}
