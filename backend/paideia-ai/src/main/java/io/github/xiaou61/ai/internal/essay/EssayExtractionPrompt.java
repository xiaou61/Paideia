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

    static final String VERSION = "essay-extract-v4";

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

                **解析的排版要求（重要，决定读者愿不愿意看，务必遵守）**：
                - 用 markdown 写，**一行一句话或一个要点，绝对不要把全部内容挤成一大段**。
                - 第一行先给结论（一句话；可把核心词加粗）。
                - 需要并列罗列时用 `-` 无序列表，每行一个要点；有先后步骤时用 `1.` 有序列表。
                - 关键术语、对比项用 **加粗** 标出。
                - 篇幅 120~260 字。答案内部**不要写 # 标题**（题目本身已是标题），尽量不放代码块，
                  确有必要只放一两行。

                4. **解析必须对上题目**：讲的就是题目问的那件事，**不要用复述题目充数**；
                   原帖里没有能支撑这道题的信息就不要硬编，宁可少出一题。拿不准的，少出错比多出错好。

                只输出一个 JSON 对象，不要解释、不要用 markdown 代码块包裹（answer 里的换行用 \\n 转义）：
                {"questions":[{"title":"题目本身，一句话，不超过 60 字","answer":"解析（markdown，多行，含列表与加粗）"}]}

                题目写法要求：像面试官会问的那样，而不是像标题。例如
                "HashMap 为什么线程不安全" 好过 "HashMap 线程安全性"。

                **篇幅纪律**：一次最多 5 道题、每道解析控制在 260 字内，总输出不要超过 1500 字。
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
