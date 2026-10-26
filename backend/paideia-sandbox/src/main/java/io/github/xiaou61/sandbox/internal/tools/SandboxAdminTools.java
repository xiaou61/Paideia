package io.github.xiaou61.sandbox.internal.tools;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * SQL 数据集的管理工具组（WORK-039 TASK-005，3 个）。
 *
 * <p>**只能让它不可见，不能编辑别人的内容**（与 `SqlDatasetAdminController` 同一条口径）。
 * 状态只有 `published` 与 `taken_down` 两个可写值；下架时同时把未处理的举报标成已处理。
 */
public final class SandboxAdminTools {

    private static final String GROUP = "admin_review";
    private static final int MAX_ROWS = 200;

    private SandboxAdminTools() {
    }

    /** 社区数据集清单。 */
    @Component
    public static class ListDatasets implements PaideiaTool {

        private final SqlDatasetMapper datasets;

        ListDatasets(SqlDatasetMapper datasets) {
            this.datasets = datasets;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_review_list_datasets", GROUP, "列出社区 SQL 数据集",
                    "列出社区数据集（不含私人的），带作者、表/行数与举报数。"
                            + "status 可传 draft / published / taken_down 过滤。",
                    schema(Map.of("status", str("状态过滤；不传看全部"))),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                List<SqlDatasetMapper.Summary> rows = datasets.listCommunity(
                        stringOf(arguments, "status"), MAX_ROWS);
                if (rows.isEmpty()) {
                    return ToolResult.ok("没有符合条件的社区数据集。");
                }
                List<String> lines = rows.stream()
                        .map(row -> "key=" + row.datasetKey() + " " + row.name()
                                + " 作者=" + (row.ownerUserId() == null ? "无" : row.ownerUserId())
                                + " 表=" + row.tableCount() + " 行=" + row.rowCount()
                                + " 举报=" + datasets.countReports(row.datasetKey()))
                        .toList();
                return ToolResult.ok("共 " + rows.size() + " 个：\n" + join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取数据集失败：" + messageOf(failure));
            }
        }
    }

    /** 数据集举报清单。 */
    @Component
    public static class ListDatasetReports implements PaideiaTool {

        private final SqlDatasetMapper datasets;

        ListDatasetReports(SqlDatasetMapper datasets) {
            this.datasets = datasets;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_review_list_dataset_reports", GROUP, "列出 SQL 数据集举报",
                    "列出针对社区数据集的举报，带举报人、原因与处理状态。",
                    schema(Map.of("status", str("pending / taken_down 等；不传看全部"))),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                List<SqlDatasetMapper.ReportRow> reports = datasets.listReports(
                        stringOf(arguments, "status"), MAX_ROWS);
                if (reports.isEmpty()) {
                    return ToolResult.ok("没有符合条件的举报。");
                }
                List<String> lines = reports.stream()
                        .map(row -> "举报id=" + row.id() + " [" + row.status() + "] " + row.createdAt()
                                + " 数据集=" + row.datasetKey() + " 举报人=" + row.reporterUserId()
                                + " 原因=" + row.reason())
                        .toList();
                return ToolResult.ok("共 " + reports.size() + " 条：\n" + join(lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取举报失败：" + messageOf(failure));
            }
        }
    }

    /** 下架或恢复数据集。 */
    @Component
    public static class SetDatasetStatus implements PaideiaTool {

        private final SqlDatasetMapper datasets;

        SetDatasetStatus(SqlDatasetMapper datasets) {
            this.datasets = datasets;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_review_set_dataset_status", GROUP, "下架或恢复社区数据集",
                    "把社区数据集设为 taken_down（下架）或 published（恢复上架）。"
                            + "下架会同时把未处理的举报标成已处理；恢复不会改动举报状态。"
                            + "只有社区数据集可以被下架，私人数据集会被拒绝。",
                    schema(Map.of(
                            "datasetKey", str("数据集的 key"),
                            "status", str("taken_down 或 published")),
                            "datasetKey", "status"),
                    null, false, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                String key = stringOf(arguments, "datasetKey");
                String status = stringOf(arguments, "status");
                if (key == null || status == null) {
                    return ToolResult.fail("datasetKey 与 status 必填");
                }
                String normalized = status.toLowerCase(java.util.Locale.ROOT);
                if (!"taken_down".equals(normalized) && !"published".equals(normalized)) {
                    return ToolResult.fail("status 只能是 taken_down 或 published");
                }
                SqlDatasetMapper.Row row = datasets.findByKey(key)
                        .orElseThrow(() -> new IllegalArgumentException("没有这个数据集"));
                if (!"community".equals(row.source())) {
                    return ToolResult.fail("只有社区数据集可以被下架或恢复");
                }
                Instant now = Instant.now();
                datasets.updateStatus(key, normalized, now);
                if ("taken_down".equals(normalized)) {
                    datasets.markReportsHandled(key, "taken_down", now);
                }
                return ToolResult.ok("已把数据集 " + key + " 设为 " + normalized);
            } catch (RuntimeException failure) {
                return ToolResult.fail("修改数据集状态失败：" + messageOf(failure));
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

    static String stringOf(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
