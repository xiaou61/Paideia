package io.github.xiaou61.ai;

/**
 * 一轮调用的采集端口（WORK-022）。
 *
 * <p><b>这是本项唯一重要的设计决定</b>：业务代码只认识这个接口，不认识数据库、OTel 或任何观测后端。
 * 落地由若干 {@code RunSink} 并列完成——接 Langfuse / Prometheus 是加一个类，不是回头改七个埋点。
 *
 * <p><b>调用方线程零 IO</b>：`begin` / `step` / `attribute` / `finish` / `close` 只动内存，
 * 快照进有界队列就返回。队列满时丢弃并计数，**统计系统不许影响回答**。
 *
 * <p><b>失败只打日志</b>：写入抛异常一律吞掉。调用方永远不该因为"记录失败"而看到错误。
 *
 * <p>用法：一轮的开头 `begin`，结束时 `finish`；异常路径靠 try-with-resources 的
 * {@code close()} 兜成 {@link RunOutcome#INTERRUPTED}。
 *
 * <pre>{@code
 * try (var run = recorder.begin(RunSpec.of(RunKind.CHAT, userId))) {
 *     try (var step = run.step(SpanSpec.llm(1))) {
 *         ... 一次上游调用 ...
 *         step.tokens(input, output, 0);
 *         step.succeeded();
 *     }
 *     run.tokens(input, output, 0);
 *     run.finish(RunOutcome.OK, null);
 * }
 * }</pre>
 */
public interface RunRecorder {

    /** 采集是否开着。关掉时实现返回 {@link NoopScope}，业务代码不必分支。 */
    boolean enabled();

    RunScope begin(RunSpec spec);

    /** 一轮。实现必须幂等：`finish` 与 `close` 任意顺序、任意次数，只生效一次。 */
    interface RunScope extends AutoCloseable {

        /**
         * 这一轮的标识（WORK-036）。
         *
         * <p>给"用户对这条回答点踩"用：反馈要挂到具体某一轮上才能定位场景、模型与提示词版本。
         * **关闭采集时为 null**——那时本来就没有轮次记录，反馈仍可提交，只是挂不上去。
         */
        String traceId();

        /** 开一步。没在 try-with-resources 里 close 的步按 interrupted 处理。 */
        StepScope step(SpanSpec spec);

        /**
         * 冷字段。**只接受白名单键**（见设计里那七个），其它键记 debug 后丢弃——
         * 不然总有一天有人把提示词或用户输入塞进来。
         */
        void attribute(String key, Object value);

        /** 累加到轮次总量。 */
        void tokens(int input, int output, int cacheRead);

        /**
         * 真正开始处理的时刻。`begin` 到这一步之间的差就是**排队时间**——
         * 线程池满时它就是"用户等了多久才被捡起来"，重复调用以第一次为准。
         */
        void markStarted();

        /** 首字时刻。只在第一个正文增量出现时调，重复调用以第一次为准。 */
        void markTtft();

        /** 这一轮实扣多少积分（与积分流水做日级对账）。 */
        void addCredits(int charged);

        /** 终态 + 低基数错误标识（取自 ErrorCode 或归一值，**不是异常消息**）。 */
        void finish(RunOutcome outcome, String errorType);

        /**
         * 带处置语义的终态（WORK-069）。
         *
         * <p>默认转发到两参方法，既有的采集实现与业务调用点不必一次性改造；需要落
         * {@code failure_kind} 的实现覆盖它即可。失败终态应尽量给出明确处置；采集层对
         * 没有显式分类的失败兜底为 {@link FailureKind#RETRYABLE}。
         */
        default void finish(RunOutcome outcome, String errorType, FailureKind failureKind) {
            finish(outcome, errorType);
        }

        @Override
        void close();
    }

    /** 一步。 */
    interface StepScope extends AutoCloseable {
        void attribute(String key, Object value);

        void tokens(int input, int output, int cacheRead);

        /**
         * 这一轮的首字延迟（WORK-030 阶段 B）。只对 LLM 步有意义；工具步不必调。
         *
         * <p>传的是**毫秒数**而不是时刻：调用方拿到的是"第一个增量距本轮开始多久"，
         * 那个差值只能在流式回调里算，交给采集器算会引入另一套时钟。
         */
        void ttftMs(int millis);

        /**
         * 这一步的完整输入与输出（WORK-030 阶段 B）。
         *
         * <p>**只在 LLM 步调**：工具步没有"发给模型的一整包消息"这回事，它的入参与返回走
         * {@link #attribute(String, Object)}。
         *
         * <p>超上限时实现负责**存占位而不是丢弃**——静默丢会让"为什么没有请求体"变成新的谜。
         * 调用方不必自己判大小。
         */
        void payload(String requestBody, String responseBody);

        void succeeded();

        void failed(String errorType);

        @Override
        void close();
    }
}
