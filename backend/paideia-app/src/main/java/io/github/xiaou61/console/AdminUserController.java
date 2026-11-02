package io.github.xiaou61.console;

import io.github.xiaou61.account.AccountAdminApi;
import io.github.xiaou61.ai.AiAdminApi;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端的用户管理（WORK-030）。
 *
 * <p><b>为什么这个控制器在 `paideia-app` 而不是某个业务模块</b>：它要同时读账号模块与 AI 模块，
 * 而 `paideia-ai` 不依赖 `paideia-account`（反过来也不该成立）。只有 `paideia-app` 同时看得见
 * 两者——与既有的 `ops` 包同一先例。
 *
 * <p><b>权限靠路径规则</b>：`/api/v1/account/admin/**` → `hasRole("ADMIN")`，写在
 * `SecurityConfiguration` 里且排在更宽的规则之前。本类不逐方法加注解（与其它管理端控制器一致）。
 *
 * <p><b>不可自伤在服务端判</b>（REQ-010）：改角色、删号、调积分都拒绝操作自己。
 * 前端把按钮变灰只是体验，挡不住直接调接口。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/account/admin/users")
class AdminUserController {

    /** 每页上限。`PageQuery` 自己有 200 的上限，这里再收一道业务上限。 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AccountAdminApi accounts;
    private final AiAdminApi ai;

    AdminUserController(AccountAdminApi accounts, AiAdminApi ai) {
        this.accounts = accounts;
        this.ai = ai;
    }

    /** 列表的一行：账号本身 + 四个用量指标。 */
    record UserRow(long id, String username, String email, String role, String createdAt,
                   long balance, long usedCredits, long runCount, String lastActiveAt) {
    }

    record UserPage(long total, int page, int size, List<UserRow> items) {
    }

    /**
     * 分页列表。
     *
     * <p>目前只按注册时间倒序。按余额/调用次数排行是另一条路径（要先在 AI 侧排出 id 序列再取
     * 账号），等真有规模再加——现在只有两个账号，加它是给不存在的规模做优化。
     */
    @GetMapping
    ApiResponse<UserPage> list(@RequestParam(name = "query", required = false) String query,
                               @RequestParam(name = "role", required = false) String role,
                               @RequestParam(name = "page", required = false) Integer page,
                               @RequestParam(name = "size", required = false) Integer size) {
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var result = accounts.list(query, role, page == null ? 1 : page, pageSize);
        return ApiResponse.ok(new UserPage(result.total(), result.page(), result.size(),
                enrich(result.items())));
    }

    /** 单个用户：账号 + 用量。 */
    @GetMapping("/{id}")
    ApiResponse<UserRow> detail(@PathVariable("id") long id) {
        AccountAdminApi.AdminUser user = accounts.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "账号不存在"));
        return ApiResponse.ok(enrich(List.of(user)).get(0));
    }

    record RoleRequest(String role) {
    }

    /** 改角色。成功即吊销该用户全部 refresh token（U-18）。 */
    @PostMapping("/{id}/role")
    ApiResponse<UserRow> changeRole(@PathVariable("id") long id, @RequestBody RoleRequest request) {
        accounts.changeRole(id, request == null ? null : request.role(), operatorId());
        return detail(id);
    }

    record CreditsRequest(long delta, String note) {
    }

    /**
     * 调积分。**必须填原因**：这是不可撤销的动作，流水要能回答"为什么给他发"。
     *
     * <p>落库走既有的 `CreditService.adjust`（经 {@link AiAdminApi}），这里不重复实现计费逻辑。
     */
    @PostMapping("/{id}/credits")
    ApiResponse<Map<String, Object>> adjustCredits(@PathVariable("id") long id,
                                                   @RequestBody CreditsRequest request) {
        long operator = operatorId();
        if (id == operator) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不能调整自己的积分");
        }
        if (request == null || request.note() == null || request.note().isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "调整积分必须写明原因");
        }
        accounts.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "账号不存在"));
        long balance = ai.adjust(id, request.delta(), operator, request.note().trim());
        return ApiResponse.ok(Map.of("userId", id, "balance", balance));
    }

    /** 管理员删号：不校验对方密码，级联与自助注销共用（见 `AccountDeletionService`）。 */
    @DeleteMapping("/{id}")
    ApiResponse<Boolean> delete(@PathVariable("id") long id) {
        accounts.deleteByAdmin(id, operatorId());
        return ApiResponse.ok(true);
    }

    /**
     * 当前操作者的账号 id。
     *
     * <p>令牌的 `sub` 是账号主键的字符串形式；非数字只可能来自伪造或版本错配的令牌，
     * 按未认证处理而不是让它冒成 500。与 `AiRequester.require()` 同一口径。
     */
    private static long operatorId() {
        return CurrentUser.userId();
    }

    /**
     * 把账号与用量拼起来。
     *
     * <p>一次批量查用量（`IN (...)`），不是每行查一次：20 行 × 4 指标 = 80 次往返。
     */
    private List<UserRow> enrich(List<AccountAdminApi.AdminUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, AiAdminApi.UserUsage> usage = ai.usage(users.stream()
                        .map(AccountAdminApi.AdminUser::id).toList())
                .stream()
                .collect(Collectors.toMap(AiAdminApi.UserUsage::userId, Function.identity()));
        return users.stream().map(user -> {
            AiAdminApi.UserUsage stats = usage.get(user.id());
            return new UserRow(user.id(), user.username(), user.email(), user.role(), user.createdAt(),
                    stats == null ? 0L : stats.balance(),
                    stats == null ? 0L : stats.usedCredits(),
                    stats == null ? 0L : stats.runCount(),
                    stats == null ? null : stats.lastActiveAt());
        }).toList();
    }
}
