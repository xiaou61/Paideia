package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.xiaou61.persistence.ExampleItemMapper;
import io.github.xiaou61.security.AuthPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 授权隔离的端到端验证（AC-006）。
 *
 * <p>用完整上下文与真实安全过滤器链，但用 MockMvc 而不是真实端口：省掉 HTTP 客户端的
 * 编解码噪音，同时仍然经过过滤器链、控制器、mapper 与数据库。
 *
 * <p>需要真实 MySQL 与 {@code PAIDEIA_TEST_DB_PASSWORD}；未配置时整个类被跳过并计入 Skipped，
 * 不会让构建悄悄变绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class AuthorizationIsolationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ExampleItemMapper mapper;

    @Autowired
    AuthPort authPort;

    MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.create(mockMvc);
        mapper.deleteAll();
        mapper.insert("learner-a", "a-item-1");
        mapper.insert("learner-a", "a-item-2");
        mapper.insert("learner-b", "b-item-1");
    }

    private String tokenFor(String subject) {
        return TestTokens.bearer(authPort, subject);
    }

    @Test
    @DisplayName("没有令牌时被拒为 401")
    void rejectsMissingToken() {
        assertThat(mvc.get().uri("/api/v1/me")).matches(status().isUnauthorized());
    }

    @Test
    @DisplayName("令牌有效时主体取自令牌，而不是请求参数")
    void resolvesSubjectFromToken() {
        assertThat(mvc.get().uri("/api/v1/me").header("Authorization", tokenFor("learner-a")))
                .matches(status().isOk())
                .bodyText()
                .contains("learner-a");
    }

    @Test
    @DisplayName("伪造或篡改的令牌被拒为 401")
    void rejectsForgedToken() {
        assertThat(mvc.get().uri("/api/v1/me").header("Authorization", "Bearer not-a-real-token"))
                .matches(status().isUnauthorized());
    }

    @Test
    @DisplayName("用户只能读到自己的数据，两个令牌各自只看到自己的行")
    void eachTokenSeesOnlyItsOwnRows() {
        assertThat(mvc.get().uri("/api/v1/fixture/items").header("Authorization", tokenFor("learner-a")))
                .matches(status().isOk())
                .bodyText()
                .contains("\"total\":2")
                .contains("a-item-1")
                .contains("a-item-2")
                .doesNotContain("b-item-1");

        assertThat(mvc.get().uri("/api/v1/fixture/items").header("Authorization", tokenFor("learner-b")))
                .matches(status().isOk())
                .bodyText()
                .contains("\"total\":1")
                .contains("b-item-1")
                .doesNotContain("a-item-1");
    }

    @Test
    @DisplayName("客户端试图指定他人的归属时被拒为 403，而不是静默忽略")
    void rejectsForeignOwnerParameter() {
        assertThat(mvc.get().uri("/api/v1/fixture/items?owner=learner-b").header("Authorization", tokenFor("learner-a")))
                .matches(status().isForbidden())
                .bodyText()
                .contains("\"code\":40300")
                .doesNotContain("b-item-1");
    }

    @Test
    @DisplayName("失败响应不泄露堆栈或内部类型")
    void failuresDoNotLeakInternals() {
        assertThat(mvc.get().uri("/api/v1/fixture/items?owner=learner-b").header("Authorization", tokenFor("learner-a")))
                .bodyText()
                .doesNotContain("BizException")
                .doesNotContain("\tat ");
    }

    private static final String DESKTOP_ORIGIN = "http://127.0.0.1:5310";

    @Test
    @DisplayName("健康检查对白名单来源放行跨域；这一条最容易漏，因为该路径不在 /api/** 下")
    void corsAllowsDesktopOriginOnHealth() {
        assertThat(mvc.get().uri("/actuator/health").header("Origin", DESKTOP_ORIGIN))
                .matches(status().isOk())
                .matches(header().string("Access-Control-Allow-Origin", DESKTOP_ORIGIN));
    }

    @Test
    @DisplayName("未列入白名单的来源被直接拒为 403，而不是返回 200 少一个放行头")
    void corsRejectsUnknownOrigin() {
        assertThat(mvc.get().uri("/actuator/health").header("Origin", "http://evil.example.com"))
                .matches(status().isForbidden())
                .matches(header().doesNotExist("Access-Control-Allow-Origin"));

        assertThat(mvc.get().uri("/api/v1/me").header("Origin", "http://evil.example.com"))
                .matches(status().isForbidden())
                .matches(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("安全链接管了 CORS：被拒的请求也带放行头，预检不被认证要求挡住")
    void securityChainHandlesCors() {
        // 无令牌 -> 401，但仍要带 CORS 头，否则浏览器看到的是一个没有原因的失败
        assertThat(mvc.get().uri("/api/v1/me").header("Origin", DESKTOP_ORIGIN))
                .matches(status().isUnauthorized())
                .matches(header().string("Access-Control-Allow-Origin", DESKTOP_ORIGIN));

        // 预检必须放行，否则跨域调用根本发不出去
        assertThat(mvc.options().uri("/api/v1/me")
                .header("Origin", DESKTOP_ORIGIN)
                .header("Access-Control-Request-Method", "GET"))
                .matches(status().isOk())
                .matches(header().string("Access-Control-Allow-Origin", DESKTOP_ORIGIN));
    }
}
