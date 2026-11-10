package io.github.xiaou61.ai.internal.essay;

import java.util.List;

/**
 * 从面经正文里抽题 + 生成解析的提示词（WORK-025）。
 *
 * <p><b>为什么不直接把帖子原文入库</b>：面经是用户 UGC，里面混着公司名、薪资、个人经历、
 * 作者昵称。逐字入库既是转载，也不适合做学习内容。这里要的是"从这堆叙述里挑出技术问题，
 * 再用我们自己的话讲一遍"。
 *
 * <p><b>只输出 JSON，不走结构化输出通道</b>：与 {@code MemoryExtractionPrompt} 同一理由——
 * DeepSeek 的结构化输出走"合成工具 + 强制工具调用"的回退路径，而本项目已知工具调用与结构化
 * 输出不能在同一次调用里并用。校验交给 {@link EssayQuestionParser}。
 *
 * <p><b>{@link #VERSION} 单独一个</b>：将来出现"同一篇面经抽出的题不一样"时，要能分辨是
 * 抽取提示词改了还是模型变了。
 */
final class EssayExtractionPrompt {

    static final String VERSION = "essay-extract-v2";

    private EssayExtractionPrompt() {
    }

    static String system() {
        return """
                你在把一个程序员的面经帖子整理成可复习的题目。

                **挑出帖子里所有的技术问题，最多 5 道**。优先挑面试官真会问、能讲清机制的那种；
                同一主题的多个小问合成一道，不要凑数。
                如果这篇帖子没有任何技术问题，输出 {"questions":[]}——这是合法结果，不要硬凑。

                三条硬约束：
                1. **只提取技术问题**。帖子里关于学校安排、薪资、职业规划、面试感受、公司氛围的内容，
                   一律丢掉，不要出现在输出里。
                2. **不出现任何个人信息**：不写公司名、不写薪资数字、不写作者昵称、不写"我"的经历。
                   题目本身要脱离这篇帖子也能看懂。
                3. **解析是你自己写的**，不是从帖子里抄的答案（帖子里通常也没有答案）。
                   **120~200 字**，先给结论，再说原因；有对比就列出来；能举例就举例。

                只输出一个 JSON 对象，不要解释、不要用 markdown 代码块包裹：
                {"questions":[{"title":"题目本身，一句话，不超过 60 字","answer":"解析，120~200 字，可用 markdown"}]}

                题目写法要求：像面试官会问的那样，而不是像标题。例如
                "HashMap 为什么线程不安全" 好过 "HashMap 线程安全性"。

                **篇幅纪律**：一次最多 5 道题、每道解析控制在 200 字内，总输出不要超过 1200 字。
                写多了会超出输出上限被截断，截断的结果是**整篇作废**——宁可少而完整。
                """;
    }

    static String user(String postTitle, String postBody) {
        return "帖子标题：" + postTitle + "\n\n帖子正文：\n" + postBody;
    }

    /**
     * 抽出来的一道题。
     *
     * @param title  题目
     * @param answer 我们自己写的解析（markdown）
     */
    record ExtractedQuestion(String title, String answer) {
    }

    /** 一次抽取的结果：可能为空（帖子里没有技术问题）。 */
    record Extraction(List<ExtractedQuestion> questions) {
    }
}
