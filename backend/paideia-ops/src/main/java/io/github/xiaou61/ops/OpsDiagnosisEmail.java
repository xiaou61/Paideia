package io.github.xiaou61.ops;

/**
 * 把一份 AI 诊断报告组织成一封「先给答案」的邮件。纯函数，便于单测。
 *
 * <p>为什么单独抽出来：邮件的内容组织（主题、正文、Markdown 降级）是纯字符串逻辑，
 * 不该和 JavaMailSender 的装配混在一起——混在一起就只能靠真发一封信来验证。
 */
final class OpsDiagnosisEmail {

    private OpsDiagnosisEmail() {
    }

    /** 主题：带上告警名，收件箱里一眼能看出是哪条告警。 */
    static String subject(String alertName) {
        String name = alertName == null || alertName.isBlank() ? "运维告警" : alertName.strip();
        return "【Paideia 运维·已自动诊断】" + name;
    }

    /**
     * 正文：告警抬头 + 诊断结论原文 + 管理端深链接。
     *
     * <p>直接复用报告原文（它本来就是「结论/证据/原因/建议」结构），只把 Markdown
     * 标题降级成纯文本分隔符，避免邮件里冒出裸的 {@code ##}。
     */
    static String body(String alertName, String reportMd, String linkUrl) {
        String name = alertName == null || alertName.isBlank() ? "（未命名）" : alertName.strip();
        StringBuilder sb = new StringBuilder();
        sb.append("Paideia 运维 · 自动诊断\n");
        sb.append("告警：").append(name).append('\n');
        sb.append('\n');
        sb.append("下面是 AI 自动诊断结论（先看结论，再看证据与建议）。\n");
        sb.append("────────────────────────\n");
        sb.append(toPlainText(reportMd)).append('\n');
        sb.append("────────────────────────\n");
        if (linkUrl != null && !linkUrl.isBlank()) {
            sb.append("完整报告与证据：").append(linkUrl.strip()).append('\n');
        }
        sb.append('\n');
        sb.append("（这是一封自动发送的运维诊断邮件，由 Paideia AIOps 生成。）\n");
        return sb.toString();
    }

    /** 把 Markdown 的 {@code '# 标题'} 降级成纯文本的 {@code ■ 标题}，其余原样保留。 */
    static String toPlainText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "（本次诊断没有生成报告正文）";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                int i = 0;
                while (i < trimmed.length() && trimmed.charAt(i) == '#') {
                    i++;
                }
                String title = trimmed.substring(i).strip();
                if (!title.isEmpty()) {
                    sb.append("\n■ ").append(title).append('\n');
                    continue;
                }
            }
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }
}
