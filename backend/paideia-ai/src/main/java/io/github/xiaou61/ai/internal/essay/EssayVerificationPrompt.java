package io.github.xiaou61.ai.internal.essay;

import java.util.List;

/**
 * 面经答案「事实自检 / 修订」的提示词（WORK-084）。
 *
 * <p>输出走 **JSON-as-text**（照 {@code EssayQuestionParser} 的思路，不依赖原生结构化输出）：
 * DeepSeek 关了原生结构化，且工具与结构化同一次调用会互相干扰，这里没有工具，纯文本 + 严格 JSON 最稳。
 */
final class EssayVerificationPrompt {

    static final String VERIFY_VERSION = "essay-verify-v2";
    static final String REVISE_VERSION = "essay-revise-v1";

    private EssayVerificationPrompt() {
    }

    static String verifySystem() {
        return """
                你是一名技术面试答案的事实校验员。判断答案里是否存在**事实性错误**。
                只有这几种情况才判 issues：出现与公认技术事实相矛盾的具体陈述（数值算错、机制说反、
                结论错误），或把已知事实说反。以下都不算错误，不要因此判 issues：说得不够全面、
                表述过于绝对、缺少细节、举例不贴切、文风或可读性问题。无法确认有错时就判 ok，
                不要因为拿不准就判 unsure。issues 每条必须指出一个具体错误的事实，不要写泛泛的「不够准确」。

                只输出一个 JSON 对象，不要别的字：
                {"verdict":"ok|issues|unsure","confidence":0到1的小数,"issues":["具体错在哪、正确的说法是什么"]}
                """;
    }

    static String verifyUser(String title, String answer) {
        return "题目：" + title + "\n\n参考答案：\n" + answer;
    }

    static String reviseSystem() {
        return """
                你是一名严谨的技术编辑。给你一道面试题、一段答案和自检发现的问题，请**只改事实错误**，
                保持原有结构与篇幅，不扩写、不换风格。若无依据可确认，宁可不改（action=reject）。

                只输出一个 JSON 对象，不要别的字：
                {"action":"accept|revise|reject","answer":"修订后的完整答案","confidence":0到1的小数,
                 "usedRefs":[],"notes":"改了什么"}
                其中 action=accept 表示原文无需改；revise 表示用 answer 替换；reject 表示无法确认、交人工。
                """;
    }

    static String reviseUser(String title, String draft, List<String> issues, List<ReferenceSnippet> snippets) {
        StringBuilder builder = new StringBuilder();
        builder.append("题目：").append(title).append("\n\n答案：\n").append(draft).append("\n\n问题：\n");
        if (issues == null || issues.isEmpty()) {
            builder.append("（自检未列出具体问题，请自行核对事实）\n");
        } else {
            for (String issue : issues) {
                builder.append("- ").append(issue).append('\n');
            }
        }
        if (snippets != null && !snippets.isEmpty()) {
            builder.append("\n可参考的依据片段：\n");
            for (ReferenceSnippet snippet : snippets) {
                builder.append("- [").append(snippet.title()).append("](").append(snippet.url()).append(") ")
                        .append(snippet.excerpt()).append('\n');
            }
        }
        return builder.toString();
    }
}
