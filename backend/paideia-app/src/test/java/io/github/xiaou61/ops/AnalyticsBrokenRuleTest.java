package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 断点判定（WORK-045 AC-003）。
 *
 * <p>这是整页最容易被改错、也最有信息量的一处规则，所以单列成纯函数测试：
 * 「上游也是 0」必须不算断点，否则一条从来没跑过的链会被涂成一片红。
 */
class AnalyticsBrokenRuleTest {

    @Test
    @DisplayName("本节点为 0 且上游 > 0 → 断点")
    void zeroAfterUpstreamIsBroken() {
        assertThat(AnalyticsService.isBroken(new long[] {55, 20, 1, 0}, 3)).isTrue();
    }

    @Test
    @DisplayName("上游也是 0 → 不算断点（整条链没跑）")
    void zeroAfterZeroIsNotBroken() {
        assertThat(AnalyticsService.isBroken(new long[] {0, 0, 0, 0}, 3)).isFalse();
        assertThat(AnalyticsService.isBroken(new long[] {10, 0, 0, 0}, 2)).isFalse();
    }

    @Test
    @DisplayName("第一个节点永远不算断点")
    void firstNodeIsNeverBroken() {
        assertThat(AnalyticsService.isBroken(new long[] {0, 5, 5, 5}, 0)).isFalse();
    }

    @Test
    @DisplayName("节点非 0 就不算断点")
    void nonZeroIsNotBroken() {
        assertThat(AnalyticsService.isBroken(new long[] {1, 1, 1, 1}, 2)).isFalse();
    }

    @Test
    @DisplayName("三态：0 是 zero，非 0 是 ok")
    void stateFromCount() {
        assertThat(AnalyticsService.stateOf(new long[] {0, 3}, 0)).isEqualTo(AnalyticsView.State.ZERO);
        assertThat(AnalyticsService.stateOf(new long[] {0, 3}, 1)).isEqualTo(AnalyticsView.State.OK);
    }
}
