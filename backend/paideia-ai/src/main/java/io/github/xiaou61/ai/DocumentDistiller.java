package io.github.xiaou61.ai;

/**
 * 文档蒸馏端口（WORK-076）：把一份文档的纯文本变成一条已校验的条目草稿。
 *
 * <p>签名里没有任何框架类型（D-09）：实现放 {@code internal.provider}，由 AgentScope 承载；
 * 换实现或升级框架都不动调用方。端口不做缓存、不管凭据、不做限流——调用方负责取凭据、计费与落库。
 *
 * <p>失败语义：上游不可用、被拒、返回空内容或字段校验不通过，一律抛
 * {@link io.github.xiaou61.platform.BizException}；调用方**不得把失败结果落库**。
 */
public interface DocumentDistiller {

    /**
     * @param fileName 原始文件名（进提示词，帮助模型抓住主题；可为空）
     * @param text     已抽取的文档纯文本（超长时由实现按段落截断并在结果里标记）
     * @param access   本次调用用的凭据（明文只在这一次调用期间存在）
     */
    DistilledDocument distill(String fileName, String text, ModelAccess access);
}
