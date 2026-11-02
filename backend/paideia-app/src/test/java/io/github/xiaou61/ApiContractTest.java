package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.security.AuthPort;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * L2a：对运行时派生出的每个端点跑**无副作用**的边界检查，并把
 * {@code @ApiAccess} 声明与**实际鉴权行为**对照。
 *
 * <p>它回答的是横向问题——"每个接口的边界是不是一致"——而不是"这个接口的业务对不对"。
 * 后者由模块内的业务集成测试承担。
 *
 * <p><b>矩阵永不发送合法业务数据</b>：只有匿名请求、越权请求与畸形 JSON。
 * 畸形体在参数绑定阶段就被拒（{@code HttpMessageNotReadableException}），到不了 service，
 * 因此不会写库、不会跑代码、不会花积分。这是它能在全部端点上放心执行的前提。
 *
 * <p>安全层的 401/403 由 Spring 默认入口点产生，**没有 {@code ApiResponse} 包络**，所以那两类
 * 只断言状态码；业务层的错误（400/404）才有 {@code code} 字段。
 *
 * <p><b>声明与行为对照</b>（{@link #declaredAdminReallyIsAdmin()} 等）：这是把"清单写错也查不出来"
 * 这个缺口关掉的机制——声明为 ADMIN 的端点，学习者令牌必须 403；声明为其他级别的不能是 403。
 * 只对"探测本身无副作用"的端点做（GET、带请求体的非 GET），其余在报告里列为已知缺口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthPort authPort;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private List<ApiSurfaceTest.Endpoint> endpoints() {
        return ApiSurfaceTest.endpoints(handlerMapping).stream()
                .filter(endpoint -> "ANY".equals(endpoint.method()) == false)
                .toList();
    }

    @Test
    @DisplayName("匿名访问需要身份的路由一律 401")
    void anonymousIsRejectedEverywhereItShouldBe() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ApiSurfaceTest.Endpoint endpoint : endpoints()) {
            if (isPublic(endpoint)) {
                continue;
            }
            int status = perform(endpoint, null, null).getResponse().getStatus();
            if (status != 401) {
                failures.add(endpoint.method() + " " + endpoint.pattern() + " → " + status + "（期望 401）");
            }
        }
        assertThat(failures).as("这些路由允许匿名访问了").isEmpty();
    }

    @Test
    @DisplayName("公开读路由不要求身份（不得是 401）")
    void publicReadRoutesDoNotRequireAuthentication() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ApiSurfaceTest.Endpoint endpoint : endpoints()) {
            if (endpoint.access() != ApiAccess.Access.PUBLIC_READ) {
                continue;
            }
            int status = perform(endpoint, null, null).getResponse().getStatus();
            if (status == 401) {
                failures.add(endpoint.method() + " " + endpoint.pattern() + " 把匿名请求挡成了 401");
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("声明为 ADMIN 的端点，学习者令牌必须 403")
    void declaredAdminReallyIsAdmin() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ApiSurfaceTest.Endpoint endpoint : endpoints()) {
            if (endpoint.access() != ApiAccess.Access.ADMIN) {
                continue;
            }
            int status = perform(endpoint, learnerToken(), null).getResponse().getStatus();
            if (status != 403) {
                failures.add(endpoint.method() + " " + endpoint.pattern() + " → " + status
                        + "（声明为 ADMIN，但学习者没有被拒；检查 SecurityConfiguration 的路径规则是否覆盖它、顺序是否在前）");
            }
        }
        assertThat(failures).as("管理端规则漏配或顺序写反").isEmpty();
    }

    @Test
    @DisplayName("声明为非 ADMIN 的端点，学习者令牌不能是 403")
    void declaredNonAdminIsNotForbidden() throws Exception {
        List<String> failures = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ApiSurfaceTest.Endpoint endpoint : endpoints()) {
            if (endpoint.access() == ApiAccess.Access.ADMIN || isPublicWrite(endpoint)) {
                continue;
            }
            // 只探测无副作用的端点：GET 是读；带请求体的非 GET 会被畸形体在绑定阶段拒绝
            if (!"GET".equals(endpoint.method()) && !hasRequestBody(endpoint)) {
                skipped.add(endpoint.method() + " " + endpoint.pattern());
                continue;
            }
            int status = perform(endpoint, learnerToken(), "{ not json").getResponse().getStatus();
            if (status == 403) {
                failures.add(endpoint.method() + " " + endpoint.pattern()
                        + " 对学习者返回 403，但没有声明 ADMIN——声明漏了，或路径规则比它声明的更严");
            }
        }
        assertThat(failures).isEmpty();
        // skipped 不该悄悄长大：新增了这类端点时要么给它请求体，要么在这里留记录
        assertThat(skipped).as("这些端点无法无副作用地探测鉴权（没有请求体，POST 会真写库），未参与对照")
                .allMatch(entry -> entry.startsWith("DELETE ") || entry.startsWith("POST ")
                        || entry.startsWith("PUT "));
    }

    @Test
    @DisplayName("声明为 LOCAL_ONLY 的端点：非回环来源 403；回环也要共享令牌，JWT 不算数")
    void declaredLocalOnlyReallyIsLocalOnly() throws Exception {
        List<String> problems = new ArrayList<>();
        for (ApiSurfaceTest.Endpoint endpoint : endpoints()) {
            if (!isLocalOnly(endpoint)) {
                continue;
            }
            // 非回环 + 已认证：安全链必须拒为 403。**必须带令牌探测**——匿名被拒走的是入口点
            // （401），而规则若整个缺失，请求会落到控制器里的令牌闸，同样是 401；
            // 只有"已认证 + 非回环"的 403 才能区分"规则在"与"规则没了"。
            int remoteStatus = perform(endpoint, learnerToken(), null, "198.51.100.7")
                    .getResponse().getStatus();
            if (remoteStatus != 403) {
                problems.add(endpoint.method() + " " + endpoint.pattern()
                        + " 非回环来源带学习者令牌得到 " + remoteStatus
                        + "（期望 403；检查 LocalOnlyAuthorizationManager 是否覆盖它）");
                continue;
            }
            // 回环来源：安全链放行，但 JWT 不算数——没有共享令牌必须被控制器的令牌闸拒为 401
            int loopbackStatus = perform(endpoint, learnerToken(), null).getResponse().getStatus();
            if (loopbackStatus != 401) {
                problems.add(endpoint.method() + " " + endpoint.pattern()
                        + " 回环但只有 JWT、没有共享令牌得到 " + loopbackStatus + "（期望 401）");
            }
        }
        assertThat(problems).as("LOCAL_ONLY 声明与实际行为不一致").isEmpty();
    }

    @Test
    @DisplayName("畸形 JSON 体得到 400；只收 multipart 的路由得到 415")
    void malformedBodyIsAClientError() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ApiSurfaceTest.Endpoint endpoint : endpoints()) {
            // 反代端点的请求体是**不透明字节**，本服务不解析它（WORK-028）：畸形 JSON 对它
            // 是合法输入，应当原样转给 Grafana 由下游决定。把它算进来等于逼转发路径加一层
            // 它不该有的解析。
            // LOCAL_ONLY 也被排除：它要求共享令牌，探测矩阵不持有——请求在令牌闸就 401，
            // 到不了畸形体解析，没法在这里断言 400。它的边界与令牌闸由
            // declaredLocalOnlyReallyIsLocalOnly 对照。
            if (isPublicWrite(endpoint) || isOpaqueForwarder(endpoint) || isLocalOnly(endpoint)
                    || !hasRequestBody(endpoint)) {
                continue;
            }
            String token = tokenFor(endpoint);
            MvcResult result = perform(endpoint, token, "{ not json");
            int status = result.getResponse().getStatus();

            if (consumesMultipart(endpoint)) {
                if (status != 415) {
                    failures.add(endpoint.method() + " " + endpoint.pattern() + " → " + status + "（期望 415）");
                }
                continue;
            }
            if (status != 400) {
                failures.add(endpoint.method() + " " + endpoint.pattern() + " → " + status + "（期望 400）");
                continue;
            }
            Integer code = readCode(result);
            if (code == null || code != 40000) {
                failures.add(endpoint.method() + " " + endpoint.pattern() + " → 400 但业务码是 " + code + "（期望 40000）");
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("每个被探测的响应都带合法的 X-Trace-Id")
    void everyResponseCarriesTraceId() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ApiSurfaceTest.Endpoint endpoint : endpoints()) {
            if (endpoint.access() == ApiAccess.Access.PUBLIC_WRITE) {
                continue;
            }
            String traceId = perform(endpoint, tokenFor(endpoint), null).getResponse().getHeader("X-Trace-Id");
            if (traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,64}")) {
                failures.add(endpoint.method() + " " + endpoint.pattern() + " → " + traceId);
            }
        }
        assertThat(failures).as("TraceIdFilter 可能没有最先执行，或响应头被覆盖了").isEmpty();
    }

    @Test
    @DisplayName("未知路径是 404，不是 500")
    void unknownPathIsNotFound() throws Exception {
        MvcResult result = mockMvc.perform(request(HttpMethod.GET, "/api/v1/__not_a_route")
                .header("Authorization", learnerToken())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("路径存在但方法不对是 405")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        // /api/v1/ai/credits 只注册了 GET
        MvcResult result = mockMvc.perform(request(HttpMethod.DELETE, "/api/v1/ai/credits")
                .header("Authorization", learnerToken())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(405);
    }

    @Test
    @DisplayName("成功响应走统一包络：code=0、traceId 非空")
    void successfulResponseUsesEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(request(HttpMethod.GET, "/api/v1/me")
                .header("Authorization", learnerToken())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((Integer) JsonPath.read(body, "$.code")).isEqualTo(0);
        assertThat((String) JsonPath.read(body, "$.traceId")).isNotBlank();
        assertThat((String) JsonPath.read(body, "$.data.subject")).isNotBlank();
    }

    // ---- 辅助 ----

    private static boolean isPublic(ApiSurfaceTest.Endpoint endpoint) {
        return endpoint.access() == ApiAccess.Access.PUBLIC_READ
                || endpoint.access() == ApiAccess.Access.PUBLIC_WRITE;
    }

    private static boolean isPublicWrite(ApiSurfaceTest.Endpoint endpoint) {
        return endpoint.access() == ApiAccess.Access.PUBLIC_WRITE;
    }

    /** 只许回环来源的机器到机器端点：探测矩阵不持有它的共享令牌。 */
    private static boolean isLocalOnly(ApiSurfaceTest.Endpoint endpoint) {
        return endpoint.access() == ApiAccess.Access.LOCAL_ONLY;
    }

    /** Grafana 反代（WORK-028）：请求体原样透传，本服务不解析。 */
    private static boolean isOpaqueForwarder(ApiSurfaceTest.Endpoint endpoint) {
        return endpoint.pattern().startsWith("/api/v1/ops/grafana/");
    }

    private String tokenFor(ApiSurfaceTest.Endpoint endpoint) {
        return endpoint.access() == ApiAccess.Access.ADMIN ? adminToken() : learnerToken();
    }

    private String learnerToken() {
        return TestTokens.bearer(authPort, "1");
    }

    private String adminToken() {
        return TestTokens.bearer(authPort, "1", AuthPort.ROLE_ADMIN);
    }

    /**
     * 发一个最小的探测请求；路径变量按语义替换。
     *
     * <p><b>这里曾经有一个会删数据的缺陷（2026-10-18 查出并修）</b>：路径变量被一律填成
     * `1`，而 `DELETE /api/v1/knowledge/admin/entries/{id}` 用管理员令牌探测时会**真的删掉
     * 种子条目 1**。后果不是这条用例红，而是**后面跑的 KnowledgeRead / KnowledgeSelfAssessment
     * 全部红**——症状是"找不到 id=1 的条目"，看起来像知识库坏了，实际是这个探测留下的后果。
     * 更糟的是它会静默发生：删除成功返回 200，而这里只看 traceId。
     *
     * <p>现在的规则：**破坏性方法（DELETE / 写类 POST 与 PUT）一律不碰真实资源**，用不存在的
     * 高位 id（{@value #PROBE_ID}）。这样探测要么 404、要么被参数校验拒，都不会改库。
     * 非破坏性方法仍填 `1`——读一个不存在的 id 与读存在的 id 对响应头没有区别。
     */
    private static final String PROBE_ID = "9223372036854775806";

    private MvcResult perform(ApiSurfaceTest.Endpoint endpoint, String bearer, String body) throws Exception {
        return perform(endpoint, bearer, body, null);
    }

    /** 带 remoteAddr 覆盖的重载：用来探测 LOCAL_ONLY 的"来源即身份"。 */
    private MvcResult perform(ApiSurfaceTest.Endpoint endpoint, String bearer, String body,
                              String remoteAddr) throws Exception {
        var builder = request(HttpMethod.valueOf(endpoint.method()), concretePath(endpoint));
        if (bearer != null) {
            builder.header("Authorization", bearer);
        }
        if (body != null) {
            builder.contentType(APPLICATION_JSON).content(body);
        }
        if (remoteAddr != null) {
            builder.with(probe -> {
                probe.setRemoteAddr(remoteAddr);
                return probe;
            });
        }
        return mockMvc.perform(builder).andReturn();
    }

    /** 这个方法会改库吗？DELETE 与带体写入都算。 */
    private boolean isDestructive(ApiSurfaceTest.Endpoint endpoint) {
        return "DELETE".equals(endpoint.method())
                || "PUT".equals(endpoint.method())
                || ("POST".equals(endpoint.method()) && hasRequestBody(endpoint));
    }

    private String concretePath(ApiSurfaceTest.Endpoint endpoint) {
        String pattern = endpoint.pattern();
        // 只有两处变量有语义要求：shareId 必须是 UUID 形状，source 只认 structured/text。
        String replaced = pattern
                .replace("{shareId}", UUID.randomUUID().toString())
                .replace("{source}", "text");
        // 破坏性方法绝不填 1：那会命中种子里真实存在的 id，把数据删掉/改掉
        String filler = isDestructive(endpoint) ? PROBE_ID : "1";
        return replaced.replaceAll("\\{[^}]+}", filler);
    }

    private Integer readCode(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JsonPath.read(body, "$.code");
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Stream<RequestMappingInfo> matchingInfos(ApiSurfaceTest.Endpoint endpoint) {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getPatternValues().contains(endpoint.pattern()))
                .filter(info -> info.getMethodsCondition().getMethods()
                        .contains(RequestMethod.valueOf(endpoint.method())));
    }

    private boolean hasRequestBody(ApiSurfaceTest.Endpoint endpoint) {
        return matchingInfos(endpoint)
                .flatMap(info -> Stream.of(handlerMapping.getHandlerMethods().get(info)))
                .anyMatch(handler -> Stream.of(handler.getMethod().getParameters())
                        .anyMatch(parameter -> parameter.isAnnotationPresent(RequestBody.class)));
    }

    private boolean consumesMultipart(ApiSurfaceTest.Endpoint endpoint) {
        return matchingInfos(endpoint)
                .flatMap(info -> info.getConsumesCondition().getConsumableMediaTypes().stream())
                .anyMatch(mediaType -> MediaType.MULTIPART_FORM_DATA.includes(mediaType)
                        || mediaType.isCompatibleWith(MediaType.MULTIPART_FORM_DATA));
    }
}
