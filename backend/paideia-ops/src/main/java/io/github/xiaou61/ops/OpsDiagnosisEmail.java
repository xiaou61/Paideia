package io.github.xiaou61.ops;

import java.util.List;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * 把一份 AI 诊断报告组织成一封「先给答案」的邮件。纯函数，便于单测。
 *
 * <p>为什么单独抽出来：邮件的内容组织（主题、正文、Markdown 降级）是纯字符串逻辑，
 * 不该和 JavaMailSender 的装配混在一起——混在一起就只能靠真发一封信来验证。
 */
final class OpsDiagnosisEmail {

    /**
     * 表格是报告里最常用的排版（指标对比、证据清单），所以解析与渲染两侧都要挂扩展，
     * 只挂一侧会得到「解析成了表格、渲染又当段落」的半成品。
     */
    private static final List<org.commonmark.Extension> EXTENSIONS =
            List.of(TablesExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    /** {@code escapeHtml(true)}：报告是模型输出，按不可信文本处理，原文里的 HTML 一律转义。 */
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .build();

    /** 结论最多写这么多字：它是邮件第一屏的「一句话」，不是摘要全文。 */
    static final int MAX_CONCLUSION_CHARS = 240;

    private OpsDiagnosisEmail() {
    }

    /** 主题：带上告警名，收件箱里一眼能看出是哪条告警。 */
    static String subject(String alertName) {
        String name = alertName == null || alertName.isBlank() ? "运维告警" : alertName.strip();
        return "【Paideia 运维·已自动诊断】" + name;
    }

    /** 不带 traceId 的简版；保留给只需结论的场景与旧测试。 */
    static String body(String alertName, String reportMd, String linkUrl) {
        return body(alertName, reportMd, linkUrl, List.of(), null);
    }

    /**
     * 正文：告警抬头 + 诊断结论原文 + 管理端深链接 + 链路追踪。
     *
     * <p>直接复用报告原文（它本来就是「结论/证据/原因/建议」结构），只把 Markdown
     * 标题降级成纯文本分隔符，避免邮件里冒出裸的 {@code ##}。
     *
     * <p>{@code traceIds} 是 traceId 全链路串联的连接键：把它写进邮件，收到告警的人就能
     * 拿着同一个 id 去 Grafana 的 Tempo 里看完整调用链、去 Loki 里捞这次请求的全部日志。
     */
    static String body(String alertName, String reportMd, String linkUrl,
                       List<String> traceIds, String grafanaBaseUrl) {
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
        if (traceIds != null && !traceIds.isEmpty()) {
            sb.append('\n');
            sb.append("链路追踪（traceId 串起日志、指标与调用链）：\n");
            for (String traceId : traceIds) {
                sb.append("  traceId = ").append(traceId).append('\n');
            }
            if (grafanaBaseUrl != null && !grafanaBaseUrl.isBlank()) {
                sb.append("在 Grafana → Explore → Tempo 粘贴上面的 traceId，可看完整调用链：\n  ")
                        .append(grafanaBaseUrl.strip()).append("/explore\n");
            }
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

    /**
     * 从报告里抽出 {@code ## 结论} 那一段的第一句（去格式化符号、截断）。
     *
     * <p>用途是把「一句话结论」提到邮件最上面：报告本身很长，收到告警的人多数只看第一屏。
     * 抽不到就返回空串，由调用方决定是否省略结论框——不要编一句"诊断已完成"充数。
     */
    static String conclusion(String reportMd) {
        if (reportMd == null || reportMd.isBlank()) {
            return "";
        }
        String[] lines = reportMd.split("\n", -1);
        StringBuilder collected = null;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                if (collected != null) {
                    break;
                }
                if (headingText(trimmed).equals("结论")) {
                    collected = new StringBuilder();
                }
                continue;
            }
            if (collected != null && !trimmed.isEmpty()) {
                collected.append(trimmed).append(' ');
            }
        }
        if (collected == null) {
            return "";
        }
        String text = collected.toString()
                .replace("**", "")
                .replace("`", "")
                .replaceAll("^[->*\\s]+", "")
                .strip();
        if (text.length() > MAX_CONCLUSION_CHARS) {
            text = text.substring(0, MAX_CONCLUSION_CHARS).strip() + "…";
        }
        return text;
    }

    /** 去掉行首的 {@code #} 与首尾空白，得到标题文字。 */
    private static String headingText(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }
        return line.substring(i).strip();
    }

    /** Markdown → HTML 片段；报告为空时给一句占位，不返回空白块。 */
    static String renderMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "<p class=\"muted\">（本次诊断没有生成报告正文）</p>";
        }
        Node document = PARSER.parse(markdown);
        return RENDERER.render(document);
    }

    /**
     * HTML 正文：结论提到最上面，报告原文渲染成可直接阅读的排版，链接与 traceId 跟在后面。
     *
     * <p>样式写成一段 {@code <style>} 而不是逐标签内联：邮件里标签很多，逐条内联既难读
     * 又容易漏；能读 {@code <style>} 的客户端（Gmail/Apple Mail）拿到完整排版，
     * 读不了的退回浏览器默认样式，结构仍然成立。
     */
    static String html(String alertName, String reportMd, String linkUrl,
                       List<String> traceIds, String grafanaBaseUrl) {
        String name = alertName == null || alertName.isBlank() ? "（未命名告警）" : alertName.strip();
        String oneLine = conclusion(reportMd);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(htmlEscape(subject(name))).append("</title>")
                .append("<style>")
                .append("body{margin:0;padding:24px 12px;background:#f5f6f8;}")
                .append(".card{max-width:720px;margin:0 auto;background:#fff;border:1px solid #e5e7eb;")
                .append("border-radius:14px;padding:28px 32px;font-family:-apple-system,BlinkMacSystemFont,")
                .append("'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:#1f2937;")
                .append("font-size:15px;line-height:1.75;}")
                .append(".kicker{font-size:12px;letter-spacing:.14em;text-transform:uppercase;color:#6b7280;}")
                .append("h1{font-size:20px;line-height:1.4;margin:6px 0 4px;color:#111827;}")
                .append(".verdict{margin:18px 0 20px;padding:14px 16px;background:#f3f7ff;")
                .append("border-left:3px solid #2563eb;border-radius:0 8px 8px 0;}")
                .append(".verdict .label{font-size:12px;color:#2563eb;letter-spacing:.08em;}")
                .append(".verdict p{margin:4px 0 0;font-size:16px;color:#111827;font-weight:600;}")
                .append(".report h2{font-size:16px;margin:24px 0 8px;padding-bottom:6px;")
                .append("border-bottom:1px solid #eef0f3;color:#111827;}")
                .append(".report h3{font-size:14px;margin:18px 0 6px;color:#374151;}")
                .append(".report p{margin:8px 0;}")
                .append(".report ul,.report ol{margin:8px 0;padding-left:22px;}")
                .append(".report li{margin:4px 0;}")
                .append(".report code{background:#f3f4f6;border-radius:4px;padding:1px 5px;")
                .append("font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px;}")
                .append(".report pre{background:#f8fafc;border:1px solid #eef0f3;border-radius:8px;")
                .append("padding:12px;overflow-x:auto;font-size:13px;}")
                .append(".report table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px;}")
                .append(".report th,.report td{border:1px solid #e5e7eb;padding:8px 10px;text-align:left;}")
                .append(".report th{background:#f9fafb;font-weight:600;}")
                .append(".report blockquote{margin:12px 0;padding:8px 14px;border-left:3px solid #e5e7eb;")
                .append("color:#4b5563;background:#fafafa;}")
                .append(".links{margin-top:26px;padding-top:16px;border-top:1px solid #eef0f3;font-size:14px;}")
                .append(".links a{color:#2563eb;text-decoration:none;}")
                .append(".trace{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px;")
                .append("color:#374151;}")
                .append(".muted{color:#9ca3af;}")
                .append(".foot{margin-top:20px;font-size:12px;color:#9ca3af;}")
                .append("</style></head><body><div class=\"card\">")
                .append("<div class=\"kicker\">Paideia · AIOps 自动诊断</div>")
                .append("<h1>").append(htmlEscape(name)).append("</h1>");
        if (!oneLine.isEmpty()) {
            sb.append("<div class=\"verdict\"><div class=\"label\">结论</div><p>")
                    .append(htmlEscape(oneLine)).append("</p></div>");
        }
        sb.append("<div class=\"report\">").append(renderMarkdown(reportMd)).append("</div>");
        sb.append("<div class=\"links\">");
        if (linkUrl != null && !linkUrl.isBlank()) {
            String href = htmlEscape(linkUrl.strip());
            sb.append("<p>完整报告与证据：<a href=\"").append(href).append("\">")
                    .append(href).append("</a></p>");
        }
        if (traceIds != null && !traceIds.isEmpty()) {
            sb.append("<p>链路追踪（把 traceId 粘到 Grafana → Explore → Tempo 看完整调用链）：</p>")
                    .append("<ul>");
            for (String traceId : traceIds) {
                sb.append("<li class=\"trace\">").append(htmlEscape(traceId)).append("</li>");
            }
            sb.append("</ul>");
            if (grafanaBaseUrl != null && !grafanaBaseUrl.isBlank()) {
                String explore = htmlEscape(grafanaBaseUrl.strip() + "/explore");
                sb.append("<p><a href=\"").append(explore).append("\">打开 Grafana Explore</a></p>");
            }
        }
        sb.append("</div>");
        sb.append("<div class=\"foot\">这是一封自动发送的运维诊断邮件，由 Paideia AIOps 生成。")
                .append("报告由模型根据当时的证据生成，结论需要人工确认后再动手。</div>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    /** HTML 转义：邮件里每一处动态文本都走这里，不在模板里凭记忆写。 */
    static String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
