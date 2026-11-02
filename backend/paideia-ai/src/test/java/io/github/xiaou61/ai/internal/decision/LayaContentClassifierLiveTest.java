package io.github.xiaou61.ai.internal.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真连 laya-serve 的验证（WORK-026）。
 *
 * <p>**默认跳过**：它需要一个跑着的决策服务，CI 和没有部署的机器上都不该有。要跑就这样：
 * <pre>
 * mvn -pl paideia-ai test -Dtest=LayaContentClassifierLiveTest \
 *     -Dpaideia.laya.live=true -Dpaideia.laya.base-url=http://127.0.0.1:8091
 * </pre>
 *
 * <p>为什么要留这条：单测用的是桩，桩永远说"是"，看不出真实模型的行为。这条的价值是
 * 把"服务真的能返回我们要的结构"验掉——一旦上游改了响应格式，它会立刻红。
 */
@EnabledIfSystemProperty(named = "paideia.laya.live", matches = "true")
class LayaContentClassifierLiveTest {

    private static DecisionProperties liveProperties() {
        DecisionProperties properties = new DecisionProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(System.getProperty("paideia.laya.base-url", "http://127.0.0.1:8091"));
        properties.setApiKey(System.getProperty("paideia.laya.api-key", ""));
        properties.setTimeoutMs(10000);
        return properties;
    }

    @Test
    void liveServerClassifiesTechnicalAndNonTechnicalText() {
        LayaContentClassifier classifier = new LayaContentClassifier(liveProperties());

        Optional<ContentClassification> technical = classifier.classify(
                "面经：1、HashMap为什么线程不安全 2、说说JVM内存模型 3、MySQL索引失效的场景有哪些");
        assertThat(technical).as("技术面经应当被判为技术内容").isPresent();
        assertThat(technical.get().technical()).isTrue();

        Optional<ContentClassification> career = classifier.classify(
                "今日战绩：1面 1offer，终于找到实习了！感谢牛客");
        assertThat(career).as("纯求职信息帖不应被判为技术内容").isEmpty();
    }

    /**
     * 已知的判错样例，留在这里是为了**让错误一直是可见的**。
     *
     * <p>「如何提高工程能力？我觉得多写代码就行」被判成 technical（P=0.953）。它确实提到了
     * "写代码"，所以模型不算瞎猜，但它不是我们要的技术面经。这条不断言"判对"——它断言
     * "这条链不会因此丢掉真正的内容"：{@link TechEssayFilter} 的规则层命中 0 种技术词，
     * 根本不会走到模型，所以实际运行中这条压根不会被误收。
     */
    @Test
    void knownMisjudgementIsAbsorbedByTheRuleLayer() {
        LayaContentClassifier classifier = new LayaContentClassifier(liveProperties());
        TechEssayFilter filter = new TechEssayFilter(classifier);

        String advice = "如何提高工程能力？我觉得多写代码就行";
        assertThat(filter.countDistinctTerms(advice))
                .as("规则层命中 0 种技术词，所以不会调用模型")
                .isZero();
        assertThat(filter.isTechnical(advice)).isFalse();
    }

    @Test
    void unreachableServerFallsBackInsteadOfThrowing() {
        DecisionProperties properties = liveProperties();
        properties.setBaseUrl("http://127.0.0.1:1");
        properties.setTimeoutMs(500);

        assertThat(new LayaContentClassifier(properties).classify("HashMap 线程安全吗"))
                .as("服务不可达时必须返回 empty，不能抛")
                .isEmpty();
    }
}
