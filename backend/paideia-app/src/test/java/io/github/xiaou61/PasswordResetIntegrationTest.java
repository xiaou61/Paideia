package io.github.xiaou61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.xiaou61.account.internal.credential.CaptchaImageRenderer;
import io.github.xiaou61.account.internal.mail.InMemoryMailPort;
import io.github.xiaou61.account.internal.support.Digest;
import io.github.xiaou61.account.internal.support.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 忘记密码 / 重置口令的端到端集成测试。
 *
 * <p>与 {@code AccountAuthIntegrationTest} 同一套装配：完整上下文、真实安全链、真实 MySQL；
 * 图形验证码的出图器被钉成固定答案，邮件端口换成内存信箱，因此**重置链接可以从测试里直接读出来**，
 * 全链路不依赖真实 SMTP。
 *
 * <p>守的几条不变量：链接一次性、会过期、重新申请会让旧链接失效、改口令会把旧会话踢下线、
 * 以及"邮箱不存在"不能从这个接口的返回值里看出来。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class PasswordResetIntegrationTest {

    private static final String CAPTCHA_CODE = "ABCD";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "a-brand-new-passphrase";

    @Autowired
    MockMvc mvc;

    @Autowired
    InMemoryMailPort mailbox;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** 为了让"重新申请作废旧链接"这条不必真等 60 秒，直接清掉那条限流记录。 */
    @Autowired
    RateLimiter rateLimiter;

    @MockitoBean
    CaptchaImageRenderer captchaImageRenderer;

    private String sourceAddress;

    @BeforeEach
    void setUp() {
        when(captchaImageRenderer.randomCode(anyInt())).thenReturn(CAPTCHA_CODE);
        when(captchaImageRenderer.renderPng(anyString())).thenReturn(new byte[] {1, 2, 3});
        mailbox.clear();
        sourceAddress = "10.1." + ThreadLocalRandom.current().nextInt(1, 250)
                + "." + ThreadLocalRandom.current().nextInt(1, 250);
    }

    @Test
    @DisplayName("未知邮箱也返回成功，且不会发出任何邮件（不能据此枚举账号）")
    void unknownEmailLooksLikeSuccessAndSendsNothing() throws Exception {
        String email = uniqueEmail("ghost");

        String response = requestReset(email);

        assertThat(codeOf(response)).as("未知邮箱不该报错：%s", response).isZero();
        assertThat(mailbox.latestPasswordResetFor(email)).as("给未注册的邮箱发了重置邮件").isEmpty();
    }

    @Test
    @DisplayName("没有有效图形验证码时不发重置邮件")
    void doesNotSendWithoutValidCaptcha() throws Exception {
        String email = uniqueEmail("no-captcha");
        registerNewAccount(unique("no-captcha"), email);

        String response = postJson("/api/v1/auth/password-reset/request", """
                {"email":"%s","captchaId":"not-a-real-id","captchaAnswer":"ABCD"}
                """.formatted(email));

        assertThat(codeOf(response)).isEqualTo(40000);
        assertThat(mailbox.latestPasswordResetFor(email)).as("没过图形验证码就发了信").isEmpty();
    }

    @Test
    @DisplayName("拿到链接即可改口令：旧口令失效、新口令可登录、链接只能用一次")
    void resetsPasswordWithEmailedLink() throws Exception {
        String username = unique("reset");
        String email = uniqueEmail("reset");
        registerNewAccount(username, email);

        String link = requestResetAndReadLink(email);
        String token = tokenOf(link);

        String confirmed = confirm(token, NEW_PASSWORD);
        assertThat(codeOf(confirmed)).as("重置失败：%s", confirmed).isZero();

        // 旧口令不能再登录，新口令可以
        assertThat(codeOf(login(username, PASSWORD))).isEqualTo(40100);
        assertThat(codeOf(login(username, NEW_PASSWORD))).isZero();

        // 同一枚令牌再来一次：必须失败（一次性）
        String replay = confirm(token, "yet-another-passphrase");
        assertThat(codeOf(replay)).as("链接被重复使用了：%s", replay).isEqualTo(40000);
        assertThat(messageOf(replay)).contains("无效或已过期");
    }

    @Test
    @DisplayName("重新申请会让上一封邮件里的链接失效")
    void newRequestInvalidatesTheOldLink() throws Exception {
        String email = uniqueEmail("supersede");
        registerNewAccount(unique("supersede"), email);

        String first = tokenOf(requestResetAndReadLink(email));
        // 限流键与发信服务内部一致；清掉它只是省掉 60 秒等待，不改变被测逻辑
        rateLimiter.clear("password-reset:" + Digest.sha256Hex(email.toLowerCase(Locale.ROOT)));
        String second = tokenOf(requestResetAndReadLink(email));

        assertThat(codeOf(confirm(first, NEW_PASSWORD)))
                .as("旧链接没有被作废")
                .isEqualTo(40000);
        assertThat(codeOf(confirm(second, NEW_PASSWORD))).isZero();
    }

    @Test
    @DisplayName("过期的链接不可用")
    void expiredLinkIsRejected() throws Exception {
        String email = uniqueEmail("expired");
        registerNewAccount(unique("expired"), email);
        long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);

        String rawToken = "expired-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """, userId, Digest.sha256Hex(rawToken),
                Timestamp.from(Instant.now().minusSeconds(60)));

        String response = confirm(rawToken, NEW_PASSWORD);

        assertThat(codeOf(response)).isEqualTo(40000);
        assertThat(messageOf(response)).contains("无效或已过期");
    }

    @Test
    @DisplayName("改口令会吊销该账号全部旧会话（旧 refresh 立刻换不出新令牌）")
    void resetRevokesExistingSessions() throws Exception {
        String username = unique("kick");
        String email = uniqueEmail("kick");
        String registered = registerNewAccount(username, email);
        String oldRefresh = stringAt(registered, "$.data.refreshToken");

        String token = tokenOf(requestResetAndReadLink(email));
        assertThat(codeOf(confirm(token, NEW_PASSWORD))).isZero();

        String afterReset = postJson("/api/v1/auth/refresh", """
                {"refreshToken":"%s"}
                """.formatted(oldRefresh));
        assertThat(codeOf(afterReset)).as("改口令后旧会话仍然有效：%s", afterReset).isEqualTo(40100);
    }

    @Test
    @DisplayName("太短的新口令被拒，且不消耗链接")
    void rejectsTooShortPasswordWithoutBurningTheToken() throws Exception {
        String username = unique("short");
        String email = uniqueEmail("short");
        registerNewAccount(username, email);
        String token = tokenOf(requestResetAndReadLink(email));

        String rejected = confirm(token, "short");
        assertThat(codeOf(rejected)).isEqualTo(40000);

        // 校验失败发生在消费之前：同一个链接还能用来设一个合法口令
        assertThat(codeOf(confirm(token, NEW_PASSWORD))).isZero();
        assertThat(codeOf(login(username, NEW_PASSWORD))).isZero();
    }

    // ---------------------------------------------------------------- 辅助

    private String requestReset(String email) throws Exception {
        return postJson("/api/v1/auth/password-reset/request", """
                {"email":"%s","captchaId":"%s","captchaAnswer":"%s"}
                """.formatted(email, freshCaptchaId(), CAPTCHA_CODE));
    }

    private String requestResetAndReadLink(String email) throws Exception {
        String response = requestReset(email);
        assertThat(codeOf(response)).as("申请重置失败：%s", response).isZero();
        return mailbox.latestPasswordResetFor(email)
                .orElseThrow(() -> new AssertionError("没有收到重置邮件：" + email));
    }

    private static String tokenOf(String link) {
        int index = link.indexOf("token=");
        assertThat(index).as("重置链接里没有 token 参数：%s", link).isGreaterThan(-1);
        return link.substring(index + "token=".length());
    }

    private String confirm(String token, String password) throws Exception {
        return postJson("/api/v1/auth/password-reset/confirm", """
                {"token":"%s","password":"%s"}
                """.formatted(token, password));
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniqueEmail(String prefix) {
        return unique(prefix) + "@example.test";
    }

    private String freshCaptchaId() throws Exception {
        String body = postJson("/api/v1/auth/captcha", "{}");
        return stringAt(body, "$.data.captchaId");
    }

    private String issueEmailCode(String email) throws Exception {
        String response = postJson("/api/v1/auth/email-code", """
                {"email":"%s","captchaId":"%s","captchaAnswer":"%s"}
                """.formatted(email, freshCaptchaId(), CAPTCHA_CODE));
        assertThat(codeOf(response)).as("发信失败：%s", response).isZero();
        return mailbox.latestCodeFor(email).orElseThrow();
    }

    private String registerNewAccount(String username, String email) throws Exception {
        String code = issueEmailCode(email);
        String response = postJson("/api/v1/auth/register", """
                {"username":"%s","email":"%s","password":"%s","code":"%s"}
                """.formatted(username, email, PASSWORD, code));
        assertThat(codeOf(response)).as("注册失败：%s", response).isZero();
        return response;
    }

    private String login(String identifier, String password) throws Exception {
        return postJson("/api/v1/auth/login", """
                {"identifier":"%s","password":"%s"}
                """.formatted(identifier, password));
    }

    private String postJson(String path, String payload) throws Exception {
        MvcResult result = mvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .with(request -> {
                    request.setRemoteAddr(sourceAddress);
                    return request;
                })).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int codeOf(String body) {
        return ((Number) JsonPath.read(body, "$.code")).intValue();
    }

    private static String messageOf(String body) {
        return JsonPath.read(body, "$.message").toString();
    }

    private static String stringAt(String body, String path) {
        Object value = JsonPath.read(body, path);
        return value instanceof String text ? text : String.valueOf(value);
    }
}
