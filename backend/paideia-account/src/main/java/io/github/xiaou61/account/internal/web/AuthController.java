package io.github.xiaou61.account.internal.web;

import io.github.xiaou61.account.internal.credential.CaptchaService;
import io.github.xiaou61.account.internal.credential.EmailCodeService;
import io.github.xiaou61.account.internal.login.LoginService;
import io.github.xiaou61.account.internal.passwordreset.PasswordResetService;
import io.github.xiaou61.account.internal.registration.RegistrationService;
import io.github.xiaou61.account.internal.token.AuthTokens;
import io.github.xiaou61.account.internal.token.TokenService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 凭据交换端点。
 *
 * <p><b>本控制器下的所有路径都在 {@code /api/v1/auth/**} 前缀下，而该前缀整体放行</b>
 * （见 {@code SecurityConfiguration} 的类注释）。因此这里只允许放"用凭据换令牌"或
 * "令牌自身操作"的端点——**任何读取业务数据的接口都不能挂在这个前缀下**。
 *
 * <p>原来的 {@code POST /api/v1/auth/token}（不校验凭据、可为任意主体签发）**已删除**：
 * 有了真实登录之后连开发期都不再需要它，一个不该存在的东西留着就是隐患。
 *
 * <p>所有响应走既有的 {@code ApiResponse} 包装（由 {@code ApiResponseBodyAdvice} 统一处理），
 * 因此没有 204：动作类端点的成功响应是 `code: 0` 且 `data` 为空。
 */
@ApiAccess(ApiAccess.Access.PUBLIC_WRITE)
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final CaptchaService captchaService;
    private final EmailCodeService emailCodeService;
    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final TokenService tokenService;
    private final PasswordResetService passwordResetService;

    AuthController(CaptchaService captchaService,
                   EmailCodeService emailCodeService,
                   RegistrationService registrationService,
                   LoginService loginService,
                   TokenService tokenService,
                   PasswordResetService passwordResetService) {
        this.captchaService = captchaService;
        this.emailCodeService = emailCodeService;
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.tokenService = tokenService;
        this.passwordResetService = passwordResetService;
    }

    /** 下发一张图形验证码。它是"发邮箱验证码"的前置，不保护登录。 */
    @PostMapping("/captcha")
    ApiResponse<CaptchaResponse> captcha(HttpServletRequest request) {
        CaptchaService.Challenge challenge = captchaService.issue(request.getRemoteAddr());
        return ApiResponse.ok(new CaptchaResponse(
                challenge.captchaId(), challenge.imageBase64(), challenge.expiresAt().toString()));
    }

    /** 发送注册用的邮箱验证码。必须先通过图形验证码。 */
    @PostMapping("/email-code")
    ApiResponse<Void> emailCode(@Valid @RequestBody EmailCodeRequest body, HttpServletRequest request) {
        emailCodeService.send(body.email(), body.captchaId(), body.captchaAnswer(), request.getRemoteAddr());
        return ApiResponse.ok(null);
    }

    @PostMapping("/register")
    ApiResponse<AuthTokens> register(@Valid @RequestBody RegisterRequest body) {
        return ApiResponse.ok(
                registrationService.register(body.username(), body.email(), body.password(), body.code()));
    }

    @PostMapping("/login")
    ApiResponse<AuthTokens> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return ApiResponse.ok(
                loginService.login(body.identifier(), body.password(), request.getRemoteAddr()));
    }

    /** 用 refresh 换一组新令牌。凭据就是 refresh 本身，因此这个端点也是放行的。 */
    @PostMapping("/refresh")
    ApiResponse<AuthTokens> refresh(@Valid @RequestBody RefreshRequest body) {
        return ApiResponse.ok(tokenService.rotate(body.refreshToken()));
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest body) {
        tokenService.revoke(body.refreshToken());
        return ApiResponse.ok(null);
    }

    /**
     * 忘记密码：把一条一次性重置链接发到邮箱。
     *
     * <p>邮箱不存在时同样返回成功——这个端点的返回值不该能回答"这个邮箱有没有账号"。
     */
    @PostMapping("/password-reset/request")
    ApiResponse<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest body,
                                           HttpServletRequest request) {
        passwordResetService.request(body.email(), body.captchaId(), body.captchaAnswer(),
                request.getRemoteAddr(), baseUrl(request));
        return ApiResponse.ok(null);
    }

    /** 用邮件链接里的令牌设置新口令。成功即吊销该账号全部旧会话。 */
    @PostMapping("/password-reset/confirm")
    ApiResponse<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest body) {
        passwordResetService.confirm(body.token(), body.password());
        return ApiResponse.ok(null);
    }

    /**
     * 当前请求的对外基地址，仅在没配 {@code paideia.account.web.base-url} 时作为链接兜底。
     * 取自请求的 Host 头，因此它在可以被伪造的前提下一律不该被采用。
     */
    private static String baseUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
    }

    record CaptchaResponse(String captchaId, String imageBase64, String expiresAt) {
    }

    record EmailCodeRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 64) String captchaId,
            @NotBlank @Size(max = 16) String captchaAnswer) {
    }

    record RegisterRequest(
            // 允许 Unicode 字母（中文用户名）与数字、下划线、连字符
            @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[\\p{L}\\p{N}_-]+$",
                    message = "只能包含字母、数字、下划线或连字符") String username,
            @NotBlank @Email @Size(max = 254) String email,
            // 上限 72 是 BCrypt 的输入上限：更长的口令会被静默截断，不如当场拒绝
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(min = 4, max = 12) String code) {
    }

    record LoginRequest(
            @NotBlank @Size(max = 254) String identifier,
            @NotBlank @Size(max = 72) String password) {
    }

    record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
    }

    record PasswordResetRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 64) String captchaId,
            @NotBlank @Size(max = 16) String captchaAnswer) {
    }

    record PasswordResetConfirmRequest(
            // 32 字节的 base64url 是 43 个字符；上限放到 128 只为了不因为编码变化而误拒
            @NotBlank @Size(max = 128) String token,
            // 与注册同一套约束：上限 72 是 BCrypt 的输入上限
            @NotBlank @Size(min = 8, max = 72) String password) {
    }
}
