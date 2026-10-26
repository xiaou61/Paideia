package io.github.xiaou61.ai.internal.digest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.internal.provider.GuardedHttpTransport;
import io.github.xiaou61.ai.internal.provider.UpstreamGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真连上游验证那一次 AI 压缩（WORK-027 TASK-006）。
 *
 * <p><b>为什么单列一条真连测试</b>：解析层有 8 条单测，但它们喂的是字符串——证明不了
 * "模型给出的东西能解析"、"一次调用能在超时内处理几十条"。这两件事只有真调一次才有答案，
 * 而实跑环境当时没配凭据，全部走了降级分支。
 *
 * <p>默认跳过（要花钱、要网络）。打开方式：
 * <pre>
 * DEEPSEEK_API_KEY=sk-... mvn -pl paideia-ai -am test -Dtest=DigestSummarizerLiveTest \
 *   -Dpaideia.digest.ai.live=true
 * </pre>
 *
 * <p><b>Key 只从环境变量读</b>，不落任何被跟踪的文件——与项目保密纪律一致。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DigestSummarizerLiveTest {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String MODEL = "deepseek-flash";

    /** 五条真实感的中英文候选，覆盖"中文原样、英文要翻译"两种。 */
    private static final List<FetchedItem> ITEMS = List.of(
            new FetchedItem("Introducing the new reasoning model", "https://example.com/a",
                    "A lab released a new model focused on multi-step reasoning.",
                    1200, null, null, 1_800_000_000_000L, "test", "测试源", "news"),
            new FetchedItem("AI 推理模型发布，长链思考更强", "https://example.com/b",
                    "某实验室发布新模型，主打多步推理。", 800, null, null, 1_800_000_000_000L, "test",
                    "测试源", "news"),
            new FetchedItem("owner/awesome-repo", "https://github.com/owner/awesome-repo",
                    "A toolkit for building agent workflows.", 3400, null, "TypeScript",
                    1_800_000_000_000L, "github-trending", "GitHub 热榜", "github"),
            new FetchedItem("Ask HN: is Rust worth learning in 2026", "https://news.ycombinator.com/item?id=1",
                    "", 220, null, "88 条评论", 1_800_000_000_000L, "hackernews", "Hacker News",
                    "hackernews"),
            new FetchedItem("Tauri 2.0 ships stable mobile support", "https://example.com/c",
                    "The desktop framework now targets iOS and Android.", 150, null, null,
                    1_800_000_000_000L, "test", "测试源", "community"));

    @Test
    @DisplayName("真连 DeepSeek：能生成中文标题与摘要，且按指纹回填不错位")
    void summarizesWithRealUpstream() {
        ModelAccess access = new ModelAccess(BASE_URL, MODEL, System.getenv("DEEPSEEK_API_KEY"));
        DigestSummarizer summarizer =
                new DigestSummarizer(new GuardedHttpTransport(new UpstreamGuard()));

        List<String> fingerprints = new ArrayList<>();
        for (FetchedItem item : ITEMS) {
            fingerprints.add(DigestFingerprint.of(item.url()));
        }

        long started = System.currentTimeMillis();
        DigestSummarizer.Outcome outcome =
                summarizer.summarize(ITEMS, fingerprints, access, 180);
        long elapsed = System.currentTimeMillis() - started;

        System.out.printf("[live] 耗时 %d ms，返回 %d/%d 条，usage=%s%n",
                elapsed, outcome.rewritten().size(), ITEMS.size(), outcome.usage());

        // 1. 至少要有结果——全空说明上游/解析出了问题
        assertThat(outcome.rewritten())
                .as("真连应至少写回若干条；若为空，先看日志里的 warn 原因")
                .isNotEmpty();

        // 2. 用量必须带出来（AC-008 的记账前提）
        assertThat(outcome.usage()).as("一次真实调用应有用量").isNotNull();
        assertThat(outcome.usage().inputTokens()).isPositive();

        // 3. **回填不错位**：每条结果的键必须是输入的指纹之一
        assertThat(outcome.rewritten().keySet()).isSubsetOf(fingerprints);

        // 4. 标题必须是中文（英文原文要被翻译）——抽查英文那条
        String englishFingerprint = fingerprints.get(0);
        DigestSummarizer.Rewritten english = outcome.rewritten().get(englishFingerprint);
        if (english != null) {
            assertThat(english.title())
                    .as("英文原文的标题应被译成中文：%s", english.title())
                    .matches(".*[\\u4e00-\\u9fa5].*");
            System.out.printf("[live] 英文原来标题 → %s / %s%n", english.title(), english.summary());
        }

        outcome.rewritten().forEach((key, value) ->
                System.out.printf("[live] %s → %s | %s%n",
                        key.substring(0, 8), value.title(), value.summary()));
    }

    @Test
    @DisplayName("空输入时不发起调用（不花冤枉钱）")
    void emptyInputMakesNoCall() {
        ModelAccess access = new ModelAccess(BASE_URL, MODEL, System.getenv("DEEPSEEK_API_KEY"));
        DigestSummarizer summarizer =
                new DigestSummarizer(new GuardedHttpTransport(new UpstreamGuard()));

        DigestSummarizer.Outcome outcome = summarizer.summarize(List.of(), List.of(), access, 30);

        assertThat(outcome.rewritten()).isEmpty();
        assertThat(outcome.usage()).isNull();
    }

    @Test
    @DisplayName("凭据错误时整批降级返回空，不抛异常（AC-005 的真连版）")
    void badCredentialDegradesInsteadOfThrowing() {
        ModelAccess access = new ModelAccess(BASE_URL, MODEL, "sk-invalid-key-for-test");
        DigestSummarizer summarizer =
                new DigestSummarizer(new GuardedHttpTransport(new UpstreamGuard()));

        List<String> fingerprints = List.of(DigestFingerprint.of(ITEMS.get(0).url()));
        DigestSummarizer.Outcome outcome = summarizer.summarize(
                List.of(ITEMS.get(0)), fingerprints, access, 60);

        assertThat(outcome.rewritten()).as("凭据错时降级为空，而不是抛给调用方").isEmpty();
        assertThat(outcome.usage()).isNull();
    }
}
