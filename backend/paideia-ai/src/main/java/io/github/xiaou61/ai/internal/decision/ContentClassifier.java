package io.github.xiaou61.ai.internal.decision;

import io.github.xiaou61.ai.EntrySummarizer;
import java.util.Optional;

/**
 * 内容判定端口：给一段文本，回答"这是不是技术内容、属于哪个主题"（WORK-026）。
 *
 * <p><b>它和 {@link EntrySummarizer} 是两类东西</b>：那个生成文字，这个只做一次判断、不产出
 * 任何文本。所以它便宜且快（本机实测 150~260ms），适合放在流程前端当过滤器，而不是让大模型
 * 为一次分类花几秒写一段分析。
 *
 * <p><b>失败语义与精简端口不同：这里不抛异常，返回 empty。</b> 判定是"可以没有"的能力——
 * 判不了就退回规则判断，不该让一条内容因为判定服务挂了而丢。调用方必须自己准备降级路径。
 *
 * <p>签名里没有框架类型（D-09）：换个决策模型只改实现。
 */
public interface ContentClassifier {

    /**
     * @param text 待判定的文本（过长时由实现自行截断）
     * @return 判定结果；**模型未启用、不可达、超时或置信度不足时返回 empty**
     */
    Optional<ContentClassification> classify(String text);
}
