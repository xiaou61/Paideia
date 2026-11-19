package io.github.xiaou61.ai.internal.billing;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 计费与平台凭据（WORK-009）。
 *
 * <p><b>为什么平台凭据放配置而不是数据库</b>：它是部署级单例（全平台一份），入库只会多一个泄漏面，
 * 而且没有任何"每个用户不同"的语义。用户自带的 Key 才需要加密落库——WORK-009 已经把那条路撤了。
 *
 * <p><b>为什么价目表也在配置里</b>：上游调价时不该重新构建。改配置即改变同一用量的扣减数（AC-008）。
 *
 * <p><b>积分一律整数</b>：避免浮点误差，也避免出现"余额 3.999 分"这种界面。价格按"每千 token"
 * 记，折算时向下取整，**有调用就至少扣 1 分**（否则长尾调用永远不扣）。
 */
@ConfigurationProperties(prefix = "paideia.ai.billing")
public class BillingProperties {

    /**
     * 关掉即退回"平台凭据 + 不计量"（回滚档位一，见 design §10）。
     *
     * <p>它是 {@code true} 而平台凭据缺失时**不会启动失败**——只是 AI 功能给出"平台尚未配置"的提示。
     * 本地与 CI 因此不必准备凭据也能把上下文起起来。
     */
    private boolean enabled = true;

    private PlatformCredential platformCredential = new PlatformCredential();

    /** 模型单价：每千 token 的积分。键是模型名，**大小写不敏感**。 */
    private Map<String, Price> pricing = new LinkedHashMap<>();

    /** 低于这个余额直接拒绝（默认 0：至少得有 1 分可扣）。 */
    private long reserveFloor;

    /** 单次调用最多扣多少积分——防"一条消息吃掉全部余额"。 */
    private long maxCreditsPerCall = 5000;

    /**
     * 预留系数：预扣额 = 单次上限 × 它。
     *
     * <p>它是"并发时短暂占住多少额度"，**不是费用上限**（真正的费用上限是 settle 里那次截断）。
     * 默认 0.2 而不是 1.0：取 1.0 会让每次调用都要求余额达到单次上限（默认 5000）才肯开工，
     * 那是把"预留"当成了"预付费"。
     */
    private double reserveFactor = 0.2;

    /**
     * 一次调用该扣多少积分。
     *
     * <p>没有配到价目就返回 0（**不扣**）：宁可漏扣也不误扣——真配错了，审计页上"有用量但 0 扣减"
     * 一眼就能看出来，而误扣是用户看得见的损失。
     */
    public long creditsFor(String model, int inputTokens, int outputTokens) {
        Price price = priceFor(model);
        if (price == null || (inputTokens <= 0 && outputTokens <= 0)) {
            return 0;
        }
        long input = (long) inputTokens * price.getInput() / 1000;
        long output = (long) outputTokens * price.getOutput() / 1000;
        return Math.max(1, input + output);
    }

    /** 按模型名找价目，大小写不敏感（配置里写成 deepseek-flash 还是 DeepSeek-Flash 都认）。 */
    public Price priceFor(String model) {
        if (model == null) {
            return null;
        }
        for (Map.Entry<String, Price> entry : pricing.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(model)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public PlatformCredential getPlatformCredential() {
        return platformCredential;
    }

    public void setPlatformCredential(PlatformCredential platformCredential) {
        this.platformCredential = platformCredential;
    }

    public Map<String, Price> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, Price> pricing) {
        this.pricing = pricing;
    }

    public long getReserveFloor() {
        return reserveFloor;
    }

    public void setReserveFloor(long reserveFloor) {
        this.reserveFloor = reserveFloor;
    }

    public long getMaxCreditsPerCall() {
        return maxCreditsPerCall;
    }

    public void setMaxCreditsPerCall(long maxCreditsPerCall) {
        this.maxCreditsPerCall = maxCreditsPerCall;
    }

    public double getReserveFactor() {
        return reserveFactor;
    }

    public void setReserveFactor(double reserveFactor) {
        this.reserveFactor = reserveFactor;
    }

    /** 平台统一持有的上游凭据。三样都填了才算配置好。 */
    public static class PlatformCredential {

        private String baseUrl;
        private String model;
        private String apiKey;
        /**
         * 上游要求的额外请求头（如 OpenCode Go 的 {@code x-opencode-session}）。
         *
         * <p>只在该上游自己的 host 上发送，不会带给用户自带 Key 的其它上游。
         */
        private Map<String, String> headers = new LinkedHashMap<>();

        public boolean configured() {
            return notBlank(baseUrl) && notBlank(model) && notBlank(apiKey);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }

    /** 一个模型的单价：每千 token 多少积分。输入与输出分开——两者的价差是数量级的。 */
    public static class Price {

        private long input;
        private long output;

        public long getInput() {
            return input;
        }

        public void setInput(long input) {
            this.input = input;
        }

        public long getOutput() {
            return output;
        }

        public void setOutput(long output) {
            this.output = output;
        }
    }
}
