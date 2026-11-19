package io.github.xiaou61.ops;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 模板级异常检测（WORK-085）：频次突增、新模板出现、模板消失。
 *
 * <p>以「模板」为驱动遍历（{@link PatternMapper#recentTemplates}），而不是遍历「有桶的模板」——
 * 只有这样才发现**消失**：某模板这一窗口一条都没有，就不会有桶，遍历桶永远看不见它。
 *
 * <p>基线 = 同 dow+hour 的历史窗口条数（近 baseline-weeks 周）。冷启动（本单元窗口之前从无桶）
 * 时不判「新模板」，否则第一次接入会把全量模板都报成新模板。
 */
@Service
class PatternAnomalyDetector {

    private final OpsProperties properties;
    private final PatternMapper mapper;

    PatternAnomalyDetector(OpsProperties properties, PatternMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 检测一轮；返回本轮新写入（未被去重挡掉）的异常行。 */
    List<PatternMapper.AnomalyRow> detect(Instant now) {
        OpsProperties.Patterns cfg = properties.getPatterns();
        if (!cfg.isEnabled()) {
            return List.of();
        }
        Instant windowStart = PatternSyncService.bucketStart(now, cfg.getWindowMinutes());
        Instant windowEnd = windowStart.plusSeconds(cfg.getWindowMinutes() * 60L);
        Instant prevStart = windowStart.minusSeconds(cfg.getWindowMinutes() * 60L);
        ZonedDateTime local = windowStart.atZone(ZoneId.systemDefault());
        int dow = local.getDayOfWeek().getValue() % 7;
        int hour = local.getHour();
        Instant baselineSince = windowStart.minusSeconds(cfg.getBaselineWeeks() * 7L * 86_400L);

        List<PatternMapper.AnomalyRow> out = new ArrayList<>();
        for (String unit : cfg.getUnits()) {
            if (unit == null || unit.isBlank()) {
                continue;
            }
            boolean coldStart = mapper.countBucketsBefore(unit, windowStart) == 0;
            List<PatternMapper.Template> templates =
                    mapper.recentTemplates(unit, now.minusSeconds(24 * 3600L), cfg.getMaxTemplates());
            for (PatternMapper.Template t : templates) {
                long current = mapper.sumInWindow(t.templateHash(), unit, windowStart, windowEnd);
                List<Integer> baseline = mapper.baselineCounts(
                        t.templateHash(), unit, dow, hour, baselineSince, windowStart);
                Stats stats = Stats.of(baseline);

                String kind = classify(cfg, coldStart, t, current, stats,
                        mapper.sumInWindow(t.templateHash(), unit, prevStart, windowStart), windowStart);
                if (kind == null) {
                    continue;
                }
                // 同一模板同一类型在同一个窗口里只记一条，重复同步不追加。
                if (mapper.countRecent(kind, t.templateHash(), unit, windowStart) > 0) {
                    continue;
                }
                PatternMapper.AnomalyRow row = new PatternMapper.AnomalyRow();
                row.setKind(kind);
                row.setTemplateHash(t.templateHash());
                row.setUnit(unit);
                row.setCurrentCount((int) Math.min(Integer.MAX_VALUE, current));
                row.setBaselineCount((int) Math.round(stats.avg));
                row.setRatio(stats.avg > 0 ? round(current / stats.avg) : null);
                row.setZ(stats.std > 0 ? round((current - stats.avg) / stats.std) : null);
                row.setWindowStart(windowStart);
                row.setWindowEnd(windowEnd);
                row.setStatus("OPEN");
                row.setDetail(detail(kind, t, current, stats));
                row.setCreatedAt(now);
                mapper.insertAnomaly(row);
                out.add(row);
            }
        }
        return out;
    }

    private String classify(OpsProperties.Patterns cfg, boolean coldStart,
                            PatternMapper.Template t, long current, Stats stats, long prevWindow,
                            Instant windowStart) {
        if (!coldStart && current >= cfg.getNewTemplateMinCount() && !t.firstSeen().isBefore(windowStart)) {
            return "NEW_TEMPLATE";
        }
        if (stats.samples >= 3 && stats.avg > 0
                && current / stats.avg >= cfg.getFreqSpikeRatio()
                && stats.std > 0 && (current - stats.avg) / stats.std >= cfg.getZScoreThreshold()) {
            return "FREQ_SPIKE";
        }
        if (stats.avg >= cfg.getDisappearedBaselineMin() && current == 0 && prevWindow == 0) {
            return "DISAPPEARED";
        }
        return null;
    }

    private static String detail(String kind, PatternMapper.Template t, long current, Stats stats) {
        String pattern = PatternSyncService.truncate(t.pattern(), 200);
        return switch (kind) {
            case "NEW_TEMPLATE" -> "新模板出现：" + current + " 条（近 " + stats.samples
                    + " 个同 dow/hour 窗口未见此模板）。模板：" + pattern;
            case "FREQ_SPIKE" -> "模板频次突增：本窗口 " + current + " 条，基线均值 "
                    + round(stats.avg) + "（样本 " + stats.samples + "），z=" + round(stats.z(current)) + "。模板：" + pattern;
            case "DISAPPEARED" -> "模板消失（服务静默）：基线均值 " + round(stats.avg)
                    + " 条，本窗口与上一窗口均为 0。模板：" + pattern;
            default -> pattern;
        };
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 基线统计。 */
    static final class Stats {
        final int samples;
        final double avg;
        final double std;

        private Stats(int samples, double avg, double std) {
            this.samples = samples;
            this.avg = avg;
            this.std = std;
        }

        static Stats of(List<Integer> values) {
            if (values == null || values.isEmpty()) {
                return new Stats(0, 0, 0);
            }
            double sum = 0;
            for (Integer v : values) {
                sum += v == null ? 0 : v;
            }
            double avg = sum / values.size();
            double var = 0;
            for (Integer v : values) {
                double d = (v == null ? 0 : v) - avg;
                var += d * d;
            }
            double std = values.size() > 1 ? Math.sqrt(var / (values.size() - 1)) : 0;
            return new Stats(values.size(), avg, std);
        }

        double z(long current) {
            return std > 0 ? (current - avg) / std : 0;
        }
    }
}
