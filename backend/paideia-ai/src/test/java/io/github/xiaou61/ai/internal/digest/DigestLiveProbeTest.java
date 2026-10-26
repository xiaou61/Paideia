package io.github.xiaou61.ai.internal.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真实联调：七个源各抓一次，断言都能解析出非零条目（WORK-027 TASK-003/004 的实跑证据）。
 *
 * <p>**默认跳过**（连外网、受对方站点状态影响），用 {@code -Dpaideia.digest.live=true} 打开
 * ——与既有 {@code LayaContentClassifierLiveTest} 同一手法。
 *
 * <p>它的价值不是"回归"，而是**源改版时能立刻看到是哪一家坏了**：
 * 单测喂的是夹具，夹具不会自己坏。
 */
@EnabledIfSystemProperty(named = "paideia.digest.live", matches = "true")
class DigestLiveProbeTest {

    private final FeedXml feedXml = new FeedXml();
    private final DigestHttp http = new DigestHttp(20);

    /** 配置里的五个 RSS 源与实际端点。 */
    private static final List<String[]> RSS = List.of(
            new String[] {"qbitai", "量子位", "https://www.qbitai.com/feed"},
            new String[] {"ithome", "IT之家", "https://www.ithome.com/rss/"},
            new String[] {"infoq", "InfoQ", "https://www.infoq.cn/feed"},
            new String[] {"juejin", "掘金", "https://juejin.cn/rss"},
            new String[] {"oschina", "开源中国", "https://www.oschina.net/news/rss"});

    @Test
    @DisplayName("五个 RSS 源各能解析出非零条目")
    void rssSourcesYieldItems() {
        for (String[] source : RSS) {
            String body = http.getText(source[2], "PaideiaDigest/1.0");
            List<FeedXml.Entry> entries = feedXml.parse(body, System.currentTimeMillis());
            System.out.printf("[live] %-8s 解析出 %d 条，首条：%s%n", source[0], entries.size(),
                    entries.isEmpty() ? "(无)" : entries.get(0).title());
            assertThat(entries)
                    .as("%s（%s）应至少解析出一条——零条说明源改版或解析器需要更新",
                            source[1], source[2])
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("GitHub 抓取能返回条目（页面优先，页面不可达时走 Search）")
    void githubTrendingPageParsesRepos() {
        List<FetchedItem> repos = new GithubTrendingSource(new GithubStarHistory())
                .fetch(new DigestProperties(), http, System.currentTimeMillis());
        System.out.printf("[live] github 热榜返回 %d 条；首条：%s%n", repos.size(),
                repos.isEmpty() ? "(无)" : repos.get(0).title());
        assertThat(repos).as("页面或 Search 兜底至少有一条能用").isNotEmpty();
    }

    @Test
    @DisplayName("HN Algolia 返回前 10，points 作为 heat")
    void hackerNewsYieldsHits() {
        List<FetchedItem> hits = new HackerNewsSource().fetch(http, System.currentTimeMillis());
        System.out.printf("[live] hacker news 解析出 %d 条，首条：%s（%s points）%n",
                hits.size(), hits.isEmpty() ? "(无)" : hits.get(0).title(),
                hits.isEmpty() ? "-" : String.valueOf(hits.get(0).heat()));
        assertThat(hits).isNotEmpty();
    }

    @Test
    @DisplayName("GitHub 抓取通路能返回（有 token 时 history 限额更高）")
    void githubFetchWorks() {
        DigestProperties properties = new DigestProperties();
        properties.setGithubToken(System.getProperty("paideia.digest.githubToken", ""));
        List<FetchedItem> items = new GithubTrendingSource(new GithubStarHistory())
                .fetch(properties, http, System.currentTimeMillis());
        System.out.printf("[live] GitHub 双通路共 %d 条（token=%s）%n", items.size(),
                properties.getGithubToken().isBlank() ? "无" : "有");
        assertThat(items).as("页面或兜底至少有一条能用").isNotEmpty();
    }
}
