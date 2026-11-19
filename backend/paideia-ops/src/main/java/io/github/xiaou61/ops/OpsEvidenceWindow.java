package io.github.xiaou61.ops;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 诊断证据的时间窗口（WORK-086 TASK-001）。
 *
 * <p><b>为什么要有这个东西</b>：修之前采集器把所有时间窗查询都锚在 {@code Instant.now()} 往前一小时，
 * 而诊断往往是**事后**才跑（事件 14:17 触发、补诊断 14:41 才开始），取到的是"现在"的日志。
 * 2026-11-19 那次就是这样：报告里写着"无明显异常"，而真实窗口里应用重启了 4 次。
 * 窗口必须是**事件自己的时间**，并且所有查询共用同一个实例，才能保证日志、指标、trace 说的是同一段时间。
 *
 * <p><b>为什么优先级是 firstSeen 而不是触发时刻</b>：告警 webhook 的到达时刻可能比事件首次出现晚几分钟到几小时
 * （重试、人工补诊断、应用重启后的 catch-up）。事件自己记录的首末时间才是证据的正确锚点。
 *
 * <p><b>为什么还要"至少 10 分钟"的下限</b>：事件的首末时间可能只差几秒（一条 webhook 里两个相邻时间戳），
 * 那样窗口太窄，看不出前后因果——重启前那几行 {@code Shutdown initiated} 就落在窗口外了。
 */
public record OpsEvidenceWindow(Instant start, Instant end, String source, boolean outOfRetention) {

    /** 无事件时向前的默认跨度（分钟）。 */
    public static final int DEFAULT_MINUTES = 30;

    /** 窗口总长上限（分钟）：再长就超出模型上下文的价值密度。 */
    public static final int MAX_MINUTES = 60;

    /** 事件首末时间之间至少留出的跨度，见类注释。 */
    private static final Duration MIN_SPAN = Duration.ofMinutes(10);

    /** 事件结束后再多看 5 分钟：重启、连接池关闭这类尾巴常常落在 lastSeen 之后。 */
    private static final Duration TAIL = Duration.ofMinutes(5);

    /** Loki 保留期（{@code deploy/ops/loki-config.yaml:39} {@code retention_period: 720h}）。 */
    private static final Duration LOG_RETENTION = Duration.ofDays(30);

    /** 与 {@code OpsIncidentContext} 一致地钉时区，避免服务器/测试机时区不同导致报告里出现两种时间。 */
    private static final DateTimeFormatter HHMM =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    public OpsEvidenceWindow {
        if (start == null || end == null) {
            throw new IllegalArgumentException("证据窗口的起止时间不能为空");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("证据窗口的起点不能晚于终点：" + start + " > " + end);
        }
    }

    /**
     * 以"现在"为基准构造（生产路径）。
     *
     * @param firstSeen 事件首次出现时间；为空表示没有关联事件（手动诊断、模板触发）
     * @param lastSeen  事件最近时间；为空时按 {@code firstSeen} 处理
     * @param triggerAt 触发诊断的时刻；为空时用 {@code now}
     */
    public static OpsEvidenceWindow of(
            Instant firstSeen, Instant lastSeen, Instant triggerAt, int defaultMinutes, int maxMinutes) {
        return of(firstSeen, lastSeen, triggerAt, defaultMinutes, maxMinutes, Instant.now());
    }

    /** 同上，但把"现在"也作为参数——只给测试用来钉住时间。 */
    public static OpsEvidenceWindow of(
            Instant firstSeen,
            Instant lastSeen,
            Instant triggerAt,
            int defaultMinutes,
            int maxMinutes,
            Instant now) {
        Instant reference = now == null ? Instant.now() : now;
        int defaultSpan = defaultMinutes > 0 ? defaultMinutes : DEFAULT_MINUTES;
        int maxSpan = maxMinutes > 0 ? maxMinutes : MAX_MINUTES;

        Instant end;
        if (firstSeen != null) {
            Instant last = lastSeen == null || lastSeen.isBefore(firstSeen) ? firstSeen : lastSeen;
            end = last.plus(TAIL);
        } else {
            end = triggerAt == null ? reference : triggerAt;
        }
        // 事件的"未来尾巴"不能超过现在：否则窗口里会出现还没发生的区间
        if (end.isAfter(reference)) {
            end = reference;
        }

        Instant start;
        if (firstSeen != null) {
            start = firstSeen.minus(MIN_SPAN);
            // 首末时间挨得太近时，把起点往前推，保证至少 MIN_SPAN 的观察面
            Instant floor = end.minus(MIN_SPAN);
            if (start.isAfter(floor)) {
                start = floor;
            }
        } else {
            start = end.minus(Duration.ofMinutes(defaultSpan));
        }
        Instant earliest = end.minus(Duration.ofMinutes(maxSpan));
        if (start.isBefore(earliest)) {
            start = earliest;
        }
        if (start.isAfter(end)) {
            start = end;
        }

        boolean outOfRetention = firstSeen != null && end.isBefore(reference.minus(LOG_RETENTION));
        return new OpsEvidenceWindow(start, end, "", outOfRetention);
    }

    /** 补上"这段窗口是怎么来的"（如 {@code 事件 #17}），用于证据头部与提示词。 */
    public OpsEvidenceWindow withSource(String text) {
        return new OpsEvidenceWindow(start, end, text == null ? "" : text.trim(), outOfRetention);
    }

    /** 证据与提示词里显示的一行：{@code 证据窗口 14:07–14:23（事件 #17）}。 */
    public String label() {
        String text = "证据窗口 " + HHMM.format(start) + "–" + HHMM.format(end);
        String tag = source == null ? "" : source.trim();
        if (!tag.isEmpty()) {
            text = text + "（" + tag + "）";
        }
        if (outOfRetention) {
            text = text + "，超出 Loki 保留期（30 天），仅指标可用";
        }
        return text;
    }

    /** 窗口长度（秒），用于拼接 PromQL 的 {@code [<n>s]}。 */
    public long seconds() {
        return Duration.between(start, end).getSeconds();
    }

    /** 窗口长度（分钟，向上取整至少 1），用于拼接 PromQL 的 {@code [<n>m]}。 */
    public int minutes() {
        long seconds = seconds();
        return (int) Math.max(1, (seconds + 59) / 60);
    }
}
