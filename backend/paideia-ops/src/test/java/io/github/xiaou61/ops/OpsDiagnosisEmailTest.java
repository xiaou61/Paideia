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
    void markdownHeadingDegradesToPlainText() {
        assertThat(OpsDiagnosisEmail.toPlainText("### 证据\n- a"))
                .contains("■ 证据")
                .doesNotContain("###");
    }
}
