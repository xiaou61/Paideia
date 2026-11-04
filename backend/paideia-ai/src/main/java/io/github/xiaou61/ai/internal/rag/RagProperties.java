package io.github.xiaou61.ai.internal.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** RAG 的索引与检索参数（WORK-072）。 */
@ConfigurationProperties(prefix = "paideia.ai.rag")
public class RagProperties {

    /** 总开关：embedding 服务未就绪时关掉它即可完全停用 RAG。 */
    private boolean enabled = false;

    /** 每次回答注入的资料块数。 */
    private int topK = 6;

    /** 单块目标字符数。 */
    private int chunkChars = 500;

    /** 相邻块的重叠字符数，避免句子被切在边界处。 */
    private int chunkOverlap = 80;

    /** 首次索引延迟（毫秒）：给应用启动和本地模型加载留时间。 */
    private long initialDelayMs = 30_000;

    /** 增量索引周期（毫秒）。 */
    private long refreshMs = 600_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getChunkChars() {
        return chunkChars;
    }

    public void setChunkChars(int chunkChars) {
        this.chunkChars = chunkChars;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public long getRefreshMs() {
        return refreshMs;
    }

    public void setRefreshMs(long refreshMs) {
        this.refreshMs = refreshMs;
    }
}
