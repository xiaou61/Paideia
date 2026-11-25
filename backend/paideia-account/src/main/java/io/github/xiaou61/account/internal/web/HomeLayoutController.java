package io.github.xiaou61.account.internal.web;

import tools.jackson.databind.JsonNode;
import io.github.xiaou61.account.internal.home.HomeLayoutService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页自定义布局端点（{@code /api/v1/account/home-layout}）。
 *
 * <p>与画像端点同域：只需"已登录"，归属一律取自令牌，路径与请求体里都没有用户标识。
 * 布局只是界面偏好，写它不改变任何权限。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/account/home-layout")
class HomeLayoutController {

    private final HomeLayoutService layouts;

    HomeLayoutController(HomeLayoutService layouts) {
        this.layouts = layouts;
    }

    @GetMapping
    ApiResponse<JsonNode> get() {
        return ApiResponse.ok(layouts.get(CurrentUser.userId()));
    }

    /** 整份覆盖式保存：请求体就是完整布局 JSON（前端决定结构，服务端原样存）。 */
    @PutMapping
    ApiResponse<JsonNode> save(@RequestBody(required = false) JsonNode layout) {
        return ApiResponse.ok(layouts.save(CurrentUser.userId(), layout));
    }
}
