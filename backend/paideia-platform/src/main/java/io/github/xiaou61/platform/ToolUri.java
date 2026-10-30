package io.github.xiaou61.platform;

import java.util.Optional;

/**
 * 对象引用 `type:id` 的解析（WORK-058）。
 *
 * <p>前端递过来的附件是一个 URI（`entry:123`、`note:45`、`entry:123/selection`），而工具按数据库
 * 主键取行。这个类只做那一小步转换，规则只有一条：
 *
 * <p><b>只认数字前缀</b>——`entry:123` 与 `entry:123/selection` 都给 123；`level:java/13` 这种
 * 非数字 id 返回空（关卡是合成对象，没有对应的表行）。类型不符、负数、0 也返回空。
 *
 * <p>放 `platform` 而不是各模块各写一份：这是**契约**（URI 长什么样），不是业务。
 */
public final class ToolUri {

    private ToolUri() {
    }

    /**
     * @param uri  形如 `type:id`；null 与空串返回空
     * @param type 期望的类型（与 uri 前缀逐字比较，区分大小写）
     */
    public static Optional<Long> numericId(String uri, String type) {
        if (uri == null || type == null) {
            return Optional.empty();
        }
        String prefix = type + ":";
        if (!uri.startsWith(prefix)) {
            return Optional.empty();
        }
        String id = uri.substring(prefix.length()).trim();
        int end = 0;
        while (end < id.length() && Character.isDigit(id.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return Optional.empty();
        }
        try {
            long value = Long.parseLong(id.substring(0, end));
            return value <= 0 ? Optional.empty() : Optional.of(value);
        } catch (NumberFormatException tooLong) {
            return Optional.empty();
        }
    }
}
