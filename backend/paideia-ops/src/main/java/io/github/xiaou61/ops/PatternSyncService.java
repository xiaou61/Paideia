package io.github.xiaou61.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
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
 */
@Service
class PatternSyncService {

    private static final int PATTERN_COLUMN_MAX = 500;
    private static final int SAMPLE_COLUMN_MAX = 1000;

    private final OpsProperties properties;
    private final PatternMapper mapper;
    private final LokiClient loki;
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
            Instant bucketStart = bucketStart(now, cfg.getWindowMinutes());
        Instant bucketEnd = bucketStart.plusSeconds(cfg.getWindowMinutes() * 60L);
        ZonedDateTime local = bucketStart.atZone(ZoneId.systemDefault());
        int dow = local.getDayOfWeek().getValue() % 7; // 0=周日，与 buckets.dow 约定一致
        int hour = local.getHour();

        int written = 0;
        for (String unit : cfg.getUnits()) {
            if (unit == null || unit.isBlank()) {
                continue;
            }
            String selector = "{unit=\"" + unit + ".service\"}";
            List<LokiClient.PatternSample> samples =
                    loki.patterns(selector, bucketStart, bucketEnd, cfg.getMaxTemplates());
            for (LokiClient.PatternSample sample : samples) {
                String hash = sha256(sample.pattern());
                String pattern = truncate(sample.pattern(), PATTERN_COLUMN_MAX);
                String sampleLine = truncate(sample.pattern(), SAMPLE_COLUMN_MAX);
                Instant seenAt = sample.at() == null ? now : sample.at();
                int count = (int) Math.min(Integer.MAX_VALUE, Math.max(0, sample.count()));
                mapper.upsertTemplate(hash, unit, pattern, sampleLine, seenAt, seenAt,
                        count, count, now, now);
                mapper.upsertBucket(hash, unit, bucketStart, dow, hour, count, now);
                written++;
            }
        }
            purge(now, cfg.getRetentionDays());
            return written;
        } finally {
            running.set(false);
        }
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
