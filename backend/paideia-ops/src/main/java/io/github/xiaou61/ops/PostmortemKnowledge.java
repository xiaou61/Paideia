package io.github.xiaou61.ops;

import java.time.Instant;
import java.util.List;

/**
 * 历史复盘的知识召回（WORK-086 REQ-007）。
 *
 * <p>只认 {@code APPROVED}：模型自己写的报告未经人确认，把它喂回下一次诊断等于让幻觉自我循环——
 * 一次错判会被后续每次诊断当作"历史经验"引用，而且越引用越像事实。
 *
 * <p>召回失败一律返回空表：知识是**加分项**，取不到就当作没有，绝不能让诊断因为知识库挂了而失败。
 */
interface PostmortemKnowledge {

    /**
     * @param alertName  本次告警名，可空（同名告警的复盘优先）
     * @param signatures 日志签名拼接串，空串表示没有可用的关键词
     * @param limit      最多几条
     */
    List<Hit> recall(String alertName, String signatures, int limit);

    /** 一条命中。`rootCause`/`actions` 已截断，供拼注入文本。 */
    record Hit(long id, String title, Instant approvedAt, String rootCause, String actions) {
    }
}
