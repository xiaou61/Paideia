package io.github.xiaou61.ai.internal.rag;

import java.util.List;

/** 文本向量化端口（WORK-072）。实现可替换为自建服务或测试桩。 */
public interface EmbeddingClient {

    /** 服务是否启用。关闭时调用方不再发起网络请求。 */
    boolean enabled();

    /**
     * 把一组文本转成向量；顺序与入参一致。
     *
     * @throws RuntimeException 服务不可用、返回数量不一致或响应格式错误
     */
    List<float[]> embed(List<String> texts);
}
