package io.github.xiaou61.ai.internal.digest;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 每日速递的管理工具组（WORK-038 TASK-006，2 个）。
 *
 * <p>删除是硬删：抓来的公开新闻没有审计价值。删掉之后同一天重跑**会**再抓回来
 * （指纹已不在库里），要做永久屏蔽得另做黑名单，一期不做——与 `DigestAdminController`
 * 同一条已知行为。
 */
public final class DigestAdminTools {

    private static final String GROUP = "admin_digest";

    private DigestAdminTools() {
    }

    /** 当日（或指定日）条目。 */
    @Component
    public static class ListItems implements PaideiaTool {

        private final DigestMapper mapper;
        private final DigestProperties properties;

        ListItems(DigestMapper mapper, DigestProperties properties) {
            this.mapper = mapper;
            this.properties = properties;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_digest_list_items", GROUP, "列出某天的速递条目",
                    "列出某一天的速递条目（含指纹与摘要状态），用于排查「为什么这条进来了」。"
                            + "不传日期按业务时区的今天。",
                    schema(Map.of("date", str("日期 YYYY-MM-DD；不传取今天"))),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                String raw = stringOf(arguments, "date");
                LocalDate date;
                try {
                    date = raw == null ? properties.today() : LocalDate.parse(raw);
                } catch (DateTimeParseException failure) {
                    return ToolResult.fail("日期格式应为 YYYY-MM-DD");
                }
                List<DigestMapper.Row> rows = mapper.findByDate(date);
                if (rows.isEmpty()) {
                    return ToolResult.ok(date + " 没有速递条目。");
                }
                List<String> lines = rows.stream()
                        .map(row -> "id=" + row.id() + " [" + row.groupKey() + "/" + row.sourceName() + "] "
                                + row.title() + " 热度=" + (row.heat() == null ? "—" : row.heat())
                                + " 摘要=" + row.summaryState() + " " + row.url())
                        .toList();
                return ToolResult.ok(date + " 共 " + rows.size() + " 条：\n" + join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取速递失败：" + messageOf(failure));
            }
        }
    }

    /** 删除单条。 */
    @Component
    public static class DeleteItem implements PaideiaTool {

        private final DigestMapper mapper;

        DeleteItem(DigestMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_digest_delete_item", GROUP, "删除速递条目",
                    "**不可撤销**：硬删这一条。同一天重跑抓取会把它再抓回来（指纹已不在库里）。",
                    schema(Map.of("id", num("速递条目 id")), "id"),
                    null, false, true, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long id = longOf(arguments, "id");
                if (id == null) {
                    return ToolResult.fail("缺少 id");
                }
                if (mapper.deleteById(id) == 0) {
                    return ToolResult.fail("这条速递不存在：id=" + id);
                }
                return ToolResult.ok("已删除速递条目 id=" + id);
            } catch (RuntimeException failure) {
                return ToolResult.fail("删除速递失败：" + messageOf(failure));
            }
        }
    }

    // --------------------------------------------------------------- helpers

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

    static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
