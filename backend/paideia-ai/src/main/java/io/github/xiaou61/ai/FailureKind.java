package io.github.xiaou61.ai;

/**
 * AI 失败的处置语义（WORK-069）。
 *
 * <p>它回答的是「这次失败接下来该怎么办」，不是「这次失败是谁的错」。错误归因仍由
 * {@code ai_runs.error_type} 承担；本枚举只落处置结论，供调用方决定重试与人工介入。
 *
 * <p>{@link #RETRYABLE} 与其它两档的分界就是「同配置再来一次有没有可能成功」：
 * 临时网络故障可以，模型输出不合规与平台配置错误不可以。
 */
public enum FailureKind {

    /** 临时失败，可自动重来（上游不可用、超时、线程池忙、数据库瞬时错误）。 */
    RETRYABLE("retryable"),

    /** 模型输出不合规，同配置再来一次没用（空返回、坏 JSON、字段缺失）。 */
    BAD_OUTPUT("bad_output"),

    /** 要人改配置或拍板（凭据被上游拒绝、max_tokens 被思考吃满等）。 */
    NEEDS_HUMAN("needs_human");

    private final String key;

    FailureKind(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * 是否值得自动重来。
     *
     * <p>这是全项目唯一的可重试判定口径；调用方不要各自比较字符串。
     */
    public boolean isRetryable() {
        return this == RETRYABLE;
    }
}
