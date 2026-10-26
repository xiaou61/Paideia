package io.github.xiaou61.knowledge.internal.learning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.knowledge.internal.learning.LearningProgressRules.BlockScore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通过判定与复习阶梯的边界（WORK-020 TASK-007）。
 *
 * <p>这几条都是"看起来显然、写错却不会报错"的地方：刚好 80%、total=0、缺一类记录、
 * 阶梯到顶。全部用整数比较，所以 8/10 与 4/5 应当一致。
 */
class LearningProgressRulesTest {

    private static Map<String, BlockScore> scores(Object... pairs) {
        Map<String, BlockScore> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 3) {
            map.put((String) pairs[index], new BlockScore((Integer) pairs[index + 1], (Integer) pairs[index + 2]));
        }
        return map;
    }

    @Test
    @DisplayName("缺任意一类记录都不算通过：只做小测、只做练习都不行")
    void bothKindsAreRequired() {
        assertThat(LearningProgressRules.passed(scores("q", 3, 3), Map.of())).isFalse();
        assertThat(LearningProgressRules.passed(Map.of(), scores("c", 3, 3))).isFalse();
        assertThat(LearningProgressRules.passed(Map.of(), Map.of())).isFalse();
    }

    @Test
    @DisplayName("刚刚 80% 算通过，79% 不算")
    void eightyPercentIsTheLine() {
        assertThat(LearningProgressRules.passed(scores("c", 4, 5), scores("q", 4, 5))).isTrue();
        assertThat(LearningProgressRules.passed(scores("c", 7, 10), scores("q", 8, 10))).isFalse();
        assertThat(LearningProgressRules.passed(scores("c", 8, 10), scores("q", 8, 10))).isTrue();
    }

    @Test
    @DisplayName("total 为 0 的记录永远不通过（防除零与空块）")
    void zeroTotalIsNeverPassed() {
        assertThat(LearningProgressRules.passed(scores("c", 0, 0), scores("q", 3, 3))).isFalse();
    }

    @Test
    @DisplayName("理论规则只认小测：只有小测也能过，只有动手题反而不过")
    void theoryOnlyNeedsQuiz() {
        assertThat(LearningProgressRules.passed(LearningProgressRules.Rule.THEORY,
                Map.of(), scores("q", 4, 5))).isTrue();
        assertThat(LearningProgressRules.passed(LearningProgressRules.Rule.THEORY,
                scores("c", 5, 5), Map.of())).isFalse();
        assertThat(LearningProgressRules.passed(LearningProgressRules.Rule.THEORY,
                Map.of(), scores("q", 3, 4))).isFalse();
    }

    @Test
    @DisplayName("实战规则只认动手题：只有动手也能过，只有小测反而不过")
    void practiceOnlyNeedsChallenge() {
        assertThat(LearningProgressRules.passed(LearningProgressRules.Rule.PRACTICE,
                scores("c", 5, 5), Map.of())).isTrue();
        assertThat(LearningProgressRules.passed(LearningProgressRules.Rule.PRACTICE,
                Map.of(), scores("q", 5, 5))).isFalse();
        assertThat(LearningProgressRules.passed(LearningProgressRules.Rule.PRACTICE,
                scores("c", 7, 10), Map.of())).isFalse();
    }

    @Test
    @DisplayName("同一条 entry 属于多条轨道时取最严：课程 > 实战 > 理论；无线时按课程")
    void ruleResolutionTakesTheStrictest() {
        assertThat(LearningProgressRules.resolveRule(java.util.List.of(
                LearningProgressRules.Rule.THEORY, LearningProgressRules.Rule.COURSE)))
                .isEqualTo(LearningProgressRules.Rule.COURSE);
        assertThat(LearningProgressRules.resolveRule(java.util.List.of(
                LearningProgressRules.Rule.THEORY, LearningProgressRules.Rule.PRACTICE)))
                .isEqualTo(LearningProgressRules.Rule.PRACTICE);
        assertThat(LearningProgressRules.resolveRule(java.util.List.of(LearningProgressRules.Rule.THEORY)))
                .isEqualTo(LearningProgressRules.Rule.THEORY);
        assertThat(LearningProgressRules.resolveRule(java.util.List.of()))
                .isEqualTo(LearningProgressRules.Rule.COURSE);
    }

    @Test
    @DisplayName("合并取更高分：重做一次手滑不会把已通过的降级")
    void mergeKeepsTheBetterScore() {
        Map<String, BlockScore> merged = LearningProgressRules.merge(
                scores("a", 5, 5), scores("a", 2, 5, "b", 3, 3));
        assertThat(merged.get("a")).isEqualTo(new BlockScore(5, 5));
        assertThat(merged.get("b")).isEqualTo(new BlockScore(3, 3));
    }

    @Test
    @DisplayName("合并比较的是比例：3/4 优于 7/10")
    void mergeComparesRatios() {
        Map<String, BlockScore> merged = LearningProgressRules.merge(scores("a", 7, 10), scores("a", 3, 4));
        assertThat(merged.get("a")).isEqualTo(new BlockScore(3, 4));
    }

    @Test
    @DisplayName("复习阶梯到顶停在 21 天，不再增长")
    void reviewStageStopsAtLastStep() {
        assertThat(LearningProgressRules.intervalDays(0)).isEqualTo(1);
        assertThat(LearningProgressRules.intervalDays(1)).isEqualTo(3);
        assertThat(LearningProgressRules.intervalDays(2)).isEqualTo(7);
        assertThat(LearningProgressRules.intervalDays(3)).isEqualTo(21);
        assertThat(LearningProgressRules.nextStage(3)).isEqualTo(3);
    }

    @Test
    @DisplayName("间隔天数越界按边界处理，不抛错（脏数据不该让读路径失败）")
    void outOfRangeStageIsClamped() {
        assertThat(LearningProgressRules.intervalDays(-5)).isEqualTo(1);
        assertThat(LearningProgressRules.intervalDays(99)).isEqualTo(21);
    }

    @Test
    @DisplayName("到期时间按 UTC 加对应天数")
    void dueAtAddsDays() {
        Instant from = Instant.parse("2026-10-19T00:00:00Z");
        assertThat(LearningProgressRules.dueAt(from, 0)).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
        assertThat(LearningProgressRules.dueAt(from, 3)).isEqualTo(Instant.parse("2026-11-09T00:00:00Z"));
    }
}
