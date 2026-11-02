package io.github.xiaou61.ops;

import java.util.List;

/**
 * 数据大屏的响应（WORK-045）。
 *
 * <p><b>五个区块一次返回</b>：分区请求会让各区块数字来自不同时刻，用户看到"AI 运行 55 次"
 * 与"积分消耗 50 笔"对不上会以为是 bug。
 *
 * <p><b>三态是刻意的</b>：{@link #STATE_OK}、{@link #STATE_ZERO}、{@link #STATE_NOT_COLLECTED}。
 * "确实是 0"与"根本没采集"必须能分开——把后者显示成 0 会让空白看起来像好消息。
 */
record AnalyticsView(String collectedAt,
                     List<Flow> flows,
                     Volume volume,
                     Usage usage,
                     Ai ai,
                     Search search,
                     Monitoring monitoring) {

    final class State {
        /** 有数据。 */
        static final String OK = "ok";
        /** 已采集，但当前为 0。 */
        static final String ZERO = "zero";
        /** 没有数据源，压根没采集。 */
        static final String NOT_COLLECTED = "not-collected";

        private State() {
        }
    }

    /** 一条数据链：源 → 处理 → 落库 → 消费。 */
    record Flow(String key, String title, List<Node> nodes, List<Edge> edges, String note) {
    }

    /**
     * 链上的一个节点。
     *
     * @param count 真实计数
     * @param state 见 {@link State}
     * @param broken 是否是**断点**：本节点为 0 而上游 > 0。整条链都没跑时不算断点
     */
    record Node(String label, long count, String state, String unit, boolean broken) {
    }

    /** 两个节点之间的通过率；上游为 0 时为 null（无法计算，不是 0%）。 */
    record Edge(Integer fromIndex, Integer toIndex, Double ratio) {
    }

    record Volume(List<TableRow> tables, List<DailyPoint> daily, String dailyNote) {
    }

    record TableRow(String name, String label, long rows, Long delta7d, String state) {
    }

    record DailyPoint(String date, String series, long count) {
    }

    /** 功能使用度：回答"上线了但没人用"。 */
    record Usage(long accounts, List<Feature> features) {
    }

    record Feature(String name, String state, String detail) {
    }

    record Ai(List<KindRow> byKind, Double successRate, long runs, Credits credits,
              List<ClickRow> topItems, long clicks, Long clickRatePercent) {
    }

    record KindRow(String kind, long runs, long ok) {
    }

    record Credits(long granted, long spent, long balance) {
    }

    record ClickRow(long id, String title, long clicks) {
    }

    /** 搜索洞察：用户在搜什么、搜不到什么。 */
    record Search(List<NameCount> top, List<NameCount> zeroResult) {
    }

    record NameCount(String name, long count) {
    }

    record Monitoring(boolean reachable, Double requestRate, Double errorRate, Double p95Seconds,
                      Double aiSuccessRate) {
    }
}
