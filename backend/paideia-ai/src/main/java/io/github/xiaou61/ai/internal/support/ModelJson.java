package io.github.xiaou61.ai.internal.support;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 模型输出的小工具（WORK-062 收敛）。
 *
 * <p>全仓原有 6 份**逐字相同**的"剥代码围栏"与 5 份 JsonNode 取值，散在各解析器里。
 * 落点在 ai 模块内（消费者全在 ai，不改变模块边界）；将来真有第二个模块要用，再按
 * `SearchQuery` 的"第二个消费者才提升"先例上 platform。
 *
 * <p>2026-11-11 又收敛了 4 份"取第一个 `{` 到最后一个 `}`"（题目打标 / 复习题 / 速递 /
 * 学习建议），都是同一段下标计算，见 {@link #objectBody(String)}。
 *
 * <p><b>刻意保留的三个变体</b>（语义不同，不合并）：
 * {@code CampItemParser} / {@code EssayQuestionParser} 的剥围栏在"输出被截断、没有收尾围栏"
 * 时会保留全部正文；{@code ResumeJson.text} 还带计数统计。
 */
public final class ModelJson {

    private ModelJson() {
    }

    /**
     * 剥掉模型输出常见的 ``` 围栏：取首行围栏之后、末个围栏之前的正文并 trim。
     *
     * <p>不满足条件（不以围栏开头 / 找不到收尾围栏）时**原样返回**——宁可让下游解析报错，
     * 也不在这里猜内容边界。
     */
    public static String stripFence(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    /**
     * 取出模型输出里那段 JSON 对象的正文：先剥围栏，再取第一个 `{` 到最后一个 `}`。
     *
     * <p>这是全仓最宽容的一档：围栏、前后解释文字都能兜住，所以提示词里那句"只输出 JSON"
     * 失效时不至于整批白跑。代价是模型在 JSON **后面**再写一段带花括号的话会被一起切进来、
     * 解析失败——那种情况返回 null 由调用方降级，不在这里猜边界。
     *
     * <p>返回 null 表示"这段文本里没有完整的 JSON 对象"（空、纯散文、或被截断只剩左括号）。
     */
    public static String objectBody(String raw) {
        if (raw == null) {
            return null;
        }
        String text = stripFence(raw);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** 取字段文本：节点/字段缺失或为 null → null。"没给"与"给了空串"必须能分辨。 */
    public static String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 取字段文本：缺失、null 或非值节点 → 空串；有值则 trim。给"要有默认值"的读路径用。 */
    public static String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? "" : value.asText().trim();
    }
}
