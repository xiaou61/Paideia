package io.github.xiaou61.ops;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 日志签名（WORK-086）：把一行日志里的"每次都变的部分"抹平，剩下的文本就是签名。
 *
 * <p>为什么需要它：同一类重启/异常在日志里只是 PID、时间戳、耗时数字不同。原样喂 60 行
 * 给模型，等于让模型自己在噪声里做聚类；先聚再给，既省预算也让"发生了几次"变成可见事实。
 *
 * <p>只做纯文本归约，不解析日志格式：日志行的结构由各服务自己决定，任何正则解析都只能
 * 覆盖一部分，反而会漏掉没预料到的行。
 */
final class OpsLogSignature {

    /** 签名长度上限：签名本身只是分组键，供模型看的那一版另有样例行。 */
    private static final int MAX_SIGNATURE_CHARS = 120;

    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    /** 完整日期时间（含 ISO 与 "yyyy-MM-dd HH:mm:ss" 两种写法），可带毫秒。 */
    private static final Pattern DATE_TIME = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b");

    /** 只有时间部分的写法。 */
    private static final Pattern TIME_ONLY = Pattern.compile(
            "\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b");

    /** 长十六进制串（哈希、trace id、会话 id）。 */
    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9a-fA-F]{32,}\\b");

    /** 0x 开头的十六进制字面量。 */
    private static final Pattern HEX_LITERAL = Pattern.compile("\\b0x[0-9a-fA-F]+\\b");

    /**
     * 其余数字（PID、耗时、字节数、内存）。不要求词边界：{@code 1234ms}、{@code 28.4s}
     * 这类"数字紧跟单位"的写法在本项目的日志里比裸数字更常见。
     */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private OpsLogSignature() {
    }

    /** 归约一行日志；空行返回空串（调用方应当跳过）。 */
    static String of(String line) {
        if (line == null) {
            return "";
        }
        String text = line.strip();
        if (text.isEmpty()) {
            return "";
        }
        text = UUID.matcher(text).replaceAll("<uuid>");
        text = DATE_TIME.matcher(text).replaceAll("<ts>");
        text = TIME_ONLY.matcher(text).replaceAll("<ts>");
        text = LONG_HEX.matcher(text).replaceAll("<hash>");
        text = HEX_LITERAL.matcher(text).replaceAll("<hex>");
        text = NUMBER.matcher(text).replaceAll("<n>");
        text = WHITESPACE.matcher(text).replaceAll(" ");
        if (text.length() > MAX_SIGNATURE_CHARS) {
            text = text.substring(0, MAX_SIGNATURE_CHARS) + "…";
        }
        return text.toLowerCase(Locale.ROOT);
    }
}
