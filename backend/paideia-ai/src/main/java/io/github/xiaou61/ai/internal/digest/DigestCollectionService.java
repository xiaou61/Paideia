package io.github.xiaou61.ai.internal.digest;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 每日速递的编排（WORK-027）。
 *
 * <p>八个步骤，每步的失败语义都写死了：
 * <ol>
 *   <li>未启用 / 没有源配置 → 跳过并返回原因；</li>
 *   <li>逐源抓取，**单源失败只记日志**（AC-004）；</li>
 *   <li>合并 → 算指纹 → 批内去重；</li>
 *   <li>查库剔除已存在（只查今天±1 天）；</li>
 *   <li>按 {@code maxItemsPerRun} 截断（成本闸）；</li>
 *   <li>**一次** AI 调用：挑 + 写标题摘要；</li>
 *   <li>逐条入库；AI 没返回的标 {@code degraded}（AC-005）；</li>
 *   <li>返回统计。</li>
 * </ol>
 *
 * <p><b>空候选池必须不调 AI、不写行、不报错</b>（评审焦点第二条）——
 * 所有源都失败的那天，页面应该显示"今天没有内容"，而不是让定时任务炸出堆栈。
 *
 * <p><b>不扣用户积分</b>：与 WORK-025 同一取舍——抓取是平台行为，没有用户归属。
 */
@Service
public class DigestCollectionService {

    private static final Logger log = LoggerFactory.getLogger(DigestCollectionService.class);

    /** 前端分组固定顺序。 */
    static final List<String> GROUP_ORDER = List.of("news", "github", "community", "hackernews");

    private static final String STATE_READY = "ready";
    private static final String STATE_DEGRADED = "degraded";

    /** 处理中异常（重复键）按"已存在"处理，不算失败。 */
    private static final String DUPLICATE_KEY = "Duplicate";

    private final DigestProperties properties;
    private final RssDigestSource rssSource;
    private final GithubTrendingSource githubSource;
    private final HackerNewsSource hackerNewsSource;
    private final DigestSummarizer summarizer;
    private final DigestMapper mapper;
    private final CredentialService credentials;
    private final io.github.xiaou61.ai.RunRecorder recorder;

    DigestCollectionService(DigestProperties properties, RssDigestSource rssSource,
                            GithubTrendingSource githubSource, HackerNewsSource hackerNewsSource,
                            DigestSummarizer summarizer, DigestMapper mapper,
                            CredentialService credentials,
                            io.github.xiaou61.ai.RunRecorder recorder) {
        this.properties = properties;
        this.rssSource = rssSource;
        this.githubSource = githubSource;
        this.hackerNewsSource = hackerNewsSource;
        this.summarizer = summarizer;
        this.mapper = mapper;
        this.credentials = credentials;
        this.recorder = recorder;
    }

    /** 一次运行的统计，供日志与测试断言。 */
    public record RunResult(int fetched, int duplicated, int created, int degraded, int sourcesFailed,
                            String skipReason) {

        static RunResult skipped(String reason) {
            return new RunResult(0, 0, 0, 0, 0, reason);
        }
    }

    /**
     * 跑一次完整流程。
     *
     * <p>**任何阶段失败都不抛给调用方**：定时任务里抛异常只会让下一轮更难排查。
     */
    public RunResult runOnce() {
        if (!properties.isEnabled()) {
            return RunResult.skipped("未启用");
        }
        LocalDate today = properties.today();
        long nowMillis = System.currentTimeMillis();
        long sinceMillis = nowMillis - Duration.ofHours(properties.getLookbackHours()).toMillis();

        // 1-2. 逐源抓取
        List<FetchedItem> fetched = new ArrayList<>();
        int sourcesFailed = 0;
        DigestHttp http = new DigestHttp(properties.getTimeoutSeconds());
        for (DigestProperties.RssSource source : properties.getRss()) {
            try {
                fetched.addAll(rssSource.fetch(source, http, sinceMillis, nowMillis));
            } catch (RuntimeException exception) {
                sourcesFailed++;
                log.warn("速递源 {} 抓取失败：{}", source.getKey(), exception.getMessage());
            }
        }
        try {
            fetched.addAll(githubSource.fetch(properties, http, nowMillis));
        } catch (RuntimeException exception) {
            sourcesFailed++;
            log.warn("GitHub 热榜抓取失败：{}", exception.getMessage());
        }
        try {
            fetched.addAll(hackerNewsSource.fetch(http, nowMillis));
        } catch (RuntimeException exception) {
            sourcesFailed++;
            log.warn("Hacker News 抓取失败：{}", exception.getMessage());
        }

        if (fetched.isEmpty()) {
            log.info("速递本次没有抓到任何内容（失败源 {} 个）", sourcesFailed);
            return new RunResult(0, 0, 0, 0, sourcesFailed, "本次没有抓到内容");
        }

        // 3. 批内去重（同一 URL 可能被两个源都抓到）
        Map<String, FetchedItem> byFingerprint = new LinkedHashMap<>();
        for (FetchedItem item : fetched) {
            String fingerprint = DigestFingerprint.of(item.url());
            byFingerprint.putIfAbsent(fingerprint, item);
        }

        // 4. 查库剔除：GitHub 只查当天（允许同一仓库连续在榜），其它组保持 ±1 天
        List<String> candidates = new ArrayList<>(byFingerprint.keySet());
        List<String> githubCandidates = candidates.stream()
                .filter(fp -> GithubTrendingSource.GROUP.equals(byFingerprint.get(fp).groupKey()))
                .toList();
        List<String> otherCandidates = candidates.stream()
                .filter(fp -> !GithubTrendingSource.GROUP.equals(byFingerprint.get(fp).groupKey()))
                .toList();
        Set<String> existing = new LinkedHashSet<>();
        existing.addAll(mapper.existingFingerprintsSafe(today, today, githubCandidates));
        existing.addAll(mapper.existingFingerprintsSafe(
                today.minusDays(1), today.plusDays(1), otherCandidates));
        List<String> fresh = candidates.stream().filter(fp -> !existing.contains(fp)).toList();
        if (fresh.isEmpty()) {
            log.info("速递本次候选 {} 条全部已存在", candidates.size());
            return new RunResult(fetched.size(), candidates.size(), 0, 0, sourcesFailed, "没有新内容");
        }

        // 5. 截断（成本闸）——**按分组轮转取，不能直接切前缀**
        //
        // 2026-10-21 实跑踩到：IT 之家一家就 60 条，直接取前 60 会把配额全占掉，
        // GitHub / 社区 / HN 三组一条都进不来，页面永远只有一个分组。
        // 轮转后每组都有份，条数由各组自己的产出量决定。
        List<String> selected = takeByGroup(fresh, byFingerprint, properties.getMaxItemsPerRun());
        List<FetchedItem> items = selected.stream().map(byFingerprint::get).toList();

        // 6. 一次 AI 调用
        Optional<ModelAccess> access = credentials.access(0);
        DigestSummarizer.Outcome outcome;
        if (access.isEmpty()) {
            log.warn("平台未配置 AI 凭据，速递全部按降级入库");
            outcome = new DigestSummarizer.Outcome(Map.of(), null);
        } else {
            // 采集行为记在 ai_runs 里（AC-008）。**userId=0 表示平台行为**：
            // 速递没有用户归属，与 WORK-025 同一取舍；0 在 jdk 里不对应任何真实账号，
            // 运营统计里能一眼认出这是系统调用而不是某个学习者花的钱。
            var run = recorder.begin(io.github.xiaou61.ai.RunSpec
                    .of(io.github.xiaou61.ai.RunKind.DIGEST, 0L)
                    .withModel(access.get().model())
                    .withPromptVersion(DigestPrompt.VERSION));
            try {
                outcome = summarizer.summarize(items, selected, access.get(),
                        properties.getAiTimeoutSeconds());
                if (outcome.usage() != null) {
                    run.tokens(outcome.usage().inputTokens(), outcome.usage().outputTokens(), 0);
                }
                run.finish(outcome.usage() == null
                        ? io.github.xiaou61.ai.RunOutcome.FAILED
                        : io.github.xiaou61.ai.RunOutcome.OK, null);
            } finally {
                run.close();
            }
        }
        Map<String, DigestSummarizer.Rewritten> rewritten = outcome.rewritten();

        // 7. 逐条入库
        Instant now = Instant.now();
        int created = 0;
        int degraded = 0;
        for (int index = 0; index < items.size(); index++) {
            FetchedItem item = items.get(index);
            String fingerprint = selected.get(index);
            DigestSummarizer.Rewritten result = rewritten.get(fingerprint);
            boolean ready = result != null;
            if (!ready) {
                degraded++;
            }
            try {
                // 热度优先用源自己的信号（HN 点数 / GitHub 昨日新增 star）；
                // 没有信号的源（RSS 新闻、社区）用 AI 给的重要性分，这样每组都能排序。
                boolean github = GithubTrendingSource.GROUP.equals(item.groupKey());
                Integer starsDay = github ? (item.heat() == null ? 0 : item.heat()) : null;
                Integer heat = github
                        ? starsDay
                        : (item.heat() != null ? item.heat() : (ready ? result.heat() : null));
                // GitHub 标题固定为 owner/repo：仓库名被翻译掉，用户就搜不到、也对不上 GitHub
                String title = github
                        ? item.title()
                        : (ready && !result.title().isBlank() ? result.title() : item.title());
                mapper.insert(today, item.groupKey(), item.sourceKey(), item.sourceName(),
                        title, ready ? result.summary() : null,
                        item.url(), fingerprint, heat, starsDay,
                        github ? item.starsWeek() : null,
                        item.meta(), item.language(), item.publishedAt(),
                        ready ? STATE_READY : STATE_DEGRADED, now);
                created++;
            } catch (RuntimeException exception) {
                // 并发两个实例同时跑时，唯一约束会挡住后来者——按"已存在"处理，不记失败
                if (String.valueOf(exception.getMessage()).contains(DUPLICATE_KEY)) {
                    continue;
                }
                log.warn("速递入库失败（{}）：{}", item.url(), exception.getMessage());
            }
        }

        log.info("速递完成：候选 {} 条，已存在 {} 条，入库 {} 条（其中降级 {} 条），失败源 {} 个",
                fetched.size(), candidates.size() - fresh.size(), created, degraded, sourcesFailed);
        return new RunResult(fetched.size(), candidates.size() - fresh.size(), created, degraded,
                sourcesFailed, null);
    }

    /**
     * 按分组轮转取最多 {@code limit} 条。
     *
     * <p><b>为什么不能直接切前缀</b>：一个源一次能给 60 条（IT 之家实测），直接截断会让
     * 排在配置前面的那几个源把配额全吃掉，后面三组（GitHub / 社区 / HN）一条都进不来——
     * 2026-10-21 实跑就是这样：入库 60 条全是 IT 之家，其它三组为 0。
     *
     * <p>轮转保留各组的相对顺序（组内仍按原顺序，热度高的在前由查询层排序），
     * 但不会被单个源垄断。空组跳过。
     */
    static List<String> takeByGroup(List<String> fresh, Map<String, FetchedItem> byFingerprint,
                                    int limit) {
        Map<String, java.util.ArrayDeque<String>> byGroup = new LinkedHashMap<>();
        for (String fingerprint : fresh) {
            String group = byFingerprint.get(fingerprint).groupKey();
            byGroup.computeIfAbsent(group, key -> new java.util.ArrayDeque<>()).add(fingerprint);
        }
        List<String> selected = new ArrayList<>();
        boolean added = true;
        // 一轮一轮地每组取一条，直到取满或全部组都空
        while (selected.size() < limit && added) {
            added = false;
            for (java.util.ArrayDeque<String> queue : byGroup.values()) {
                if (selected.size() >= limit) {
                    break;
                }
                String next = queue.poll();
                if (next != null) {
                    selected.add(next);
                    added = true;
                }
            }
        }
        return selected;
    }

    /** 某天有没有内容。启动补跑用它决定要不要真抓一轮。 */
    public boolean hasContentFor(LocalDate date) {
        return !mapper.findByDate(date).isEmpty();
    }

    /** 某天的条目，按 {@link #GROUP_ORDER} 分组。给读接口用。 */
    public Map<String, List<DigestMapper.Row>> groupedByDate(LocalDate date) {
        Map<String, List<DigestMapper.Row>> grouped = new LinkedHashMap<>();
        for (String group : GROUP_ORDER) {
            grouped.put(group, new ArrayList<>());
        }
        for (DigestMapper.Row row : mapper.findByDate(date)) {
            grouped.computeIfAbsent(row.groupKey(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }
}
