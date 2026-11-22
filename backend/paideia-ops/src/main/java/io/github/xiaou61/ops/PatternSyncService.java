package io.github.xiaou61.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 把 Loki pattern ingester 的模板频次同步成本地「模板字典 + 窗口桶」（WORK-085）。
 *
 * <p>聚类本身交给 Loki（Drain 算法，采集侧成模板），应用只负责三件事：成字典、按检测窗口分桶、
 * 定期清理。这样不引入新进程，也不在应用里重写一遍聚类。
 *
 * <p>幂等：桶按窗口对齐，同步每轮都会用「该窗口的重算值」覆盖同名桶（upsert count = VALUES），
 * 所以几分钟一次的重复同步不会把频次刷成两倍。
 *
 * <p>查询窗口用 {@code lookback-minutes}（默认 60），比检测窗口宽：Loki 的模板查询硬编码
 * 「窗口内累计不足 30 条就剪掉」（{@code minClusterSize = 30}），窗口开太窄本项目根本查不到模板。
 * 窗口内的**每条采样点**按自己的时间戳落桶，所以长窗口不会污染单个桶。
 */
@Service
class PatternSyncService {

    private static final int PATTERN_COLUMN_MAX = 500;
    private static final int SAMPLE_COLUMN_MAX = 1000;

    private final OpsProperties properties;
    private final PatternMapper mapper;
    private final LokiClient loki;
    /**
     * 每个 unit 本轮 Loki 数据是否可信（不可达/取不到数据时为 false）。
     *
     * <p>检测端据此跳过「消失」判定：Loki 抖两个窗口就会让全部模板看起来「一条都没有」，
     * 而基线仍来自历史桶，会把整批模板误判成服务静默（2026-11-22 修复）。
     */
    private final Map<String, Boolean> unitUsable = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Autowired
    PatternSyncService(OpsProperties properties, PatternMapper mapper) {
        this(properties, mapper, new LokiClient(properties.getLokiUrl(), properties.getQueryTimeoutMs()));
    }

    PatternSyncService(OpsProperties properties, PatternMapper mapper, LokiClient loki) {
        this.properties = properties;
        this.mapper = mapper;
        this.loki = loki;
    }

    /** 同步一轮；返回本轮写入的模板条数（0 表示关闭或没有数据）。 */
    int syncOnce(Instant now) {
        OpsProperties.Patterns cfg = properties.getPatterns();
        if (!cfg.isEnabled()) {
            return 0;
        }
        if (!running.compareAndSet(false, true)) {
            return 0; // 上一轮还没跑完：直接跳过，避免并发重复写同一窗口的桶
        }
        try {
            int windowMinutes = Math.max(1, cfg.getWindowMinutes());
            long lookbackMinutes = Math.max(windowMinutes, cfg.getLookbackMinutes());
            Instant from = now.minusSeconds(lookbackMinutes * 60L);

            int written = 0;
            for (String unit : cfg.getUnits()) {
                if (unit == null || unit.isBlank()) {
                    continue;
                }
                String selector = "{unit=\"" + unit + ".service\"}";
                List<LokiClient.PatternSeries> series =
                        loki.patterns(selector, from, now, cfg.getMaxTemplates());
                // 空列表既可能是「Loki 挂了/接口不存在」也可能是「窗口内没有模板」，
                // 只有再探一次才知道能不能拿这一轮的数据做「消失」判定
                unitUsable.put(unit, !series.isEmpty() || loki.reachable());
                for (LokiClient.PatternSeries one : series) {
                    if (writeSeries(unit, one, windowMinutes, now)) {
                        written++;
                    }
                }
            }
            purge(now, cfg.getRetentionDays());
            return written;
        } finally {
            running.set(false);
        }
    }

    /** 该 unit 本轮 Loki 数据是否可信（未同步过的一律 false，宁可漏报也不误报）。 */
    boolean isUnitUsable(String unit) {
        return unitUsable.getOrDefault(unit, false);
    }

    /** 把一个模板的采样点按窗口折算成桶并落库；返回是否写入。 */
    private boolean writeSeries(String unit, LokiClient.PatternSeries series,
                                int windowMinutes, Instant now) {
        Map<Instant, Long> perBucket = new LinkedHashMap<>();
        Instant first = null;
        Instant last = null;
        long lastCount = 0;
        long total = 0;
        for (LokiClient.PatternSample sample : series.samples()) {
            Instant at = sample.at();
            if (at == null) {
                continue;
            }
            int count = (int) Math.min(Integer.MAX_VALUE, Math.max(0, sample.count()));
            perBucket.merge(bucketStart(at, windowMinutes), (long) count, Long::sum);
            if (first == null || at.isBefore(first)) {
                first = at;
            }
            if (last == null || !at.isBefore(last)) {
                last = at;
                lastCount = count;
            }
            total += count;
        }
        if (perBucket.isEmpty()) {
            return false;
        }
        String hash = sha256(series.pattern());
        String pattern = truncate(series.pattern(), PATTERN_COLUMN_MAX);
        String sampleLine = truncate(series.pattern(), SAMPLE_COLUMN_MAX);
        mapper.upsertTemplate(hash, unit, pattern, sampleLine, first, last,
                (int) Math.min(Integer.MAX_VALUE, lastCount), total, now, now);
        for (Map.Entry<Instant, Long> entry : perBucket.entrySet()) {
            ZonedDateTime local = entry.getKey().atZone(ZoneId.systemDefault());
            int dow = local.getDayOfWeek().getValue() % 7; // 0=周日，与 buckets.dow 约定一致
            mapper.upsertBucket(hash, unit, entry.getKey(), dow, local.getHour(),
                    (int) Math.min(Integer.MAX_VALUE, entry.getValue()), now);
        }
        return true;
    }

    private void purge(Instant now, int retentionDays) {
        if (retentionDays <= 0) {
            return;
        }
        Instant cutoff = now.minusSeconds(retentionDays * 86_400L);
        mapper.purgeBuckets(cutoff);
        mapper.purgeTemplates(cutoff);
        mapper.purgeAnomalies(cutoff);
    }

    static Instant bucketStart(Instant at, int windowMinutes) {
        long width = Math.max(1, windowMinutes) * 60L;
        long epoch = at.getEpochSecond();
        return Instant.ofEpochSecond(epoch - Math.floorMod(epoch, width));
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
