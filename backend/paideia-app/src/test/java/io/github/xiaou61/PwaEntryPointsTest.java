package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * PWA 入口必须匿名可读（WORK-016）。
 *
 * <p><b>为什么单列一条</b>：浏览器取 `manifest.webmanifest` 与注册 `sw.js` 都发生在**登录之前**，
 * 且都是不带令牌的普通 GET。它们落在 `anyRequest().authenticated()` 之后就会拿到 401，
 * 而症状是**完全静默的**——页面照常打开，只是"安装"永远不出现、离线壳永远不生效，
 * 控制台里只有一条不太醒目的 "Manifest fetch failed"。
 *
 * <p>2026-10-18 首次部署 WORK-016 时实测踩到：`GET /manifest.webmanifest` 与 `GET /sw.js`
 * 都是 401，而同一批的 `/` 与 `/admin/index.html` 是 200——差别就是白名单里少了这三个入口。
 *
 * <p>用完整上下文与真实过滤器链；不需要数据库，所以不按库口令跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PwaEntryPointsTest {

    @Autowired
    MockMvc mockMvc;

    /**
     * 断言"不是 401/403"，而不是"是 200"。
     *
     * <p>测试类路径里没有前端产物，所以放行之后 Spring 会回 404（找不到静态资源）；
     * 本用例要钉的是**授权层放行**这件事，不是文件是否存在。
     */
    private void assertNotBlockedByAuth(String path) {
        MockMvcTester mvc = MockMvcTester.create(mockMvc);
        assertThat(mvc.get().uri(path))
                .as("%s 必须匿名可读，否则浏览器取不到它", path)
                .matches(status().isNotFound());
    }

    @Test
    @DisplayName("PWA 清单与 Service Worker 不需要令牌")
    void pwaEntryPointsAreAnonymouslyReadable() {
        assertNotBlockedByAuth("/manifest.webmanifest");
        assertNotBlockedByAuth("/sw.js");
        assertNotBlockedByAuth("/registerSW.js");
    }

    @Test
    @DisplayName("PWA 图标与既有入口同样不需要令牌")
    void pwaIconsAreAnonymouslyReadable() {
        assertNotBlockedByAuth("/pwa-192.png");
        assertNotBlockedByAuth("/pwa-512.png");
        assertNotBlockedByAuth("/apple-touch-icon.png");
        // 三端 index.html 都引了 SVG 图标；早先白名单只有 .ico，浏览器取 /favicon.svg 会拿到 401
        assertNotBlockedByAuth("/favicon.svg");
        assertNotBlockedByAuth("/favicon.ico");
    }

    @Test
    @DisplayName("接口仍然需要令牌——放行静态文件不等于放行数据")
    void apiIsStillProtected() {
        MockMvcTester mvc = MockMvcTester.create(mockMvc);
        assertThat(mvc.get().uri("/api/v1/me")).matches(status().isUnauthorized());
    }
}
