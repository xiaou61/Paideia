package io.github.xiaou61.console;

import io.github.xiaou61.account.AccountAdminApi;
import io.github.xiaou61.ai.AiAdminApi;
import io.github.xiaou61.platform.PageResult;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 账号与积分的管理工具组（WORK-039 TASK-003，6 个）。
 *
 * <p><b>为什么放在 `paideia-app` 的 console 包</b>：用户列表要同时读账号与 AI 用量，
 * 而 `account` 与 `ai` 互不依赖，只有装配根同时看得见两者——与 `AdminUserController` 同一先例。
 *
 * <p><b>不可自伤在工具层再判一次</b>：调积分、删号都拒绝操作自己。管理端页面把按钮变灰只是体验，
 * 工具是第二条入口，必须自己挡住。
 */
final class AdminUserTools {

    private static final String GROUP = "admin_users";
    private static final int MAX_PAGE_SIZE = 100;

    private AdminUserTools() {
    }

    /** 分页查账号，附带余额、已用积分、调用次数与最近活跃。 */
    @Component
    public static class ListUsers implements PaideiaTool {

        private final AccountAdminApi accounts;
        private final AiAdminApi ai;

        ListUsers(AccountAdminApi accounts, AiAdminApi ai) {
            this.accounts = accounts;
            this.ai = ai;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_users_list", GROUP, "列出账号",
                    "分页列出账号，附带余额、已用积分、AI 调用次数与最近活跃时间。"
                            + "query 按用户名或邮箱模糊匹配，role 可传 admin 或 learner。",
                    schema(Map.of(
                            "query", str("用户名或邮箱片段；不传不过滤"),
                            "role", str("admin / learner；不传不过滤"),
                            "page", num("页码，从 1 开始"),
                            "size", num("每页条数，1-100，默认 20"))),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                PageResult<AccountAdminApi.AdminUser> page = accounts.list(
                        stringOf(arguments, "query"), stringOf(arguments, "role"),
                        pageOf(arguments), sizeOf(arguments));
                if (page.items().isEmpty()) {
                    return ToolResult.ok("第 " + page.page() + " 页没有账号（共 " + page.total() + " 个）。");
                }
                return ToolResult.ok("共 " + page.total() + " 个账号，第 " + page.page() + " 页：\n"
                        + join(enrich(page.items(), ai)));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取账号失败：" + messageOf(failure));
            }
        }
    }

    /** 单个账号详情。 */
    @Component
    public static class Get implements PaideiaTool {

        private final AccountAdminApi accounts;
        private final AiAdminApi ai;

        Get(AccountAdminApi accounts, AiAdminApi ai) {
            this.accounts = accounts;
            this.ai = ai;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_users_get", GROUP, "读取账号详情",
                    "按 id 读一个账号：用户名、邮箱、角色、注册时间，以及余额、已用积分、"
                            + "AI 调用次数与最近活跃时间。",
                    schema(Map.of("targetUserId", num("要查看的账号 id")), "targetUserId"),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long target = longOf(arguments, "targetUserId");
                if (target == null) {
                    return ToolResult.fail("缺少 targetUserId");
                }
                Optional<AccountAdminApi.AdminUser> user = accounts.findById(target);
                if (user.isEmpty()) {
                    return ToolResult.fail("账号不存在：id=" + target);
                }
                return ToolResult.ok(join(enrich(List.of(user.get()), ai)));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取账号失败：" + messageOf(failure));
            }
        }
    }

    /** 积分流水。 */
    @Component
    public static class CreditLedger implements PaideiaTool {

        private final AiAdminApi ai;

        CreditLedger(AiAdminApi ai) {
            this.ai = ai;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_users_credit_ledger", GROUP, "查看积分流水",
                    "分页查看积分流水（发放、扣费、退款、调账）。可按账号、种类与时间区间筛选。"
                            + "kind 常见值：ADJUST / RESERVE / SETTLE / REFUND。",
                    schema(Map.of(
                            "targetUserId", num("只看某个账号；不传看全部"),
                            "kind", str("流水种类；不传不过滤"),
                            "from", str("起始时间，ISO-8601（如 2026-10-01T00:00:00Z）"),
                            "to", str("结束时间，ISO-8601"),
                            "page", num("页码，从 1 开始"),
                            "size", num("每页条数，1-100，默认 20"))),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                PageResult<AiAdminApi.LedgerEntry> page = ai.ledger(
                        longOf(arguments, "targetUserId"), stringOf(arguments, "kind"),
                        instantOf(arguments, "from"), instantOf(arguments, "to"),
                        pageOf(arguments), sizeOf(arguments));
                if (page.items().isEmpty()) {
                    return ToolResult.ok("没有符合条件的流水（共 " + page.total() + " 条）。");
                }
                List<String> lines = page.items().stream()
                        .map(row -> row.createdAt() + " 用户=" + row.userId() + " " + signed(row.delta())
                                + " " + row.kind() + (row.note() == null ? "" : " 备注=" + row.note()))
                        .toList();
                return ToolResult.ok("共 " + page.total() + " 条，第 " + page.page() + " 页：\n" + join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取流水失败：" + messageOf(failure));
            }
        }
    }

    /** 改角色。 */
    @Component
    public static class SetRole implements PaideiaTool {

        private final AccountAdminApi accounts;

        SetRole(AccountAdminApi accounts) {
            this.accounts = accounts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_users_set_role", GROUP, "修改账号角色",
                    "把账号改成 admin 或 learner。**会吊销该账号全部 refresh token**（最长一轮刷新内生效）。"
                            + "不能修改自己的角色。",
                    schema(Map.of(
                            "targetUserId", num("要改的账号 id"),
                            "role", str("admin 或 learner")),
                            "targetUserId", "role"),
                    null, false, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long target = longOf(arguments, "targetUserId");
                String role = stringOf(arguments, "role");
                if (target == null || role == null) {
                    return ToolResult.fail("targetUserId 与 role 必填");
                }
                accounts.changeRole(target, role.toLowerCase(java.util.Locale.ROOT), context.userId());
                return ToolResult.ok("已把账号 " + target + " 的角色改为 " + role);
            } catch (RuntimeException failure) {
                return ToolResult.fail("修改角色失败：" + messageOf(failure));
            }
        }
    }

    /** 调积分。 */
    @Component
    public static class AdjustCredits implements PaideiaTool {

        private final AiAdminApi ai;

        AdjustCredits(AiAdminApi ai) {
            this.ai = ai;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_users_adjust_credits", GROUP, "发放或回收积分",
                    "给某个账号增减积分。正数为发放、负数为回收，**结果不可撤销**，必须写原因。"
                            + "不能调整自己的积分。",
                    schema(Map.of(
                            "targetUserId", num("目标账号 id"),
                            "delta", num("积分数：正数发放，负数回收"),
                            "note", str("原因，会写进流水供审计")),
                            "targetUserId", "delta", "note"),
                    null, false, false, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long target = longOf(arguments, "targetUserId");
                Long delta = longOf(arguments, "delta");
                String note = stringOf(arguments, "note");
                if (target == null || delta == null || note == null) {
                    return ToolResult.fail("targetUserId、delta、note 必填");
                }
                if (delta == 0L) {
                    return ToolResult.fail("delta 不能为 0");
                }
                if (target == context.userId()) {
                    return ToolResult.fail("不能调整自己的积分");
                }
                long balance = ai.adjust(target, delta, context.userId(), note);
                return ToolResult.ok("已调整 " + signed(delta) + "，账号 " + target + " 当前余额 " + balance);
            } catch (RuntimeException failure) {
                return ToolResult.fail("调整积分失败：" + messageOf(failure));
            }
        }
    }

    /** 删号。 */
    @Component
    public static class Delete implements PaideiaTool {

        private final AccountAdminApi accounts;

        Delete(AccountAdminApi accounts) {
            this.accounts = accounts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_users_delete", GROUP, "删除账号",
                    "**不可撤销**。级联删除该账号的学习数据、笔记、AI 会话、记忆与积分流水，"
                            + "与本人注销完全一致。不能删除自己。",
                    schema(Map.of("targetUserId", num("要删除的账号 id")), "targetUserId"),
                    null, false, true, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long target = longOf(arguments, "targetUserId");
                if (target == null) {
                    return ToolResult.fail("缺少 targetUserId");
                }
                accounts.deleteByAdmin(target, context.userId());
                return ToolResult.ok("已删除账号 id=" + target);
            } catch (RuntimeException failure) {
                return ToolResult.fail("删除账号失败：" + messageOf(failure));
            }
        }
    }

    // --------------------------------------------------------------- helpers

    /** 账号 + 用量拼成文本行。一次批量查用量，不是每行查一次。 */
    static List<String> enrich(List<AccountAdminApi.AdminUser> users, AiAdminApi ai) {
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, AiAdminApi.UserUsage> usage = ai.usage(users.stream()
                        .map(AccountAdminApi.AdminUser::id).toList())
                .stream()
                .collect(Collectors.toMap(AiAdminApi.UserUsage::userId, Function.identity()));
        return users.stream().map(user -> {
            AiAdminApi.UserUsage stats = usage.get(user.id());
            return "id=" + user.id() + " 用户名=" + user.username()
                    + " 邮箱=" + (user.email() == null ? "无" : user.email())
                    + " 角色=" + user.role()
                    + " 余额=" + (stats == null ? 0L : stats.balance())
                    + " 已用=" + (stats == null ? 0L : stats.usedCredits())
                    + " 调用=" + (stats == null ? 0L : stats.runCount())
                    + " 最近活跃=" + (stats == null || stats.lastActiveAt() == null
                            ? "从未" : stats.lastActiveAt());
        }).toList();
    }

    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> num(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static String stringOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    static Long longOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Instant instantOf(Map<String, Object> arguments, String key) {
        String text = stringOf(arguments, key);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException failure) {
            throw new IllegalArgumentException("时间格式应为 ISO-8601：" + text);
        }
    }

    static int pageOf(Map<String, Object> arguments) {
        Long page = longOf(arguments, "page");
        return page == null || page < 1 ? 1 : (int) Math.min(page, Integer.MAX_VALUE);
    }

    static int sizeOf(Map<String, Object> arguments) {
        Long size = longOf(arguments, "size");
        if (size == null) {
            return 20;
        }
        return (int) Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    static String signed(long delta) {
        return delta > 0 ? "+" + delta : String.valueOf(delta);
    }

    static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
