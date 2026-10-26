package io.github.xiaou61.platform;

/**
 * 工具的静态作用域。
 *
 * <p>字符串值直接进数据库（PAT 的 `scopes` 列）与外部 token，**改名等于让已发出的凭据失配**。
 * 因此这里只有三个值，且都短、稳定。
 *
 * <p><b>角色与 scope 是两件事</b>：scope 说"这张凭据允许做什么"，角色说"这个账号本身是什么"。
 * 管理类工具要求二者同时满足——只给一个 `admin` scope 不能把学习者变成管理员。
 */
public enum ToolScope {

    /** 读取（含检索、列目录、读自己的数据）。默认视图只需要它。 */
    READ("read"),

    /** 写入（创建、修改、追加）。默认视图**不含**它。 */
    WRITE("write"),

    /**
     * 管理类能力（WORK-038 起由 7 个 `admin_*` 工具组使用）；必须同时是 ADMIN 角色。
     *
     * <p><b>管理写工具也声明它，不要声明 WRITE</b>：{@link ToolCatalog#view} 的角色过滤只作用于
     * ADMIN scope；改成 WRITE 会让非管理员仅凭「admin 工具组 + write」就看见并调用管理写工具。
     */
    ADMIN("admin");

    private final String key;

    ToolScope(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** 库里/请求头里来的字符串 → 枚举；不认识的值返回 null（调用方据此拒绝，不静默放行）。 */
    public static ToolScope of(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (ToolScope scope : values()) {
            if (scope.key.equals(normalized)) {
                return scope;
            }
        }
        return null;
    }
}
