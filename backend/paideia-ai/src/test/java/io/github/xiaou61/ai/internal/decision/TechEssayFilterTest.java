package io.github.xiaou61.ai.internal.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 面经技术内容过滤（WORK-026）。
 *
 * <p>两条线都要测：规则本身判得准，以及**模型不可用时不影响结论**——后者是这条链能不能
 * 放在定时任务里的前提，模型挂了不该让整批内容丢。
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
        String post = "面经：1、HashMap为什么线程不安全 2、说说JVM内存模型 3、MySQL索引失效的场景 4、Spring事务传播";
        assertThat(filter.isTechnical(post)).isTrue();
    }

    @Test
    void pureInformationPostIsRejectedByRuleAlone() {
        TechEssayFilter filter = new TechEssayFilter(UNAVAILABLE);
        assertThat(filter.isTechnical("今日战绩：1面 1offer，终于找到实习了！感谢牛客")).isFalse();
    }

    @Test
    void noTermMeansNoModelCall() {
        // 一个命中 0 种的帖子，不应该把模型叫起来——那是白花 200ms。
        ContentClassifier counting = text -> {
            throw new AssertionError("命中 0 个技术词时不该调用模型");
        };
        assertThat(new TechEssayFilter(counting).isTechnical("今天天气不错，去公园走走")).isFalse();
    }

    @Test
    void borderlineCaseDefersToModel() {
        // 只命中 1 种（java），落在边界地带：规则判不出，应当问模型。
        String borderline = "我是java开发，聊聊求职";
        assertThat(new TechEssayFilter(ALWAYS_TECH).isTechnical(borderline)).isTrue();
        // 同一个输入，模型不可用时退回规则：1 种 < 3，判成非技术。
        assertThat(new TechEssayFilter(UNAVAILABLE).isTechnical(borderline)).isFalse();
    }

    @Test
    void blankTextIsNeverTechnical() {
        TechEssayFilter filter = new TechEssayFilter(ALWAYS_TECH);
        assertThat(filter.isTechnical(null)).isFalse();
        assertThat(filter.isTechnical("   ")).isFalse();
    }
}
