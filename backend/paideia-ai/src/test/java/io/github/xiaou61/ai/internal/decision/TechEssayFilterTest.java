package io.github.xiaou61.ai.internal.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 面经技术内容过滤（WORK-026）。
 *
 * <p>两条线都要测：规则本身判得准，以及**模型不可用时不影响结论**——后者是这条链能不能
 * 放在定时任务里的前提，模型挂了不该让整批内容丢。
 *
 * <p>WORK-070 起返回值带上了「是否问了模型 / 是否改判 / 主题」，这些数字要进跑批日志，
 * 所以断言改成逐字段检查，而不是只看布尔结论。
 */
class TechEssayFilterTest {

    /** 什么都不判的模型，模拟"未启用 / 连不上"。 */
    private static final ContentClassifier UNAVAILABLE = text -> Optional.empty();

    /** 永远说"是技术内容"的模型，用来验证边界地带确实交给了模型。 */
    private static final ContentClassifier ALWAYS_TECH = text -> Optional.of(new ContentClassification(true, 0.9, "java"));

    @Test
    void countsDistinctTermsNotOccurrences() {
        TechEssayFilter filter = new TechEssayFilter(UNAVAILABLE);
        // "java" 出现三次，但只有一种技术词——按种类数算，应当只算 1。
        assertThat(filter.countDistinctTerms("java java java 我要投递简历")).isEqualTo(1);
    }

    @Test
    void realTechnicalPostIsAcceptedByRuleAlone() {
        TechEssayFilter filter = new TechEssayFilter(UNAVAILABLE);
        // 命中 6 种英文技术词，离阈值（3）足够远，规则自己就下结论
        String post = "面经：HashMap JVM MySQL Spring Redis Kafka 都在问";
        TechEssayFilter.Verdict verdict = filter.judge(post, false);

        assertThat(verdict.technical()).isTrue();
        // 规则自己就判定了，不该去问模型，也就没有 topic
        assertThat(verdict.askedModel()).isFalse();
        assertThat(verdict.borderline()).isFalse();
        assertThat(verdict.topic()).isEmpty();
    }

    @Test
    void pureInformationPostIsRejectedByRuleAlone() {
        TechEssayFilter filter = new TechEssayFilter(UNAVAILABLE);
        assertThat(filter.judge("今日战绩：1面 1offer，终于找到实习了！感谢牛客", false).technical()).isFalse();
    }

    @Test
    void noTermMeansNoModelCall() {
        // 一个命中 0 种的帖子，不应该把模型叫起来——那是白花 200ms。
        ContentClassifier counting = text -> {
            throw new AssertionError("命中 0 个技术词时不该调用模型");
        };
        assertThat(new TechEssayFilter(counting).judge("今天天气不错，去公园走走", false).technical()).isFalse();
    }

    @Test
    void borderlineCaseDefersToModel() {
        // 只命中 1 种（java），落在边界地带：规则判不出，应当问模型。
        String borderline = "我是java开发，聊聊求职";
        TechEssayFilter.Verdict rescued = new TechEssayFilter(ALWAYS_TECH).judge(borderline, false);
        assertThat(rescued.technical()).isTrue();
        assertThat(rescued.borderline()).isTrue();
        assertThat(rescued.askedModel()).isTrue();
        // 规则兜底会判 false（1 种 < 3），模型判 true —— 这就是一次改判
        assertThat(rescued.changedByModel()).isTrue();

        // 同一个输入，模型不可用时退回规则：1 种 < 3，判成非技术。没给结论不算改判。
        TechEssayFilter.Verdict fallback = new TechEssayFilter(UNAVAILABLE).judge(borderline, false);
        assertThat(fallback.technical()).isFalse();
        assertThat(fallback.askedModel()).isTrue();
        assertThat(fallback.changedByModel()).isFalse();
    }

    @Test
    void needTopicAsksModelEvenForClearlyTechnicalPost() {
        TechEssayFilter filter = new TechEssayFilter(ALWAYS_TECH);
        String clear = "面经：HashMap JVM MySQL Spring Redis Kafka 都在问";

        // 不分流时不问；要分流时问一次，只为了拿 topic，结论仍按规则。
        TechEssayFilter.Verdict routed = filter.judge(clear, true);
        assertThat(routed.technical()).isTrue();
        assertThat(routed.askedModel()).isTrue();
        assertThat(routed.changedByModel()).isFalse();
        assertThat(routed.topic()).isEqualTo("java");
    }

    @Test
    void blankTextIsNeverTechnical() {
        TechEssayFilter filter = new TechEssayFilter(ALWAYS_TECH);
        assertThat(filter.judge(null, false).technical()).isFalse();
        assertThat(filter.judge("   ", false).technical()).isFalse();
    }
}
