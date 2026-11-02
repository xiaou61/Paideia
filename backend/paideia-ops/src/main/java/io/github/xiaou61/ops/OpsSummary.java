package io.github.xiaou61.ops;

import java.util.List;

/**
 * 运维摘要的响应（WORK-028 REQ-028）。
 *
 * <p>每个 {@link Group} 自带状态与一句人话原因：页面只负责显示，不再自己判断阈值——
 * 判据只有一份，在 {@link OpsSummaryService}。
 */
public record OpsSummary(String collectedAt, boolean prometheusReachable, List<Group> groups) {

    public record Group(String key, String title, OpsStatus status, String reason, List<Metric> metrics) {
    }

    /** {@code unit} 只是给人看的后缀（ratio / bytes / count / text），不参与判定。 */
    public record Metric(String name, Double value, String unit, String text) {

        public static Metric number(String name, Double value, String unit) {
            return new Metric(name, value, unit, null);
        }

        public static Metric text(String name, String text) {
            return new Metric(name, null, "text", text);
        }
    }
}
