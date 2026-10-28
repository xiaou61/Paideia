package io.github.xiaou61.ai.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 评测报告的空结果分支（WORK-050 AC-011）。
 *
 * <p>它单独存在是因为"没有凭据 → 整类跳过"这条路**无法由被跳过的那个类自己断言**。
 * 这里只验一件最关键的事：一轮都没跑完时，报告必须写"未评测"，不能渲染成"0 通过"。
 * 本项目有先例：绿 BUILD 里躺着 115 条被跳过的集成测试，而没人看见。
 */
class MemoryEvalReportTest {

    @Test
    @DisplayName("一条都没跑完时报告写「未评测」，不出现 0/0 的表格")
    void emptyResultSaysNotEvaluated() {
        String report = MemoryEvalTest.render(MemoryEvalTest.Metrics.empty(), 40, 40, List.of());

        assertThat(report).contains("未评测");
        assertThat(report).doesNotContain("0 / 0");
    }
}
