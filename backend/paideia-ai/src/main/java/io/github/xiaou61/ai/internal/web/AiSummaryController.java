package io.github.xiaou61.ai.internal.web;

import io.github.xiaou61.ai.internal.summary.SummaryVisibility;
import io.github.xiaou61.ai.internal.summary.SummaryService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 精简端点。
 *
 * <p>两个动作刻意分开：**读**（{@code GET}）只拿现成的、不花钱、不计限流；**生成**
 * （{@code POST}）才可能产生上游调用。需求定的是"只给按钮触发"，所以生成永远不会
 * 由读路径捎带发生——打开条目不该悄悄花掉用户的额度。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/ai/summaries")
class AiSummaryController {

    /** 入参。`visibility` 缺省时按"公开"处理（Agent 收口：纯 BYOK 下公开池是没配凭据的人唯一的来源）。 */
    record GenerateRequest(Long entryId, String visibility) {
    }

    private final SummaryService summaries;

    AiSummaryController(SummaryService summaries) {
        this.summaries = summaries;
    }

    /** 只读：没有结果时返回 {@code data: null}，而不是 404——"还没生成"不是错误。 */
    @GetMapping("/{entryId}")
    ApiResponse<SummaryService.SummaryView> read(@PathVariable("entryId") long entryId) {
        return ApiResponse.ok(summaries.read(entryId, AiRequester.require()).orElse(null));
    }

    @PostMapping
    ApiResponse<SummaryService.SummaryView> generate(@RequestBody GenerateRequest request) {
        if (request.entryId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "entryId 不能为空");
        }
        String rawVisibility = request.visibility();
        SummaryVisibility visibility = rawVisibility == null
                ? SummaryVisibility.PUBLIC
                : SummaryVisibility.parse(rawVisibility);
        if (visibility == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "visibility 只能是 public 或 private");
        }
        return ApiResponse.ok(summaries.generate(request.entryId(), AiRequester.require(), visibility));
    }
}
