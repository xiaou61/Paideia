package io.github.xiaou61.ai.internal.digest;

import java.util.List;

/**
 * 一次 AI 压缩用的提示词（WORK-027）。
 *
 * <p><b>一次调用干两件事</b>：挑哪些值得看 + 给每条写中文标题与一句话摘要。
 * 合并成一次是为了成本——照 WORK-025「每条一次」的形态，一天 40~60 条就是几十次调用。
 *
 * <p><b>为什么输出带 fingerprint 而不是靠顺序</b>：模型可能少返回条目，顺序也会变。
 * 用指纹回填是唯一不会错位的做法（评审焦点第一条）。
 */
final class DigestPrompt {

    /** 日报压缩提示词的版本号，写进 `ai_runs.prompt_version`。改规则必须改它。 */
    static final String VERSION = "digest-v3";

    private DigestPrompt() {
    }

    /**
     * 系统提示词。
     *
     * <p><b>为什么要求"每一条都要写"而不是"挑重要的写"</b>：2026-10-21 真连上游实测，
     * 按"可以挑掉不重要的"这条去问，5 条只返回 3 条（丢 60%）。而编排层**把所有候选都入库**，
     * 被挑掉的那两条会落到 {@code degraded}、在界面上显示「这条还没写好摘要」——
     * 也就是说"挑"并没有真的过滤掉任何东西，只是让三分之一的卡片看起来是坏的。
     *
     * <p>降级态的语义应该只表示一件事：**AI 这次没写成**（凭据缺失、上游失败、超时），
     * 对应 AC-005。让模型主动少写会让这个语义失真。
     */
    static String system() {
        return """
                你是一个中文技术媒体编辑，负责把当天的技术资讯整理成一份速递。

                规则：
                1. 只根据给定信息写，**不得编造事实**。信息不足时就说标题说了什么，不要补背景。
                2. 标题用中文。原文是中文的保持原标题，不要改写。
                3. 摘要一句话，40 字以内，说清"这事是什么/为什么值得看"。
                4. **每一条都要写**，不要挑：输入的每一条都要在输出里出现一次，
                   也不得返回输入里没有的指纹。
                5. 每条必须原样带回输入的 fingerprint 值，用于回填。
                6. 给每条打一个 0-100 的重要性分（heat），用来在组内排序：
                   90 以上 = 当天必看的大新闻，60 左右 = 值得一看，30 以下 = 可略过。
                   不要给所有条目同一个分数，也不要按顺序递减。
                7. 来源是 GitHub 的条目，标题必须**原样保留** `owner/repo`，不要翻译、不要加中文名；
                   中文只写在摘要里。

                只输出 JSON，不要 markdown 代码围栏，不要任何解释文字：
                {"items":[{"fingerprint":"...","title":"中文标题","summary":"一句话摘要","heat":0}]}
                """;
    }

    /**
     * 用户消息：把候选池平铺成可读清单。
     *
     * <p>每条带上 fingerprint、源名、热度与摘要线索——模型据此判断"哪条重要"。
     * 线索已由抓取层截断到 200 字，这里不再处理长度。
     */
    static String user(List<FetchedItem> items, List<String> fingerprints) {
        StringBuilder builder = new StringBuilder();
        builder.append("今天的候选内容如下，共 ").append(items.size()).append(" 条。\n\n");
        for (int index = 0; index < items.size(); index++) {
            FetchedItem item = items.get(index);
            builder.append(index + 1).append(". fingerprint: ").append(fingerprints.get(index)).append('\n');
            builder.append("   来源: ").append(item.sourceName()).append('\n');
            builder.append("   标题: ").append(item.title()).append('\n');
            if (item.heat() != null) {
                builder.append("   热度: ").append(item.heat()).append('\n');
            }
            if (item.starsWeek() != null) {
                builder.append("   本周: ").append(item.starsWeek()).append('\n');
            }
            if (item.meta() != null && !item.meta().isBlank()) {
                builder.append("   附加: ").append(item.meta()).append('\n');
            }
            if (item.summaryHint() != null && !item.summaryHint().isBlank()) {
                builder.append("   原文摘要: ").append(item.summaryHint()).append('\n');
            }
            builder.append('\n');
        }
        builder.append("请按上面的规则输出 JSON。");
        return builder.toString();
    }
}
