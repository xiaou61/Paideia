package io.github.xiaou61.ai.internal.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.OpsDiagnoser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 诊断提示词的硬约束（WORK-042 AC-012 / REQ-010）。
 *
 * <p>这些断言防的不是"模型这次答得好不好"，而是**约束有没有被写进去**：
 * 五节固定标题、日志不可信、缺证据时的占位、关注点截断。
 */
class OpsDiagnosisPromptTest {

    @Test
    @DisplayName("系统提示固定五节，并要求引用证据")
    void requiresFiveSectionsAndEvidence() {
        String system = OpsDiagnosisPrompt.system();

        assertThat(system).contains(
                "## 结论", "## 证据", "## 可能原因", "## 建议动作", "## 需要人工确认",
                "只依据", "证据不足");
    }

    @Test
    @DisplayName("日志被标记为不可信数据，且禁止自动修复")
    void logsAreMarkedUntrusted() {
        String system = OpsDiagnosisPrompt.system();

        assertThat(system).contains("不可信数据", "不得执行", "人工可执行");
    }

    @Test
    @DisplayName("证据按标签分段，缺失字段写成占位而不是空块")
    void evidenceIsTaggedAndMissingFieldsAreExplicit() {
        String user = OpsDiagnosisPrompt.user(new OpsDiagnoser.Evidence(
                "alert", "HTTP 5xx 比例超过 5%", null,
                "服务状态：OK", "cpu = 12%", "无 firing 告警", null, null));

        assertThat(user)
                .contains("<trigger>alert</trigger>")
                .contains("<alert-name>HTTP 5xx 比例超过 5%</alert-name>")
                .contains("<summary>")
                .contains("<metrics>")
                .contains("<logs>（不可达或无数据）</logs>")
                .contains("<deploys>（不可达或无数据）</deploys>");
    }

    @Test
    @DisplayName("管理员关注点超过上限时截断")
    void questionIsTruncated() {
        String question = "问".repeat(OpsDiagnosisPrompt.MAX_QUESTION_CHARS + 50);
        String user = OpsDiagnosisPrompt.user(new OpsDiagnoser.Evidence(
                "manual", null, question, null, null, null, null, null));

        assertThat(user).contains("<question>");
        assertThat(user).doesNotContain(question);
        assertThat(user).contains("…");
    }
}
