package io.github.xiaou61.account.internal.web;

import io.github.xiaou61.account.Identity;
import io.github.xiaou61.account.LearnerProfile;
import io.github.xiaou61.account.internal.profile.LearnerProfileService;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.github.xiaou61.platform.ApiAccess;

/**
 * 身份与画像端点（WORK-010 T-03）。
 *
 * <p><b>它在 {@code /api/v1/account/**} 之下，不是 {@code /admin/}</b>：因此只需"已登录"，
 * 由 {@code anyRequest().authenticated()} 兜住。**权限靠数据过滤而不是路径**：路径与请求体里
 * 都没有用户标识，归属一律取自令牌。
 *
 * <p><b>这里写任何东西都不会改变权限</b>（AC-001）：不碰 `users.role`，也不碰任何角色判定。
 * 身份只影响推荐与助手形态。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/account/profile")
class LearnerProfileController {

    private final LearnerProfileService profiles;

    LearnerProfileController(LearnerProfileService profiles) {
        this.profiles = profiles;
    }

    /** 界面的形状。枚举以 `key` 出（小写蛇形），与库里一致，前端不必认识 Java 枚举名。 */
    record ProfileView(String identity, String identityLabel, String targetRole, String targetCity,
                       String timeline, String stage, List<String> selectableIdentities) {
    }

    /** `PUT` 的请求体。四列画像都可空——清空输入框等于"没有这个值"。 */
    record UpdateRequest(String identity, String targetRole, String targetCity,
                         String timeline, String stage) {
    }

    @GetMapping
    ApiResponse<ProfileView> get() {
        return ApiResponse.ok(view(profiles.get(requireUserId())));
    }

    /**
     * 整行覆盖式保存。
     *
     * <p>`identity` 只能传**可选的**枚举值（一期只有 `job_seeker`）：留位的身份不做界面，
     * 也不许通过接口写进来，否则会出现"库里是考研、界面上根本没有这个身份"的幽灵状态。
     * 这一条在服务层也挡了一次，是刻意的双重防护——接口层给可读的错误，服务层守不变量。
     */
    @PutMapping
    ApiResponse<ProfileView> update(@RequestBody(required = false) UpdateRequest request) {
        long userId = requireUserId();
        UpdateRequest body = request == null ? new UpdateRequest(null, null, null, null, null) : request;
        LearnerProfile saved = profiles.save(userId, parseIdentity(body.identity()),
                body.targetRole(), body.targetCity(), body.timeline(), body.stage());
        return ApiResponse.ok(view(saved));
    }

    private static ProfileView view(LearnerProfile profile) {
        return new ProfileView(profile.identity().key(), profile.identity().label(),
                profile.targetRole(), profile.targetCity(), profile.timeline(), profile.stage(),
                Arrays.stream(Identity.values()).filter(Identity::selectable).map(Identity::key).toList());
    }

    /**
     * 身份字符串 → 枚举。
     *
     * <p>**空串当作"用默认身份"**（前端第一次保存画像时可能只填了岗位、没动身份）；
     * **不认识的字符串直接拒绝**，不静默退回默认——否则用户以为自己选了身份，实际没选上。
     */
    private static Identity parseIdentity(String raw) {
        if (raw == null || raw.isBlank()) {
            return Identity.JOB_SEEKER;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(Identity.values())
                .filter(identity -> identity.key().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT, "不认识的身份：" + raw));
    }

    private static long requireUserId() {
        return CurrentUser.userId();
    }
}
