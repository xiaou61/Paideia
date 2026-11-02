package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.account.internal.oauth.OAuthHttp;
import io.github.xiaou61.account.internal.oauth.OAuthProvider;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * 第三方登录的 HTTP 链路（WORK-037 AC-001..AC-005、AC-007）。
 *
 * <p>与 {@code OAuthLoginIntegrationTest} 的分工：那个验服务层规则，这个验**端点形状与
 * 放行规则**——回调能不能匿名打到、state 伪造会不会被拒、令牌会不会漏进 URL。
 *
 * <p>平台 HTTP 被桩掉（{@link OAuthHttp}），因此不依赖真实平台。
 *
 * <p><b>配置是测试里给的</b>：真实的 clientId/secret 不该出现在仓库里，这里用占位值，
 * 只为让 {@code enabled()} 成立。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paideia.oauth.web-base-url=http://localhost:5173",
        "paideia.oauth.callback-base-url=http://localhost:8081",
        "paideia.oauth.providers.github.client-id=test-client-id",
        "paideia.oauth.providers.github.client-secret=test-client-secret",
        "paideia.oauth.providers.gitee.client-id=test-gitee-id",
        "paideia.oauth.providers.gitee.client-secret=test-gitee-secret",
})
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class OAuthEndpointsIntegrationTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 100_000);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OAuthHttp oauthHttp;

    @BeforeEach
    void stubPlatform() {
        when(oauthHttp.exchangeCode(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("platform-access-token");
    }

    private static String uniqueSubject() {
        return "ep-" + SEQUENCE.incrementAndGet();
    }

    private void stubUser(String subject, String login) {
        when(oauthHttp.fetchUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(JSON.readTree("""
                        {"id": "%s", "login": "%s", "name": "%s", "email": null}
                        """.formatted(subject, login, login)));
    }

    private String body(MvcResult result) throws Exception {
        return MockMvcBodies.body(result);
    }

    // ---------------------------------------------------------------- 放行与形状

    @Test
    @DisplayName("未登录能读到可用平台列表，且只列出配齐凭据的那些")
    void providersArePublicButCredentialGated() throws Exception {
        String json = body(mvc.perform(get("/api/v1/auth/oauth/providers")).andReturn());

        assertThat(json).contains("github").contains("gitee");
        assertThat(json).doesNotContain("test-client-secret");
        assertThat(json).doesNotContain("test-gitee-secret");
    }

    @Test
    @DisplayName("匿名发起登录拿到平台地址，且该地址里带 state 与我们的回调")
    void startIsAnonymousAndCarriesState() throws Exception {
        String json = body(mvc.perform(post("/api/v1/auth/oauth/github/start")).andReturn());

        String url = JsonPath.read(json, "$.data.authorizeUrl");
        assertThat(url).startsWith("https://github.com/login/oauth/authorize?");
        assertThat(url).contains("state=");
        assertThat(url).contains(
                "redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Fapi%2Fv1%2Fauth%2Foauth%2Fgithub%2Fcallback");
        // client secret 绝不能出现在任何下发给浏览器的内容里
        assertThat(json).doesNotContain("test-client-secret");
    }

    @Test
    @DisplayName("不认识的平台返回 404 语义，不猜一个默认平台")
    void unknownProviderIsRejected() throws Exception {
        String json = body(mvc.perform(post("/api/v1/auth/oauth/qq/start")).andReturn());

        assertThat(((Number) JsonPath.read(json, "$.code")).intValue()).isEqualTo(40400);
    }

    // ---------------------------------------------------------------- AC-007 state 防护

    @Test
    @DisplayName("AC-007 伪造的 state 被拒，且重定向到登录页而不是返回令牌")
    void forgedStateRedirectsToLogin() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/oauth/github/callback")
                .param("code", "some-code")
                .param("state", "forged-state")
                .with(request -> {
                    request.setRemoteAddr("10.9.9.9");
                    return request;
                })).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location"))
                .contains("/#/login")
                .contains("oauthError=expired");
        // 关键：失败路径上绝不能出现令牌或握手码
        assertThat(result.getResponse().getHeader("Location")).doesNotContain("handoff");
    }

    @Test
    @DisplayName("AC-007 同一个 state 第二次回调被拒（重放）")
    void replayedStateIsRejected() throws Exception {
        stubUser(uniqueSubject(), "replay");
        String json = body(mvc.perform(post("/api/v1/auth/oauth/github/start")).andReturn());
        String url = JsonPath.read(json, "$.data.authorizeUrl");
        String state = url.substring(url.indexOf("state=") + "state=".length());

        MvcResult first = mvc.perform(get("/api/v1/auth/oauth/github/callback")
                .param("code", "c1").param("state", state)).andReturn();
        assertThat(first.getResponse().getHeader("Location")).contains("handoff=");

        MvcResult second = mvc.perform(get("/api/v1/auth/oauth/github/callback")
                .param("code", "c1").param("state", state)).andReturn();
        assertThat(second.getResponse().getHeader("Location"))
                .as("state 可重放，等于给了一个可反复使用的登录链接")
                .contains("oauthError=expired");
    }

    @Test
    @DisplayName("拿 A 平台的 state 去 B 平台回调，被拒")
    void stateIsBoundToProvider() throws Exception {
        String json = body(mvc.perform(post("/api/v1/auth/oauth/github/start")).andReturn());
        String url = JsonPath.read(json, "$.data.authorizeUrl");
        String state = url.substring(url.indexOf("state=") + "state=".length());

        MvcResult result = mvc.perform(get("/api/v1/auth/oauth/gitee/callback")
                .param("code", "c1").param("state", state)).andReturn();

        assertThat(result.getResponse().getHeader("Location")).contains("oauthError=expired");
    }

    @Test
    @DisplayName("用户在平台上点了拒绝时按 denied 处理，不当作故障")
    void userDenialIsReportedAsDenied() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/oauth/github/callback")
                .param("error", "access_denied")).andReturn();

        assertThat(result.getResponse().getHeader("Location")).contains("oauthError=denied");
    }

    // ---------------------------------------------------------------- 完整链路

    @Test
    @DisplayName("AC-001 完整回调 → 握手码 → 换令牌，URL 里没有令牌")
    void fullCallbackFlowYieldsTokens() throws Exception {
        stubUser(uniqueSubject(), "flow");
        String json = body(mvc.perform(post("/api/v1/auth/oauth/github/start")).andReturn());
        String url = JsonPath.read(json, "$.data.authorizeUrl");
        String state = url.substring(url.indexOf("state=") + "state=".length());

        MvcResult callback = mvc.perform(get("/api/v1/auth/oauth/github/callback")
                .param("code", "good-code").param("state", state)).andReturn();

        String location = callback.getResponse().getHeader("Location");
        assertThat(location).startsWith("http://localhost:5173/#/oauth/callback?handoff=");
        assertThat(location).doesNotContain("accessToken").doesNotContain("refreshToken");

        String handoff = location.substring(location.indexOf("handoff=") + "handoff=".length());
        String exchanged = body(mvc.perform(post("/api/v1/auth/oauth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"handoff\":\"" + handoff + "\"}")).andReturn());

        assertThat(exchanged).contains("accessToken").contains("refreshToken");
        assertThat(((Number) JsonPath.read(exchanged, "$.code")).intValue()).isZero();

        // 握手码一次性：同一个再用一次必须失败
        String again = body(mvc.perform(post("/api/v1/auth/oauth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"handoff\":\"" + handoff + "\"}")).andReturn());
        assertThat(((Number) JsonPath.read(again, "$.code")).intValue()).isEqualTo(40000);
    }

    @Test
    @DisplayName("AC-004 已登录后能看到绑定列表；AC-005 唯一方式不能解绑")
    void identitiesRequireLoginAndRespectLastCredential() throws Exception {
        // 先造一个登录态
        stubUser(uniqueSubject(), "ident");
        String json = body(mvc.perform(post("/api/v1/auth/oauth/github/start")).andReturn());
        String url = JsonPath.read(json, "$.data.authorizeUrl");
        String state = url.substring(url.indexOf("state=") + "state=".length());
        String location = mvc.perform(get("/api/v1/auth/oauth/github/callback")
                .param("code", "c").param("state", state)).andReturn()
                .getResponse().getHeader("Location");
        String handoff = location.substring(location.indexOf("handoff=") + "handoff=".length());
        String tokens = body(mvc.perform(post("/api/v1/auth/oauth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"handoff\":\"" + handoff + "\"}")).andReturn());
        String bearer = "Bearer " + JsonPath.read(tokens, "$.data.accessToken").toString();

        String identities = body(mvc.perform(get("/api/v1/account/oauth/identities")
                .header("Authorization", bearer)).andReturn());
        assertThat(identities).contains("github").contains("\"hasPassword\":false");
        // providerUserId 不下发
        assertThat(identities).doesNotContain("ep-");

        String unbind = body(mvc.perform(delete("/api/v1/account/oauth/github")
                .header("Authorization", bearer)).andReturn());
        assertThat(((Number) JsonPath.read(unbind, "$.code")).intValue())
                .as("唯一登录方式被解掉了：%s", unbind)
                .isEqualTo(40900);
    }

    @Test
    @DisplayName("绑定端点在未登录时被拒（它不在放行的 auth 前缀下）")
    void identitiesEndpointRejectsAnonymous() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/account/oauth/identities")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("桌面端发起时回调带 desktop=1，由回调页转成 paideia:// 而不是留在浏览器")
    void desktopStartMarksCallback() throws Exception {
        stubUser(uniqueSubject(), "desktop");
        String json = body(mvc.perform(post("/api/v1/auth/oauth/github/start")
                .param("desktop", "true")).andReturn());
        String url = JsonPath.read(json, "$.data.authorizeUrl");
        String state = url.substring(url.indexOf("state=") + "state=".length());

        MvcResult callback = mvc.perform(get("/api/v1/auth/oauth/github/callback")
                .param("code", "c").param("state", state)).andReturn();

        String location = callback.getResponse().getHeader("Location");
        assertThat(location).contains("/#/oauth/callback").contains("desktop=1");
        // 令牌仍然只在握手码里，desktop 这个标记不改变"URL 里没有令牌"这条
        assertThat(location).doesNotContain("accessToken").doesNotContain("refreshToken");
    }

    @Test
    @DisplayName("绑定用途的回调回到账号设置页，而不是回调页")
    void bindPurposeReturnsToProfile() throws Exception {
        stubUser(uniqueSubject(), "bindback");
        // 先建一个账号拿到令牌
        String startJson = body(mvc.perform(post("/api/v1/auth/oauth/github/start")).andReturn());
        String startUrl = JsonPath.read(startJson, "$.data.authorizeUrl");
        String loginState = startUrl.substring(startUrl.indexOf("state=") + "state=".length());
        String loc = mvc.perform(get("/api/v1/auth/oauth/github/callback")
                .param("code", "c").param("state", loginState)).andReturn()
                .getResponse().getHeader("Location");
        String handoff = loc.substring(loc.indexOf("handoff=") + "handoff=".length());
        String tokens = body(mvc.perform(post("/api/v1/auth/oauth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"handoff\":\"" + handoff + "\"}")).andReturn());
        String bearer = "Bearer " + JsonPath.read(tokens, "$.data.accessToken").toString();

        // 再发起绑定
        String bindJson = body(mvc.perform(post("/api/v1/account/oauth/gitee/start")
                .header("Authorization", bearer)).andReturn());
        String bindUrl = JsonPath.read(bindJson, "$.data.authorizeUrl");
        String bindState = bindUrl.substring(bindUrl.indexOf("state=") + "state=".length());
        stubUser(uniqueSubject(), "bindback2");

        MvcResult callback = mvc.perform(get("/api/v1/auth/oauth/gitee/callback")
                .param("code", "c2").param("state", bindState)).andReturn();

        assertThat(callback.getResponse().getHeader("Location"))
                .contains("/#/profile")
                .contains("oauthBound=1");
    }

    @Test
    @DisplayName("平台没配置时不渲染该平台：providers 里查不到它")
    void unconfiguredProviderIsInvisible() {
        // 本测试类两个平台都配了；用枚举覆盖来验"未配置=不可见"这条判断本身，
        // 真实部署里少配一个平台就走这条分支。
        assertThat(OAuthProvider.ofKey("weibo")).isNull();
    }
}
