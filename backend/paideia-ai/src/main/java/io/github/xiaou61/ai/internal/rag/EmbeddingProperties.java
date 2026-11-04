package io.github.xiaou61.ai.internal.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地 embedding 服务配置（WORK-072）。
 *
 * <p>服务由 Paideia 自己在服务器上用 Ollama 提供，默认只绑定回环地址；这里刻意
 * 不把 API key 放进来——本地服务没有外部凭据。
 */
@ConfigurationProperties(prefix = "paideia.ai.embedding")
public class EmbeddingProperties {

    /** 关闭时 RAG 索引/检索直接返回空，不影响普通 AI 回答。 */
    private boolean enabled = false;

    /** Ollama 地址；生产部署为 http://127.0.0.1:11434。 */
    private String baseUrl = "http://127.0.0.1:11434";

    /** 本地模型名，默认 bge-m3。 */
    private String model = "bge-m3";

    /** 单次 HTTP 请求超时（秒）。CPU 上首次加载模型会慢，给足。 */
    private int timeoutSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
