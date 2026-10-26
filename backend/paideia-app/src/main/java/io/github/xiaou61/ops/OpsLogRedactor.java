package io.github.xiaou61.ops;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 日志出站前的脱敏与截断（WORK-042 REQ-010）。
 *
 * <p><b>它只做保守替换，不做"识别是不是敏感"的判断</b>：认错一个词只是少了一行可读性，
 * 漏掉一个令牌却是永久泄露。因此模式宁可宽一点（例如 32 位以上的十六进制整串打码），
 * 也不为"保留可读性"放行。
 *
 * <p>顺序有意义：先处理 `Bearer <token>`（它带一个空格，赋值模式只吃到 `Bearer` 就停），
 * 再处理 JWT、`password=…` 这类赋值，最后处理邮箱与长串。顺序反过来时
 * `Authorization: Bearer abc` 会留下裸 token。
 */
final class OpsLogRedactor {

    /** 单行上限。超过就截断——日志原文不该成为提示词长度的主因。 */
    static final int MAX_LINE_CHARS = 500;

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|api[_-]?key|authorization)\\s*[=:]\\s*\\S+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9a-fA-F]{32,}\\b");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private OpsLogRedactor() {
    }

    static String redact(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        String text = CONTROL.matcher(line).replaceAll(" ");
        for (Pattern pattern : List.of(BEARER, JWT, ASSIGNMENT, EMAIL, LONG_HEX)) {
            text = pattern.matcher(text).replaceAll("***");
        }
        if (text.length() > MAX_LINE_CHARS) {
            text = text.substring(0, MAX_LINE_CHARS) + "…";
        }
        return text;
    }

    /**
     * 逐行脱敏并按条数/总字符双重上限拼接。总上限优先于条数：宁可少给两行，
     * 也不让一次诊断把上下文撑爆。
     */
    static String joinRedacted(List<String> lines, int maxLines, int maxChars) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String raw : lines) {
            if (count >= maxLines) {
                break;
            }
            String row = redact(raw);
            if (row.isEmpty()) {
                continue;
            }
            if (out.length() + row.length() + 1 > maxChars) {
                break;
            }
            out.append(row).append('\n');
            count++;
        }
        return out.toString();
    }
}
