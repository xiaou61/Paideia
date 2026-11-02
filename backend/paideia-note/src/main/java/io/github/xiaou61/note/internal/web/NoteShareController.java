package io.github.xiaou61.note.internal.web;

import io.github.xiaou61.note.internal.share.ShareService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分享端点。
 *
 * <p><b>两个控制器的职责刻意分开</b>：这里（`/api/v1/notes/{id}/share` 与 `/api/v1/shares/{id}`）
 * 是**本人**动作，走鉴权；只读读取在 {@link PublicShareController}，是**免鉴权**的。
 * 把两者写在同一个类里，很容易在加一个方法时忘了它属于哪一边。
 *
 * <p>路径改到 `/api/v1/...` 而不是 design 写的 `/share/{shareId}`：后者会与前端 SPA 的分享页
 * 路由**撞同一个路径**（一个要返回 JSON、一个要返回页面）。作为一处局部事实订正，改到
 * 项目既有的 `/api` 前缀下，免鉴权这件事由安全规则显式声明。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1")
class NoteShareController {

    private final ShareService shares;

    NoteShareController(ShareService shares) {
        this.shares = shares;
    }

    /** 生成一条分享链接的 id。分享内容由免鉴权端点读，这里只回 id。 */
    @PostMapping("/notes/{id}/share")
    ApiResponse<String> share(@PathVariable("id") long noteId) {
        return ApiResponse.ok(shares.share(requireUserId(), noteId));
    }

    /** 我在这条笔记上还有效的分享 id。没有它，离开页面就再也撤不回来了。 */
    @GetMapping("/notes/{id}/shares")
    ApiResponse<List<String>> activeShares(@PathVariable("id") long noteId) {
        return ApiResponse.ok(shares.activeShares(requireUserId(), noteId));
    }

    /** 撤回。 */
    @DeleteMapping("/shares/{shareId}")
    ApiResponse<Boolean> revoke(@PathVariable("shareId") String shareId) {
        shares.revoke(requireUserId(), shareId);
        return ApiResponse.ok(true);
    }

    private static long requireUserId() {
        return CurrentUser.userId();
    }
}
