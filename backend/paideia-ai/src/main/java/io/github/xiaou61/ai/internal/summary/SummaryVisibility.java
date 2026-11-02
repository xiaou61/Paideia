package io.github.xiaou61.ai.internal.summary;

/**
 * 一次精简结果的可见性，**由生成者在生成时选择**（用户裁决）。
 *
 * <p>语义只有一条约束：{@link #PRIVATE} 的结果**只有生成者本人**能读到；{@link #PUBLIC}
 * 的结果任何登录用户都能复用。消费者的"只看我的"只是客户端显示偏好，不参与权限判断。
 */
public enum SummaryVisibility {

    PUBLIC,
    PRIVATE;

    /** 把请求里的字符串转成枚举；不认识的值按不合法参数处理，不静默降级成默认值。 */
    public static SummaryVisibility parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "public" -> PUBLIC;
            case "private" -> PRIVATE;
            default -> null;
        };
    }

    public String wireName() {
        return name().toLowerCase();
    }
}
