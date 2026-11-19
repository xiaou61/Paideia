package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpsDiagnosisEmailTest {

    @Test
    void subjectCarriesAlertName() {
        assertThat(OpsDiagnosisEmail.subject("SLO 快烧：登录成功率"))
                .isEqualTo("【Paideia 运维·已自动诊断】SLO 快烧：登录成功率");
    }

    @Test
    void blankAlertNameFallsBack() {
        assertThat(OpsDiagnosisEmail.subject("  ")).contains("运维告警");
    }

    @Test
    void bodyCarriesAnswerAndLink() {
        String body = OpsDiagnosisEmail.body(
                "X", "## 结论\n登录成功率低", "http://h:8081/admin/#/ops/incidents");
        assertThat(body)
                .contains("告警：X")
                .contains("■ 结论")
                .contains("登录成功率低")
                .contains("http://h:8081/admin/#/ops/incidents");
    }

    @Test
    void emptyReportIsHandled() {
        assertThat(OpsDiagnosisEmail.body("X", null, "")).contains("没有生成报告正文");
    }

    @Test
    void bodyCarriesTraceIdsAndGrafanaLink() {
        String body = OpsDiagnosisEmail.body("X", "## 结论", "",
                java.util.List.of("abc123abc123abc123abc123abc12345"),
                "http://h:8081/api/v1/ops/grafana");
        assertThat(body)
                .contains("链路追踪")
                .contains("traceId = abc123abc123abc123abc123abc12345")
                .contains("http://h:8081/api/v1/ops/grafana/explore");
    }

    @Test
    void markdownHeadingDegradesToPlainText() {
        assertThat(OpsDiagnosisEmail.toPlainText("### 证据\n- a"))
                .contains("■ 证据")
                .doesNotContain("###");
    }

    @Test
    void conclusionIsHoistedToTheTopOfTheHtml() {
        String html = OpsDiagnosisEmail.html("CPU 相对 7 天基线异常",
                "## 结论\n**进程重启 4 次**，不是崩溃。\n\n## 证据\n- PID 变了四回", null, null, null);

        assertThat(html)
                .contains("class=\"verdict\"")
                .contains("进程重启 4 次，不是崩溃。")
                // 结论只出现一次在顶部的提示框里，正文里仍是原始 Markdown 渲染的 h2
                .contains("<h2>结论</h2>");
    }

    @Test
    void tableInReportBecomesRealTable() {
        String html = OpsDiagnosisEmail.html("X",
                "## 证据\n\n| 指标 | 值 |\n| --- | --- |\n| 重启 | 4 |\n", null, null, null);

        assertThat(html)
                .contains("<table>")
                .contains("<th>指标</th>")
                .contains("<td>4</td>");
    }

    @Test
    void rawHtmlInReportIsEscaped() {
        String html = OpsDiagnosisEmail.html("X",
                "## 结论\n<script>alert(1)</script>", null, null, null);

        assertThat(html)
                .doesNotContain("<script>")
                .contains("&lt;script&gt;");
    }

    @Test
    void alertNameAndLinksAreEscaped() {
        String html = OpsDiagnosisEmail.html("<img src=x onerror=alert(1)>",
                "## 结论\nok", "http://h:8081/admin?a=1&b=2", null, null);

        assertThat(html)
                .doesNotContain("<img src=x")
                .contains("&lt;img src=x onerror=alert(1)&gt;")
                .contains("a=1&amp;b=2");
    }

    @Test
    void emptyReportStillRendersUsableMail() {
        String html = OpsDiagnosisEmail.html("X", null, null, null, null);

        assertThat(html)
                .contains("没有生成报告正文")
                .doesNotContain("class=\"verdict\"");
    }

    @Test
    void conclusionFallsBackToEmptyWhenSectionMissing() {
        assertThat(OpsDiagnosisEmail.conclusion("## 证据\n- a")).isEmpty();
        assertThat(OpsDiagnosisEmail.conclusion(null)).isEmpty();
    }

    @Test
    void longConclusionIsTruncated() {
        String longText = "很长的结论".repeat(100);

        String conclusion = OpsDiagnosisEmail.conclusion("## 结论\n" + longText + "\n\n## 证据\n- a");

        assertThat(conclusion).hasSize(OpsDiagnosisEmail.MAX_CONCLUSION_CHARS + 1).endsWith("…");
    }
}
