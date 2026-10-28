package io.github.xiaou61.ai.internal.digest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GitHub 日榜/周榜（WORK-027、WORK-041）。
 *
 * <p><b>候选来自官方页面，排序来自 star 历史。</b>
 * <ul>
 *   <li>主通路：`trending?since=daily` 与 `?since=weekly`。页面本身是 GitHub 的编辑结果，
 *       我们只拿它当候选池，不拿它的顺序当排名。</li>
 *   <li>每个候选查一次 `/stargazers/history`，得到 `d1`（昨日）与 `d7`（近 7 日）；
 *       `heat = d1`，前端「本周」按 `starsWeek = d7` 重排。</li>
 *   <li>页面两条都不可达时才走 Search 兜底，并标「GitHub 新项目」——不冒充热榜。</li>
 * </ul>
 *
 * <p><b>为什么放弃总 star 排序</b>：2026-10-26 回测，`created:>=3d stars:>50` 的 30 条与
 * 官方日榜 0/11 重叠、其中 10 条昨日新增 &lt; 20；按 `d1` 排序的前 10 有 9 条来自官方榜。
 * 总 star 衡量的是「大」，不是「热」。
 *
 * <p><b>页面解析的坑</b>：不能直接匹配 `href="/([^"]+)"`——会撞到赞助商链接。先按 `<article`
 * 切块，再在 `<h2>` 里取仓库链接；总 star 取 `href="/owner/repo/stargazers"` 的锚文本。
 */
@Component
class GithubTrendingSource {

    private static final Logger log = LoggerFactory.getLogger(GithubTrendingSource.class);

    private static final String SEARCH_URL =
            "https://api.github.com/search/repositories?q=%s&sort=stars&per_page=30";

    private static final String USER_AGENT = "PaideiaDigest/1.0";

    /** 前端分组。 */
    static final String GROUP = "github";

    private static final String SOURCE_PAGE = "GitHub 热榜";
    private static final String SOURCE_FALLBACK = "GitHub 新项目";

    /**
     * 日榜/周榜各取前 25，并集最多 50。
     *
     * <p>原值是 10（线上只出 14 条，用户反馈「太少」），提到 15 后仍偏少：GitHub 的
     * trending 页面一页就是 25 条，取 25 才算把这一页用满。总量仍由
     * `maxItemsPerRun`（默认 60、按分组轮转分配）兜住，不会把其它分组挤没。
     */
    private static final int PAGE_LIMIT = 25;

    /**
     * 一页 trending 正常应有 25 条，**低于这个数视为响应被截断**。
     *
     * <p>2026-10-28 实测：同一台机器连 GitHub 的 nginx 反代三次，两次拿到完整的 584KB，
     * 一次 30 秒只回 344KB（`<article>` 少一半）。截断时解析出的条目数直接腰斩——当天日志是
     * 「日榜 12、周榜 14、去重 25、入候选 18」，用户看到"18 条太少"，真相是那一页只抓到一半。
     * 取 20 作阈值：完整是 25，截断通常只剩十几个，留出余量。
     */
    private static final int MIN_COMPLETE_PAGE_REPOS = 20;

    /**
     * 额外抓的语言榜。**不超过三个**：每个候选都要调一次 `stargazers/history`，
     * 而 api.github.com 未认证时是 60 次/小时——现在约 25 个候选，加三个语言榜约 50 个，
     * 刚好在限额内；再加就会大批 403（虽然会退回页面数字，但"次日真实增量"就没了）。
     * 想加更多语言，先配 `paideia.digest.github-token`。
     */
    private static final List<String> LANGUAGE_PAGES = List.of("java", "python", "typescript");

    /** history 并发度：25 个候选约 5 秒，远低于顺序的 40 秒。 */
    private static final int MAX_HISTORY_THREADS = 6;

    private static final Pattern PERIOD_STARS =
            Pattern.compile("([\\d,]+)\\s*stars?\\s*(today|this week)", Pattern.CASE_INSENSITIVE);

    private static final Pattern H2_REPO = Pattern.compile(
            "<h2[^>]*>.*?href=\"/(?!topics/|sponsors/|login|features/|marketplace|collections)"
                    + "([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)\"",
            Pattern.DOTALL);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GithubStarHistory starHistory;

    GithubTrendingSource(GithubStarHistory starHistory) {
        this.starHistory = starHistory;
    }

    /**
     * 抓热榜。
     *
     * <p>任何一条通路失败都留日志；两条都坏返回空列表，由编排层决定这一组为空。
     */
    List<FetchedItem> fetch(DigestProperties properties, DigestHttp http, long nowMillis) {
        String token = properties.getGithubToken();
        LocalDate referenceDay = Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);

        List<PageRepo> daily = new ArrayList<>(
                fetchPage(http, properties.getGithubTrendingUrl(), false));
        // 全语言日榜今天只给了 11 条（2026-10-28 实测，页面完整），单靠它 + 周榜去重后只有 18 条。
        // 语言榜各有 15–17 条，合并才是"今天够看"的量。周榜仍只取全语言——周维度上语言榜重叠更多，
        // 多抓两页换不来新仓库，只会多花 history 配额。
        for (String language : LANGUAGE_PAGES) {
            daily.addAll(fetchPage(http, properties.getGithubTrendingUrl() + "/" + language, false));
        }
        List<PageRepo> weekly = fetchPage(http, properties.getGithubTrendingUrl(), true);
        List<Candidate> candidates = merge(daily, weekly);
        if (!candidates.isEmpty()) {
            Scored scored = score(candidates, token, http, referenceDay);
            List<Candidate> selected = select(scored.items());
            log.info("GitHub 候选：日榜 {}、周榜 {}、去重 {}；history 成功 {}、失败 {}；入候选 {}",
                    daily.size(), weekly.size(), candidates.size(), scored.success(), scored.failed(),
                    selected.size());
            return toItems(selected, nowMillis, SOURCE_PAGE);
        }

        List<Candidate> fresh = searchApi(token, http, referenceDay);
        if (!fresh.isEmpty()) {
            Scored scored = score(fresh, token, http, referenceDay);
            List<Candidate> gated = scored.items().stream()
                    .filter(candidate -> passesGate(candidate, referenceDay))
                    .toList();
            log.info("GitHub 页面不可达，改用 Search：{} 条（门禁后 {}，history 成功 {}、失败 {}）",
                    fresh.size(), gated.size(), scored.success(), scored.failed());
            return toItems(gated, nowMillis, SOURCE_FALLBACK);
        }
        log.warn("GitHub 热榜两条通路都没有结果（页面与 Search API），这一组本次为空");
        return List.of();
    }

    private List<PageRepo> fetchPage(DigestHttp http, String baseUrl, boolean weekly) {
        List<PageRepo> repos = fetchPageOnce(http, baseUrl, weekly);
        if (repos.size() >= MIN_COMPLETE_PAGE_REPOS) {
            return repos;
        }
        // 疑似截断：同样的请求重试一次，取更多的那份。代理层没有重试能力（它已经返回了 200），
        // 所以只能在解析这一侧兜。
        List<PageRepo> retry = fetchPageOnce(http, baseUrl, weekly);
        if (retry.size() > repos.size()) {
            log.info("GitHub trending {} 首次只解析出 {} 条（疑似截断），重试得到 {} 条",
                    weekly ? "weekly" : "daily", repos.size(), retry.size());
            return retry;
        }
        return repos;
    }

    private List<PageRepo> fetchPageOnce(DigestHttp http, String baseUrl, boolean weekly) {
        String url = appendSince(baseUrl, weekly ? "weekly" : "daily");
        try {
            return parsePage(http.getText(url, USER_AGENT), weekly);
        } catch (DigestSource.DigestSourceException exception) {
            log.warn("GitHub trending 页面不可达（{}）：{}",
                    weekly ? "weekly" : "daily", exception.getMessage());
            return List.of();
        }
    }

    /** 解析 trending 页面。包级可见，单测直接喂 HTML fixture。 */
    List<PageRepo> parsePage(String html, boolean weekly) {
        List<PageRepo> result = new ArrayList<>();
        // 按 <article 切块：每个块要么是一个仓库行，要么是别的公告
        for (String block : html.split("<article")) {
            Matcher matcher = H2_REPO.matcher(block);
            if (!matcher.find()) {
                continue;
            }
            String repo = matcher.group(1);
            String description = firstByAttribute(block, "col-9");
            String language = firstByItemprop(block, "programmingLanguage");
            result.add(new PageRepo(repo, truncate(stripTags(description)), language,
                    firstTotalStars(block, repo), firstPeriodStars(block, weekly)));
        }
        return result;
    }

    /** 日/周合并：同一个 repo 只留一条，日榜与周榜的 period stars 各存一份。 */
    static List<Candidate> merge(List<PageRepo> daily, List<PageRepo> weekly) {
        Map<String, Candidate> byRepo = new LinkedHashMap<>();
        for (PageRepo page : daily) {
            Candidate candidate = byRepo.computeIfAbsent(page.repo(), Candidate::new);
            candidate.description = blankToNull(page.description());
            candidate.language = page.language();
            candidate.totalStars = page.totalStars();
            candidate.dailyPeriodStars = page.periodStars();
        }
        for (PageRepo page : weekly) {
            Candidate candidate = byRepo.computeIfAbsent(page.repo(), Candidate::new);
            if (candidate.description == null) {
                candidate.description = blankToNull(page.description());
            }
            if (candidate.language == null) {
                candidate.language = page.language();
            }
            if (candidate.totalStars == null) {
                candidate.totalStars = page.totalStars();
            }
            candidate.weeklyPeriodStars = page.periodStars();
        }
        return new ArrayList<>(byRepo.values());
    }

    /**
     * 给候选补上真实增量。
     *
     * <p>单条失败时回退页面的 period stars（没有就 0）；**不把总 star 当热度**。
     */
    private Scored score(List<Candidate> candidates, String token, DigestHttp http,
                         LocalDate referenceDay) {
        int success = 0;
        int failed = 0;
        ExecutorService executor = Executors.newFixedThreadPool(MAX_HISTORY_THREADS);
        try {
            List<Future<GithubStarHistory.Stats>> futures = new ArrayList<>();
            for (Candidate candidate : candidates) {
                futures.add(executor.submit(
                        () -> starHistory.fetch(candidate.repo, token, http, referenceDay)));
            }
            for (int index = 0; index < candidates.size(); index++) {
                Candidate candidate = candidates.get(index);
                GithubStarHistory.Stats stats = null;
                try {
                    stats = futures.get(index).get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    log.warn("GitHub star 历史任务失败（{}）：{}", candidate.repo,
                            exception.getCause() == null
                                    ? exception.getClass().getSimpleName()
                                    : exception.getCause().getClass().getSimpleName());
                }
                if (stats != null) {
                    candidate.dayStars = stats.dayStars();
                    candidate.weekStars = stats.weekStars();
                    success++;
                } else {
                    candidate.dayStars = candidate.dailyPeriodStars == null ? 0 : candidate.dailyPeriodStars;
                    candidate.weekStars = candidate.weeklyPeriodStars;
                    failed++;
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return new Scored(candidates, success, failed);
    }

    /**
     * 入选集合 = 日榜前 10 ∪ 周榜前 10；返回时按 `d1` 降序，保证前排是今天真正热的。
     *
     * <p>必须并集：只按 `d1` 取会让「本周一直涨但今天平」的项目进不来，周榜切换就没内容。
     */
    static List<Candidate> select(List<Candidate> candidates) {
        List<Candidate> dailyTop = candidates.stream()
                .sorted((left, right) -> Integer.compare(value(right.dayStars), value(left.dayStars)))
                .limit(PAGE_LIMIT).toList();
        List<Candidate> weeklyTop = candidates.stream()
                .sorted((left, right) -> Integer.compare(value(right.weekStars), value(left.weekStars)))
                .limit(PAGE_LIMIT).toList();
        Set<String> seen = new LinkedHashSet<>();
        List<Candidate> union = new ArrayList<>();
        for (Candidate candidate : dailyTop) {
            if (seen.add(candidate.repo)) {
                union.add(candidate);
            }
        }
        for (Candidate candidate : weeklyTop) {
            if (seen.add(candidate.repo)) {
                union.add(candidate);
            }
        }
        union.sort((left, right) -> {
            int byDay = Integer.compare(value(right.dayStars), value(left.dayStars));
            return byDay != 0 ? byDay : Integer.compare(value(right.weekStars), value(left.weekStars));
        });
        return union;
    }

    /** 兜底门禁：7 天以上直接放行；新仓库只放「真爆款」（昨日 1000+ 且 100+ fork）。 */
    static boolean passesGate(Candidate candidate, LocalDate referenceDay) {
        if (candidate.createdOn == null) {
            return true;
        }
        long age = ChronoUnit.DAYS.between(candidate.createdOn, referenceDay);
        return age >= 7
                || (value(candidate.dayStars) >= 1000 && value(candidate.forks) >= 100);
    }

    private static List<FetchedItem> toItems(List<Candidate> candidates, long nowMillis,
                                             String sourceName) {
        List<FetchedItem> items = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            items.add(new FetchedItem(
                    candidate.repo,
                    "https://github.com/" + candidate.repo,
                    candidate.description == null ? "" : candidate.description,
                    value(candidate.dayStars),
                    candidate.weekStars,
                    // meta 只留总 star：语言已独立成列，前端要按它分组
                    candidate.totalStars == null ? null : candidate.totalStars + "★",
                    candidate.language,
                    nowMillis,
                    "github-trending",
                    sourceName,
                    GROUP));
        }
        return items;
    }

    private List<Candidate> searchApi(String token, DigestHttp http, LocalDate referenceDay) {
        // 3 天窗口 + 50 星：更长的窗口会被指纹去重吃掉，更短会漏掉周末。
        String since = referenceDay.minusDays(3).toString();
        String query = java.net.URLEncoder.encode("created:>=" + since + " stars:>50",
                java.nio.charset.StandardCharsets.UTF_8);
        try {
            String body = http.getTextWithHeaders(SEARCH_URL.formatted(query), USER_AGENT,
                    githubHeaders(token));
            JsonNode items = objectMapper.readTree(body).path("items");
            List<Candidate> result = new ArrayList<>();
            for (JsonNode item : items) {
                String fullName = item.path("full_name").asText("");
                if (fullName.isBlank() || item.path("html_url").asText("").isBlank()) {
                    continue;
                }
                Candidate candidate = new Candidate(fullName);
                candidate.description = truncate(item.path("description").asText(""));
                candidate.language = blankToNull(item.path("language").asText(""));
                candidate.totalStars = item.path("stargazers_count").asInt(0);
                candidate.forks = item.path("forks_count").asInt(0);
                candidate.createdOn = parseDate(item.path("created_at").asText(""));
                result.add(candidate);
            }
            return result;
        } catch (DigestSource.DigestSourceException exception) {
            log.warn("GitHub Search API 失败：{}", exception.getMessage());
            return List.of();
        } catch (Exception exception) {
            log.warn("GitHub Search API 解析失败：{}", exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private static String appendSince(String baseUrl, String since) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://github.com/trending" : baseUrl;
        return base + (base.contains("?") ? "&" : "?") + "since=" + since;
    }

    private static String firstByAttribute(String block, String className) {
        Matcher matcher = Pattern
                .compile("<p[^>]*class=\"[^\"]*" + className + "[^\"]*\"[^>]*>(.*?)</p>", Pattern.DOTALL)
                .matcher(block);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String firstByItemprop(String block, String itemprop) {
        Matcher matcher = Pattern
                .compile("itemprop=\"" + itemprop + "\"[^>]*>\\s*([^<]+)", Pattern.DOTALL)
                .matcher(block);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static Integer firstTotalStars(String block, String repo) {
        Matcher matcher = Pattern
                .compile("href=\"/" + Pattern.quote(repo) + "/stargazers\"[^>]*>(.*?)</a>", Pattern.DOTALL)
                .matcher(block);
        if (!matcher.find()) {
            return null;
        }
        Matcher digits = Pattern.compile("[\\d,]+").matcher(stripTags(matcher.group(1)));
        return digits.find() ? parseStars(digits.group()) : null;
    }

    private static Integer firstPeriodStars(String block, boolean weekly) {
        Matcher matcher = PERIOD_STARS.matcher(block);
        String expected = weekly ? "this week" : "today";
        while (matcher.find()) {
            if (expected.equalsIgnoreCase(matcher.group(2))) {
                return parseStars(matcher.group(1));
            }
        }
        return null;
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** `3,856` → 3856。解析不了返回 null（heat 是可选字段）。 */
    private static Integer parseStars(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.replace(",", "").replace(" ", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String stripTags(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = stripTags(raw);
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private static Map<String, String> githubHeaders(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/vnd.github+json");
        headers.put("x-github-api-version", "2026-03-10");
        if (token != null && !token.isBlank()) {
            headers.put("authorization", "Bearer " + token);
        }
        return headers;
    }

    /** 页面的一行。 */
    record PageRepo(String repo, String description, String language,
                    Integer totalStars, Integer periodStars) {
    }

    /** 合并后的候选；`dayStars/weekStars` 由 history 回填。 */
    static final class Candidate {

        final String repo;
        String description;
        String language;
        Integer totalStars;
        Integer dailyPeriodStars;
        Integer weeklyPeriodStars;
        Integer dayStars;
        Integer weekStars;
        LocalDate createdOn;
        Integer forks;

        Candidate(String repo) {
            this.repo = repo;
        }
    }

    private record Scored(List<Candidate> items, int success, int failed) {
    }
}
