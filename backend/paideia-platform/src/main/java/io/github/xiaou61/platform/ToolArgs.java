package io.github.xiaou61.platform;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * AI 工具入参的读取小工具（WORK-062 收敛）。
 *
 * <p>全仓原有 12 份 `stringOf`、7 份 `longOf`、12 份 `messageOf`、2 份 `instantOf` 与 4 处分页
 * 口径**逐字相同**的拷贝，散在各模块的 `internal/tools` 里。工具类都已依赖 platform，
 * 落点选这里不改变任何模块边界。
 *
 * <p><b>刻意不合并的三族</b>（它们在"看起来一样"之下语义不同）：
 * <ul>
 *   <li>{@code AskTool}/{@code ResumeTools} 的窄 {@code longOf}：只认 Number，不解析数字字符串
 *       ——合并会让入参被悄悄放宽；</li>
 *   <li>{@code OpsAdminTools} 的 `stringArg/intArg`：带 fallback 语义，是另一族；</li>
 *   <li>{@code OpsDiagnosisService.messageOf}：还要按 1000 字截断，语义不同。</li>
 * </ul>
 */
public final class ToolArgs {

    /** 工具页大小上限：与三处工具原口径一致（100）。**PageQuery 自己的上限是 200，别混**。 */
    public static final int MAX_SIZE = 100;

    public static final int DEFAULT_SIZE = 20;

    private ToolArgs() {
    }

    /** 取字符串：null 或全空白 → null。 */
    public static String stringOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** 取长整数：Number 直接取；数字字符串解析；其余（含解析失败）→ null。 */
    public static Long longOf(Map<String, Object> arguments, String key) {
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

    /** 取整数：走 {@link #longOf} 后收窄；唯一调用方是分类树（`0` 另有 zeroAsNull 语义，留在原地）。 */
    public static Integer intOf(Map<String, Object> arguments, String key) {
        Long value = longOf(arguments, key);
        return value == null ? null : value.intValue();
    }

    /** 取时间：ISO-8601；给了但解析不了**抛错**（不静默降级成 null——那会把"格式错"读成"没给"）。 */
    public static Instant instantOf(Map<String, Object> arguments, String key) {
        String text = stringOf(arguments, key);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("时间格式应为 ISO-8601：" + text);
        }
    }

    /** 失败转文案：异常没带 message 时退回类名（工具结果必须永远有可读文本）。 */
    public static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /** 工具分页：page 至少 1；size 默认 20、夹在 1..{@link #MAX_SIZE}。 */
    public static PageQuery pageQueryOf(Map<String, Object> arguments) {
        return PageQuery.of(pageOf(arguments), sizeOf(arguments), null);
    }

    /** 工具分页页码（0/负数/null → 1）。 */
    public static int pageOf(Map<String, Object> arguments) {
        Long page = longOf(arguments, "page");
        return page == null || page < 1 ? 1 : (int) Math.min(page, Integer.MAX_VALUE);
    }

    /** 工具分页页大小（null → 20；夹在 1..100）。 */
    public static int sizeOf(Map<String, Object> arguments) {
        Long size = longOf(arguments, "size");
        if (size == null) {
            return DEFAULT_SIZE;
        }
        return (int) Math.min(Math.max(size, 1), MAX_SIZE);
    }

    /** HTTP 参数版页大小（管理端账号列表用）：null → 20；夹在 1..100。 */
    public static int clampedSize(Integer size) {
        return size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
    }
}