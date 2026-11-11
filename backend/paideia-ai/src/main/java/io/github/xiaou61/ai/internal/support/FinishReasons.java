package io.github.xiaou61.ai.internal.support;

import io.agentscope.core.model.ChatResponse;
import java.util.Locale;

/**
 * 读上游的收尾原因（{@code finish_reason}）。
 *
 * <p>存在的理由只有一个：**输出被输出预算截断**和"模型没按格式回答"在解析层长得一模一样
 * （都是"不是合法 JSON"或"返回了空内容"），但根因和处置完全不同——前者要抬 maxTokens 或
 * 压输出规模，后者要改提示词。DeepSeek 默认思考、思考 token 与正文共用 maxTokens，
 * 截断是本项目的常见故障（2026-10-21 实测 {@code finish_reason=length}），所以单独识别。
 *
 * <p>取值按包含匹配：OpenAI 兼容层普遍写 {@code "length"}，也有写 {@code "MAX_TOKENS"} 的。
 * 上游没给（null）时按**未截断**处理——不能把正常回复当失败，那是更贵的错。
 */
public final class FinishReasons {

    private FinishReasons() {
    }

    /** 这次回复是不是被输出上限截断了。 */
    public static boolean truncated(ChatResponse response) {
        String reason = response == null ? null : response.getFinishReason();
        if (reason == null) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("length") || normalized.contains("max_token");
    }
}
