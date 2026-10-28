package io.github.xiaou61.ai.internal.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆自动整理的配置（WORK-013）。
 *
 * <p>{@code roundsPerExtraction} 就是设计里的 N：**每几轮整理一次**。用户裁决过"每 N 轮或会话结束
 * 批量提取"，默认 6——用线上真实用量算过，摊到每轮约 +13%（每轮自动的话是 +80%）。
 *
 * <p>{@code temperature} 刻意低（判断类任务要稳），{@code maxTokens} 刻意给足：本项目的已知坑是
 * "思考 token 吃满 max_tokens 会静默返回空内容"，而"空"在这里会被当成"没什么可记的"。
 */
@ConfigurationProperties(prefix = "paideia.ai.memory")
public class MemoryProperties {

    /** 每几轮对话自动整理一次。**0 或负数 = 关掉自动整理**（留一条不用改代码的退路）。 */
    private int roundsPerExtraction = 6;

    /** 一次整理自带的 token 上限（不含思考时的余量见类注释）。 */
    private int maxTokens = 3072;

    /** 判断类任务：低温度。 */
    private double temperature = 0.2;

    /** 单次整理调用的超时（秒）。 */
    private int requestTimeoutSeconds = 60;

    /**
     * 判重时给模型看的已有记忆条数上限。
     *
     * <p>**只能调小**：硬上限在 {@code MemoryStore.DEDUP_POOL_LIMIT}（40），这里调大不生效——
     * 那个常量表达的是"再多就该改分批检索了"这条天花板，不该被配置绕过去。
     */
    private int dedupPoolSize = 40;

    /**
     * 各 kind 的保留天数（WORK-050 D-02：淘汰按时间）。
     *
     * <p>分档而不是一刀切：`weak_topic`（还不懂）会随学会而过时，一年前的"不懂"基本是噪声；
     * `preference`（他喜欢短回答）一年后仍然可能成立。全部删掉会让助手"又忘了我"，
     * 统一长留又会让记忆库只增不减——两边的代价都由这张表承担。
     */
    private Map<String, Integer> structuredTtlDays = defaultStructuredTtl();

    /** 全文那一路的保留天数。它记的是"他说过的话"，比弱项更耐久，比偏好更易过时。 */
    private int textTtlDays = 180;

    /** 候选的有效期（WORK-050 Q-003）：面板不是每天都会打开，7 天太短。 */
    private int candidateExpiryDays = 14;

    /** 淘汰任务开关。关掉只是停止删除，历史数据不受影响。 */
    private boolean purgeEnabled = true;

    private static Map<String, Integer> defaultStructuredTtl() {
        Map<String, Integer> ttl = new LinkedHashMap<>();
        ttl.put("weak_topic", 90);
        ttl.put("entry_understanding", 90);
        ttl.put("goal", 180);
        ttl.put("preference", 365);
        return ttl;
    }

    public Map<String, Integer> getStructuredTtlDays() {
        return structuredTtlDays;
    }

    public void setStructuredTtlDays(Map<String, Integer> structuredTtlDays) {
        this.structuredTtlDays = structuredTtlDays;
    }

    public int getTextTtlDays() {
        return textTtlDays;
    }

    public void setTextTtlDays(int textTtlDays) {
        this.textTtlDays = textTtlDays;
    }

    public int getCandidateExpiryDays() {
        return candidateExpiryDays;
    }

    public void setCandidateExpiryDays(int candidateExpiryDays) {
        this.candidateExpiryDays = candidateExpiryDays;
    }

    public boolean isPurgeEnabled() {
        return purgeEnabled;
    }

    public void setPurgeEnabled(boolean purgeEnabled) {
        this.purgeEnabled = purgeEnabled;
    }

    public int getRoundsPerExtraction() {
        return roundsPerExtraction;
    }

    public void setRoundsPerExtraction(int roundsPerExtraction) {
        this.roundsPerExtraction = roundsPerExtraction;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getDedupPoolSize() {
        return dedupPoolSize;
    }

    public void setDedupPoolSize(int dedupPoolSize) {
        this.dedupPoolSize = dedupPoolSize;
    }
}
