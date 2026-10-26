package io.github.xiaou61.ops;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 运维摘要的管理工具（WORK-039 TASK-006）。
 *
 * <p>**只读**：运维面不该成为第二个能被写坏的地方（与 `OpsSummaryController` 同一条裁决）。
 * Grafana iframe 代理不做成工具：它依赖会话 Cookie，PAT 架构根本走不通。
 */
public final class OpsAdminTools {

    private OpsAdminTools() {
    }

    /** 服务、中间件、外部依赖与备份的当前状态。 */
    @Component
    public static class Summary implements PaideiaTool {

        private final OpsSummaryService service;

        Summary(OpsSummaryService service) {
            this.service = service;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "admin_ops_summary", GROUP, "读取运维摘要",
                    "返回服务、中间件、外部依赖与备份的当前状态（分组 + 状态 + 原因 + 指标）。"
                            + "Prometheus 不可达时也会返回，但指标为空。",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                    null, true, false, true, ToolScope.ADMIN, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            try {
                OpsSummary summary = service.summary();
                List<String> lines = new ArrayList<>();
                lines.add("采集时间=" + summary.collectedAt()
                        + " Prometheus=" + (summary.prometheusReachable() ? "可达" : "不可达"));
                for (OpsSummary.Group group : summary.groups()) {
                    lines.add("");
                    lines.add("【" + group.title() + "】" + group.status()
                            + (group.reason() == null || group.reason().isBlank() ? "" : " — " + group.reason()));
                    for (OpsSummary.Metric metric : group.metrics()) {
                        lines.add("  " + metric.name() + " = "
                                + (metric.text() != null ? metric.text()
                                : metric.value() == null ? "—"
                                : metric.value() + (metric.unit() == null ? "" : " " + metric.unit())));
                    }
                }
                return ToolResult.ok(String.join("\n", lines));
            } catch (RuntimeException failure) {
                return ToolResult.fail("读取运维摘要失败：" + messageOf(failure));
            }
        }

        private static String messageOf(RuntimeException failure) {
            String message = failure.getMessage();
            return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
        }
    }

    static final String GROUP = "admin_ops";
}
