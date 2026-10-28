package io.github.xiaou61.ai.internal.memory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写入前的内容过滤（WORK-050 REQ-001）。
 *
 * <p><b>为什么必须有</b>：记忆是**永久**的（没有保留期），而写入路径只有长度校验。用户一旦把手机号、
 * 口令、连接串说给助手听，模型可能把它提炼成一条记忆长期留着，之后每一轮都把它拼进提示词——
 * 那是一次不会自己消失的泄露。这不是"以后再说"的优化项。
 *
 * <p><b>只判定，不改写</b>：脱敏改写会让"被过滤的那条"仍然以某种形式存在，也让用户以为它记住了。
 * 命中就是整条不落库，由调用方决定怎么告知。
 *
 * <p><b>宁可误伤，不可漏判</b>：这是信任边界上的取舍。代价是偶尔把一句正常的话拒掉，
 * 而那会在面板上以"未记录"的形式被看见——比静默存下一个口令好得多。
 *
 * <p><b>已知误伤</b>：`password: string` 这类类型声明会命中 KEY_VALUE。用一张极小的白名单
 * （常见类型名与占位符）挡住最常见的那几种；更复杂的代码片段仍可能被拒，接受这个代价。
 */
public final class SensitiveContent {

    public enum Category {
        /** 私钥块。 */
        PRIVATE_KEY,
        /** 云厂商 / 模型服务的密钥形态。 */
        API_KEY,
        /** key: value 形式的凭据（含口令、令牌）。 */
        KEY_VALUE,
        /** 带凭据或口令的连接串。 */
        CONNECTION_STRING,
        /** 18 位或 15 位身份证号。 */
        ID_CARD,
        /** 16–19 位银行卡号。 */
        BANK_CARD,
        /** 中国大陆手机号。 */
        PHONE
    }

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----");

    private static final Pattern API_KEY = Pattern.compile(
            "\\b(sk-[A-Za-z0-9_-]{16,}"
                    + "|AKIA[0-9A-Z]{16}"
                    + "|gh[pousr]_[A-Za-z0-9]{20,}"
                    + "|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,})");

    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key)\\s*[:=]\\s*[\"']?([^\\s,;()\\[\\]{}'\"]{6,})");

    /** 这些值一看就不是凭据（类型名、占位符），放过。 */
    private static final List<String> NOT_SECRETS = List.of(
            "string", "number", "boolean", "null", "undefined", "true", "false", "xxxx", "****");

    private static final Pattern CONNECTION_STRING = Pattern.compile(
            "(?i)\\b(jdbc:[a-z0-9]+://\\S+|(?:mysql|postgresql|postgres|mongodb|redis|amqp)(?:\\+[a-z]+)?://\\S+)");

    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])([0-9]{17}[0-9Xx]|[0-9]{15})(?![0-9])");

    private static final Pattern BANK_CARD = Pattern.compile("(?<![0-9])[0-9]{16,19}(?![0-9])");

    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");

    private SensitiveContent() {
    }

    /** 命中就返回类别。空表示"看起来可以记"。 */
    public static Optional<Category> find(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (PRIVATE_KEY.matcher(text).find()) {
            return Optional.of(Category.PRIVATE_KEY);
        }
        if (API_KEY.matcher(text).find()) {
            return Optional.of(Category.API_KEY);
        }
        if (hasRealKeyValue(text)) {
            return Optional.of(Category.KEY_VALUE);
        }
        if (CONNECTION_STRING.matcher(text).find()) {
            return Optional.of(Category.CONNECTION_STRING);
        }
        if (ID_CARD.matcher(text).find()) {
            return Optional.of(Category.ID_CARD);
        }
        // 18 位身份证与 19 位银行卡会重叠，顺序在这里是有意义的：先身份证、后银行卡
        if (BANK_CARD.matcher(text).find()) {
            return Optional.of(Category.BANK_CARD);
        }
        if (PHONE.matcher(text).find()) {
            return Optional.of(Category.PHONE);
        }
        return Optional.empty();
    }

    private static boolean hasRealKeyValue(String text) {
        Matcher matcher = KEY_VALUE.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(2);
            boolean placeholder = NOT_SECRETS.stream().anyMatch(value::equalsIgnoreCase);
            if (!placeholder) {
                return true;
            }
        }
        return false;
    }
}
