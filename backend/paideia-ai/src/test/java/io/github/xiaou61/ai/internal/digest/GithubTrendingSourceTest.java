package io.github.xiaou61.ai.internal.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GitHub 日榜/周榜的候选、真实增量与兜底（WORK-041）。
 *
 * <p>页面只当候选源；热度必须来自 `/stargazers/history` 的 `d1`，不是总 star。
 * 页面不可达时才走 Search，并标「GitHub 新项目」、套门禁。
 */
class GithubTrendingSourceTest {

    /** 2026-10-26 08:00 北京 = 2026-10-26 00:00 UTC；referenceDay = 2026-10-25（周日）。 */
    private static final long NOW = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli();

    private static final String PAGE = """
            <article class="Box-row">
              <h2 class="h3 lh-condensed"><a href="/trending-owner/hot-repo">hot-repo</a></h2>
              <p class="col-9 d-inline-block">The hot project description</p>
              <a href="/trending-owner/hot-repo/stargazers">12,345</a>
              <span class="d-inline-block float-sm-right">3,856 stars today</span>
              <span itemprop="programmingLanguage">Python</span>
            </article>
            """;

    private static final String WEEKLY_PAGE = """
            <article class="Box-row">
              <h2 class="h3 lh-condensed"><a href="/trending-owner/hot-repo">hot-repo</a></h2>
              <p class="col-9 d-inline-block">The hot project description</p>
              <a href="/trending-owner/hot-repo/stargazers">12,345</a>
              <span class="d-inline-block float-sm-right">8,000 stars this week</span>
              <span itemprop="programmingLanguage">Python</span>
            </article>
            """;

    /** week=2026-10-18（周日），days 从周一开始；周日 10-25 是 250，d7=850。 */
    private static final String HISTORY = """
            [{"week":1792281600,"total":850,"days":[100,100,100,100,100,100,250]}]
            """;

    private final GithubTrendingSource source = new GithubTrendingSource(new GithubStarHistory());

    /** 记录被请求过的 URL，并按预设返回内容或抛异常。 */
    private static class StubHttp extends DigestHttp {

        final List<String> requested = java.util.Collections.synchronizedList(new ArrayList<>());
        private final java.util.function.Function<String, String> responder;

        StubHttp(java.util.function.Function<String, String> responder) {
            super(5);
            this.responder = responder;
        }

        @Override
        String getTextWithHeaders(String url, String userAgent, Map<String, String> headers) {
            requested.add(url);
            return responder.apply(url);
        }

        @Override
        String getTextFollowingSameHostRedirects(String url, String userAgent,
                                                 Map<String, String> headers, String allowedHost,
                                                 int maxHops) {
            requested.add(url);
            return responder.apply(url);
        }
    }

    private static DigestProperties properties() {
        return new DigestProperties();
    }

    @Test
    @DisplayName("页面候选 + history：heat 是 d1，starsWeek 是 d7，来源是热榜")
    void pageCandidatesUseRealDeltas() {
        StubHttp http = new StubHttp(url -> {
            if (url.contains("stargazers/history")) {
                return HISTORY;
            }
            if (url.contains("github.com/trending")) {
                return url.contains("since=weekly") ? WEEKLY_PAGE : PAGE;
            }
            throw new DigestSource.DigestSourceException("不该请求：" + url);
        });

        List<FetchedItem> items = source.fetch(properties(), http, NOW);

        assertThat(items).hasSize(1);
        FetchedItem item = items.get(0);
        assertThat(item.title()).isEqualTo("trending-owner/hot-repo");
        assertThat(item.heat()).as("heat 来自 history 的 d1").isEqualTo(250);
        assertThat(item.starsWeek()).as("starsWeek 来自 history 的 d7").isEqualTo(850);
        assertThat(item.meta()).isEqualTo("Python · 12345★");
        assertThat(item.sourceName()).isEqualTo("GitHub 热榜");
        assertThat(http.requested).anyMatch(url -> url.contains("since=daily"));
        assertThat(http.requested).anyMatch(url -> url.contains("since=weekly"));
        assertThat(http.requested).anyMatch(url -> url.contains("stargazers/history"));
    }

    @Test
    @DisplayName("页面全空：走 Search，门禁挡掉新垃圾、保留真爆款，来源改「新项目」")
    void emptyPageFallsBackToSearchWithGate() {
        String search = """
                {"items":[
                  {"full_name":"new/spam","html_url":"https://github.com/new/spam",
                   "description":"spam","language":"Java","stargazers_count":100,
                   "forks_count":0,"created_at":"2026-10-24T00:00:00Z"},
                  {"full_name":"new/hot","html_url":"https://github.com/new/hot",
                   "description":"hot","language":"Rust","stargazers_count":3200,
                   "forks_count":424,"created_at":"2026-10-24T00:00:00Z"}
                ]}
                """;
        StubHttp http = new StubHttp(url -> {
            if (url.contains("search/repositories")) {
                return search;
            }
            if (url.contains("stargazers/history")) {
                return url.contains("new/hot")
                        ? "[{\"week\":1792281600,\"days\":[0,0,0,0,0,0,1292]}]"
                        : "[{\"week\":1792281600,\"days\":[0,0,0,0,0,0,10]}]";
            }
            if (url.contains("github.com/trending")) {
                return "<html><body>no articles</body></html>";
            }
            throw new DigestSource.DigestSourceException("不该请求：" + url);
        });

        List<FetchedItem> items = source.fetch(properties(), http, NOW);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("new/hot");
        assertThat(items.get(0).heat()).isEqualTo(1292);
        assertThat(items.get(0).sourceName()).isEqualTo("GitHub 新项目");
    }

    @Test
    @DisplayName("history 单条失败：回退页面 period stars，不影响整组")
    void historyFailureFallsBackToPagePeriodStars() {
        StubHttp http = new StubHttp(url -> {
            if (url.contains("stargazers/history")) {
                throw new DigestSource.DigestSourceException("boom");
            }
            if (url.contains("github.com/trending")) {
                return url.contains("since=weekly") ? WEEKLY_PAGE : PAGE;
            }
            throw new DigestSource.DigestSourceException("不该请求：" + url);
        });

        List<FetchedItem> items = source.fetch(properties(), http, NOW);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).heat()).as("回退到页面 stars today").isEqualTo(3856);
        assertThat(items.get(0).starsWeek()).as("回退到页面 stars this week").isEqualTo(8000);
    }

    @Test
    @DisplayName("页面两条与 Search 都坏：返回空，不抛异常")
    void bothPathsBrokenReturnsEmpty() {
        StubHttp http = new StubHttp(url -> {
            throw new DigestSource.DigestSourceException("网络不通");
        });

        List<FetchedItem> items = source.fetch(properties(), http, NOW);

        assertThat(items).isEmpty();
        assertThat(http.requested).anyMatch(url -> url.contains("github.com/trending"));
        assertThat(http.requested).anyMatch(url -> url.contains("api.github.com"));
    }

    @Test
    @DisplayName("赞助商与非仓库链接不会被当成热榜条目")
    void ignoresNonRepoLinks() {
        String page = """
                <article class="Box-row sponsor">
                  <a href="/sponsors/someone">Sponsor</a>
                  <a href="/topics/java">Java topic</a>
                </article>
                <article class="Box-row">
                  <h2><a href="/real/repo">real/repo</a></h2>
                  <span>7 stars today</span>
                </article>
                """;
        StubHttp http = new StubHttp(url -> {
            if (url.contains("stargazers/history")) {
                throw new DigestSource.DigestSourceException("boom");
            }
            if (url.contains("github.com/trending")) {
                return url.contains("since=weekly") ? "" : page;
            }
            throw new DigestSource.DigestSourceException("不该请求：" + url);
        });

        List<FetchedItem> items = source.fetch(properties(), http, NOW);

        assertThat(items).extracting(FetchedItem::title).containsExactly("real/repo");
    }
}
