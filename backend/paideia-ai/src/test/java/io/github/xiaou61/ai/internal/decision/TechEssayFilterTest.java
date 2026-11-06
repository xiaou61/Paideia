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

    @Test
    void chineseTermsMatchInsideChineseSentences() {
        TechEssayFilter filter = new TechEssayFilter(UNAVAILABLE);
        // 2026-11-06 之前中文分支带 \b，而中文不是 \w —— 这条文本只会命中 mysql 一种，
        // 「索引/事务/隔离级别/死锁」全部漏掉。这条用例就是那个缺陷的守卫。
        assertThat(filter.countDistinctTerms("MySQL索引失效，事务隔离级别怎么讲，死锁怎么排查"))
                .isEqualTo(5);
    }

    @Test
    void frontendAndTestingPostsAreRecognisedByRuleAlone() {
        TechEssayFilter filter = new TechEssayFilter(UNAVAILABLE);

        // 纯中文、没有一个英文技术词：只靠词表的中文分支也要能判出技术帖。
        assertThat(filter.judge("前端一面：浏览器的跨域怎么处理，事件循环里微任务和宏任务的顺序，"
                + "闭包为什么会造成内存泄漏，防抖节流写过吗", false).technical()).isTrue();
        assertThat(filter.judge("测试一面：测试用例怎么设计，等价类和边界值各举一个，"
                + "接口自动化的断言怎么写，压测用什么工具，缺陷的生命周期是什么", false).technical()).isTrue();
    }

    @Test
    void dataEngineeringPostsAreRecognisedByRuleAlone() {
        TechEssayFilter filter = new TechEssayFilter(UNAVAILABLE);
        assertThat(filter.judge("数据开发一面：数仓分层怎么设计，出现数据倾斜怎么调，"
                + "维度建模里的事实表和维度表怎么分，实时计算用 flink 还是 spark", false).technical())
                .isTrue();
    }

    @Test
    void jobHuntingComplaintStillNotTechnicalAfterWideningTerms() {
        TechEssayFilter filter = new TechEssayFilter(UNAVAILABLE);
        // 扩词表最容易出的事故：把求职吐槽判成技术帖。这条守的是"扩词不等于放宽阈值"。
        assertThat(filter.judge("今天面了两家，第一家的面试官很热情，聊了一个小时，第二家直接挂我",
                false).technical()).isFalse();
        assertThat(filter.judge("收到 offer 了，纠结去大厂还是留在家乡，薪资差不多", false).technical())
                .isFalse();
    }
}
