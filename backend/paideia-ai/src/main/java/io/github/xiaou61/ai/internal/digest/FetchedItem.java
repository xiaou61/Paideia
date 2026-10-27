package io.github.xiaou61.ai.internal.digest;

/**
 * 一个源抓回来的一条（WORK-027、WORK-041）。
 *
 * @param title       原始标题（可能是英文）。GitHub 条目固定为 `owner/repo`，不被 AI 改写。
 * @param url         原文链接
 * @param summaryHint 源自己给的摘要线索（RSS 的 description、HN 的标题上下文），**已截断到 200 字**。
 *                    它只是给模型的输入，不会直接展示给用户——展示用我们自己写的中文摘要。
 * @param heat        源自身的信号：HN 点数、GitHub 昨日新增 star；其它源为 {@code null}。
 *                    GitHub 的 `heat` 就是 `d1`，也是入库后的默认排序键。
 * @param starsWeek   GitHub 的近 7 日新增；其它源为 {@code null}。
 * @param meta        一条副信息（作者、评论数），直接给人看
 * @param language    仓库主语言（GitHub 条目才有）。**独立字段而不是塞进 meta**：
 *                    前端要按它分组，塞进文本就只剩装饰作用。
 * @param publishedAt 原文发布时间（epoch 毫秒）；源不提供时为抓取时刻
 * @param sourceKey   源标识，如 qbitai / github-trending
 * @param sourceName  源的显示名，如「量子位」；页面不可达时 GitHub 用「GitHub 新项目」
 * @param groupKey    news | github | community | hackernews，决定前端分组
 */
public record FetchedItem(String title, String url, String summaryHint, Integer heat,
                          Integer starsWeek, String meta, String language, long publishedAt,
                          String sourceKey, String sourceName, String groupKey) {
}
