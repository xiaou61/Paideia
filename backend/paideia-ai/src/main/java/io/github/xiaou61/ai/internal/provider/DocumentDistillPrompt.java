package io.github.xiaou61.ai.internal.provider;

/** 文档蒸馏提示词（WORK-076）。只给「结构化 JSON」一种输出形态，便于解析。 */
final class DocumentDistillPrompt {

    private DocumentDistillPrompt() {
    }

    static String system() {
        return """
                你是知识库内容编辑。给定一份文档的纯文本，把它整理成一条可发布的知识条目。
                只输出一个 JSON 对象，不要任何额外文字，不要代码围栏。字段：
                  title            —— 条目标题，精简准确，不超过 200 字；
                  bodyMarkdown     —— 条目正文，用 Markdown 组织（小标题/列表/必要时代码块），保留原文关键信息，不编造；
                  suggestedConcepts—— 0 到 10 个与正文相关的知识点名称，字符串数组，可为空数组。
                示例：{"title":"TCP 三次握手","bodyMarkdown":"## 过程\\n1. ...","suggestedConcepts":["三次握手","SYN"]}
                """;
    }

    static String user(String fileName, String text) {
        String name = fileName == null || fileName.isBlank() ? "（未命名文档）" : fileName;
        return "文件名：" + name + "\n\n文档正文如下：\n" + text;
    }
}
