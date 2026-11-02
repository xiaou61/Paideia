package io.github.xiaou61.note.internal.web;

import io.github.xiaou61.note.internal.share.ShareService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 举报入口（学习者侧）。
 *
 * <p>这是免鉴权分享端点的**唯一治理入口**：链接谁都能看，所以必须有一条让内容能被撤下来的路。
 * 举报本身是已登录用户的行为（**要有身份才能追责**），处理则由管理员做（用户裁决 11）。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    record ReportRequest(String shareId, String reason) {
    }

    private final ShareService shares;

    ReportController(ShareService shares) {
        this.shares = shares;
    }

    @PostMapping
    ApiResponse<Boolean> report(@RequestBody ReportRequest request) {
        long userId = requireUserId();
        shares.report(userId, request == null ? null : request.shareId(),
                request == null ? null : request.reason());
        return ApiResponse.ok(true);
    }

    private static long requireUserId() {
        return CurrentUser.userId();
    }
}
