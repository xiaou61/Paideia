package io.github.xiaou61.note.internal.web;

import io.github.xiaou61.note.internal.share.ShareService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免鉴权的只读分享端点。
 *
 * <p><b>这是全项目第二处免鉴权读端点</b>（第一处是 `GET /api/v1/knowledge/files/{uuid}`）。
 * 纪律与那一处相同：① share_id 是 UUID，不可枚举；② 只允许 GET（在安全配置里限定方法）；
 * ③ 产物**只有标题、正文、所挂条目 id**，不含作者、记忆、标记与任何学习痕迹；
 * ④ 失效（过期／撤回／下架）统一 404，不说"为什么失效"。
 *
 * <p>刻意不写成"先查分享记录、判断有效性、再查笔记"：那中间会出现"分享有效但笔记已删"的
 * 空档，而这个端点没有身份可用于兜底。join 一次取回，读不到就是读不到。
 */
@ApiAccess(ApiAccess.Access.PUBLIC_READ)
@RestController
@RequestMapping("/api/v1/public/shares")
class PublicShareController {

    private final ShareService shares;

    PublicShareController(ShareService shares) {
        this.shares = shares;
    }

    @GetMapping("/{shareId}")
    ResponseEntity<ApiResponse<ShareService.SharedView>> read(@PathVariable("shareId") String shareId) {
        return shares.read(shareId)
                .map(view -> ResponseEntity.ok(ApiResponse.ok(view)))
                // 手写 404（而不是抛异常）是有意的：全局异常处理会带上 traceId 之类的运行信息，
                // 而这个端点是给站外的人看的，越少越好
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
