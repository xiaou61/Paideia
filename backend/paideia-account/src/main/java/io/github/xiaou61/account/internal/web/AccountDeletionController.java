package io.github.xiaou61.account.internal.web;

import io.github.xiaou61.account.internal.user.AccountDeletionService;
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
 * 注销端点。
 *
 * <p>路径放在 `/api/v1/me/**` 而不是 `/api/v1/auth/**`：后者在安全配置里是 **permitAll**，
 * 注销绝不能免鉴权。`/api/v1/me/**` 落到 `anyRequest().authenticated()`，正是想要的语义。
 *
 * <p>用 POST 而不是 DELETE：请求体里要带密码确认，而 DELETE 带体在不同客户端上表现不一。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/me")
class AccountDeletionController {

    /**
     * 注销请求体。
     *
     * <p>{@code confirmed} 只对没有密码的账号有意义（WORK-037）：第三方注册的用户从未设过密码，
     * 要求密码等于让他们永远注销不了。有密码的账号仍然只认密码，{@code confirmed} 被忽略。
     */
    record DeletionRequest(String password, Boolean confirmed) {
    }

    private final AccountDeletionService deletion;

    AccountDeletionController(AccountDeletionService deletion) {
        this.deletion = deletion;
    }

    /**
     * 注销当前账号。**不可恢复**：会话、记忆、笔记与已发出的分享一并删除。
     *
     * @return 恒为 true（成功了就是成功了；失败会抛出去）
     */
    @PostMapping("/deletion")
    ApiResponse<Boolean> delete(@RequestBody(required = false) DeletionRequest request) {
        long userId = requireUserId();
        deletion.delete(userId,
                request == null ? null : request.password(),
                request != null && Boolean.TRUE.equals(request.confirmed()));
        return ApiResponse.ok(true);
    }

    private static long requireUserId() {
        return CurrentUser.userId();
    }
}
