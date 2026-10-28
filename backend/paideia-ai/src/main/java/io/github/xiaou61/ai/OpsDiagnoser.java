package io.github.xiaou61.ai;

/**
 * 运维诊断端口（WORK-042）。
 *
 * <p><b>它只接受已经采集好、已经脱敏的证据</b>，不自己去读 Prometheus / Loki / 日志文件。
 * 这样做的理由有三条：证据的采集与截断留在能看见数据的模块里；模型只能看到我们给它的文本；
 * 积分、遥测与凭据的编排收在 AI 模块内部，app 侧不必认识它们。
 *
 * <p>与 `InterviewReviewer` 同一纪律：**一次调用、不带工具、不要结构化输出**。
 * 报告是给人读的 Markdown，落库由调用方负责。
 */
public interface OpsDiagnoser {

    Diagnosis diagnose(long userId, Evidence evidence);

    /**
     * 一次诊断的全部输入。字段可以为空（例如 Loki 不可达），但调用方必须保证已经脱敏与截断。
     *
     * @param trigger   `manual` 或 `alert`
     * @param alertName 告警触发时的告警名；手动触发为 null
     * @param question  管理员填写的关注点；没有为 null
     * @param summary   运维摘要（七组状态）
     * @param metrics   关键指标（已格式化）
     * @param baselines 关键指标与上周同时段基线（WORK-043）
     * @param context   历史同类事件与同期其他 OPEN 事件（WORK-049）
     * @param alerts    当前 firing 的告警（已格式化）
     * @param logs      最近日志摘录（已脱敏）
     * @param traces    错误/慢 trace 摘要（WORK-043；P2 起有值）
     * @param deploys   最近发版信息
     */
    record Evidence(String trigger, String alertName, String question,
                    String summary, String metrics, String baselines, String context,
                    String alerts, String logs, String traces, String deploys) {
    }

    /** @param usage 上游没有返回用量时为 null（此时结算会全额退回预留） */
    record Diagnosis(String report, TokenUsage usage, String model) {
    }
}
