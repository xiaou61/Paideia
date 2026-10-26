package io.github.xiaou61.note.internal.tools;

import io.github.xiaou61.note.internal.share.ShareService;
import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 笔记举报的管理工具组（WORK-038 TASK-005，2 个）。
 *
 * <p>下架是人做的决定：工具只负责"列出"与"下架"，不做自动判定（与 `NoteReportAdminController`
 * 同一条裁决）。下架后链接立刻不可读，且**不可恢复**。
 */
public final class NoteAdminTools {

    private static final String GROUP = "admin_review";

    private NoteAdminTools() {
    }

    /** 举报列表。 */
    @Component
    public static class ListReports implements PaideiaTool {

        private final ShareService shares;

        ListReports(ShareService shares) {
            this.shares = shares;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_review_list_note_reports", GROUP, "列出笔记分享举报",
                    "列出被举报的笔记分享。不传 status 看全部（待处理排在最前）。"
                            + "下架用 admin_review_takedown_note，参数是这里的举报 id。",
                    schema(Map.of("status", str("pending / taken_down 等；不传看全部"))),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                List<ShareService.ReportView> reports = shares.listReports(stringOf(arguments, "status"));
                if (reports.isEmpty()) {
                    return ToolResult.ok("没有符合条件的举报。");
                }
                List<String> lines = reports.stream()
                        .map(report -> "举报id=" + report.id() + " [" + report.status() + "] "
                                + report.createdAt() + " 分享=" + report.shareId()
                                + " 笔记=" + report.noteTitle() + " 原因=" + report.reason())
                        .toList();
                return ToolResult.ok("共 " + reports.size() + " 条：\n" + join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取举报失败：" + messageOf(failure));
            }
        }
    }

    /** 下架被举报的分享。 */
    @Component
    public static class Takedown implements PaideiaTool {

        private final ShareService shares;

        Takedown(ShareService shares) {
            this.shares = shares;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_review_takedown_note", GROUP, "下架被举报的笔记分享",
                    "**不可撤销**：分享链接立刻不可读，并把这条举报标为已处理。"
                            + "参数是举报 id（来自 admin_review_list_note_reports），不是分享 id。",
                    schema(Map.of("reportId", num("举报 id")), "reportId"),
                    null, false, true, false, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                Long reportId = longOf(arguments, "reportId");
                if (reportId == null) {
                    return ToolResult.fail("缺少 reportId");
                }
                shares.takedown(reportId);
                return ToolResult.ok("已下架举报 " + reportId + " 对应的分享");
            } catch (RuntimeException failure) {
                return ToolResult.fail("下架失败：" + messageOf(failure));
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
