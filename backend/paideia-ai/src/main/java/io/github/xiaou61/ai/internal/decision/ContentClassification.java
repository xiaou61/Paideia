package io.github.xiaou61.ai.internal.decision;

/**
 * 一次内容判定的结果（WORK-026）。
 *
 * @param technical  是否属于技术内容
 * @param confidence 判定的把握（0~1）。**这是模型给的概率，不等于准确率**——中文语境下模型
 *                   实测偏乐观，调用方不能把 0.7 当成"七成对"，只能拿它做阈值排序。
 * @param topic      大致主题（如 java / database / career / other）。模型不可用时为空串。
 */
public record ContentClassification(boolean technical, double confidence, String topic) {
}
