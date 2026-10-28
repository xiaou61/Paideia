package io.github.xiaou61.ops;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 诊断证据里的「历史同类 + 同期事件」（WORK-049）。
 *
 * <p>两条来源分开写、分开失败：历史查询挂了不能连累同期事件，反之亦然。
 * 两段总量硬上限 2000 字符——证据总预算 12k 是给「当前故障」的，历史只是背景，
 * 不能让背景把日志挤掉。
 *
 * <p>**只带结论首行与评分，不带历史报告全文**：模型需要的是「上次怎么判断、后来准不准」，
 * 不是把上一次的四千字再读一遍。
 */
@Service
class OpsIncidentContext {

    private static final Logger log = LoggerFactory.getLogger(OpsIncidentContext.class);

    static final int MAX_CHARS = 2_000;
    private static final int HISTORY_DAYS = 30;
    private static final int CONCURRENT_MINUTES = 30;
    private static final int MAX_CONCLUSION_CHARS = 200;
    private static final int MAX_HISTORY_CHARS = 800;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final OpsIncidentMapper incidents;

    OpsIncidentContext(OpsIncidentMapper incidents) {
        this.incidents = incidents;
    }

    /** 永不返回 null；无事件时也给显式文案。 */
    String build(Long incidentId, String alertName, Instant now) {
        StringBuilder history = new StringBuilder("## 历史同类事件\n");
        StringBuilder concurrent = new StringBuilder("## 同期其他事件\n");

        if (incidentId == null) {
            history.append("无历史同类事件（本次诊断未挂事件）\n");
            concurrent.append("无同期事件\n");
            return join(history, concurrent);
        }

        OpsIncident current = incidents.findById(incidentId).orElse(null);
        if (current == null) {
            history.append("无历史同类事件（事件不存在）\n");
        } else {
            appendHistory(history, current, alertName, now);
        }
        appendConcurrent(concurrent, incidentId, now);
        return join(history, concurrent);
    }

    private void appendHistory(StringBuilder out, OpsIncident current, String alertName, Instant now) {
        List<OpsIncidentMapper.HistoryRow> rows = new ArrayList<>();
        try {
            Instant since = now.minus(Duration.ofDays(HISTORY_DAYS));
            rows.addAll(incidents.listHistoryByFingerprint(
                    current.getFingerprint(), current.getId(), since, 3));
            if (rows.size() < 3 && alertName != null && !alertName.isBlank()) {
                Set<Long> seen = new HashSet<>();
                for (OpsIncidentMapper.HistoryRow row : rows) {
                    seen.add(row.incidentId());
                }
                for (OpsIncidentMapper.HistoryRow row : incidents.listHistoryByAlertName(alertName, current.getId(), since, 2)) {
                    if (seen.add(row.incidentId())) {
                        rows.add(row);
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.warn("ops.context.history-failed incident={} error={}",
                    current.getId(), failure.toString());
            out.append("历史查询失败（诊断继续）\n");
            return;
        }
        if (rows.isEmpty()) {
            out.append("无历史同类事件\n");
            return;
        }
        for (OpsIncidentMapper.HistoryRow row : rows) {
            out.append("- ").append(TIME.format(row.firstSeen()))
                    .append(" 跨度 ").append(duration(row))
                    .append(' ').append("RESOLVED".equals(row.status()) ? "已恢复" : "仍未恢复");
            if (row.diagnosisId() != null) {
                out.append(" 诊断#").append(row.diagnosisId())
                        .append(" 评分=").append(rating(row.rating()));
            } else {
                out.append(" 未诊断");
            }
            out.append(" 结论：").append(conclusion(row.reportMd())).append('\n');
        }
    }

    private void appendConcurrent(StringBuilder out, long incidentId, Instant now) {
        List<OpsIncident> others;
        try {
            others = incidents.listOpenSince(now.minus(Duration.ofMinutes(CONCURRENT_MINUTES)),
                    incidentId, 5);
        } catch (RuntimeException failure) {
            log.warn("ops.context.concurrent-failed incident={} error={}", incidentId, failure.toString());
            out.append("同期事件查询失败（诊断继续）\n");
            return;
        }
        if (others.isEmpty()) {
            out.append("无同期事件\n");
            return;
        }
        for (OpsIncident other : others) {
            out.append("- [OPEN] ").append(other.getAlertName());
            if (other.getSeverity() != null && !other.getSeverity().isBlank()) {
                out.append(" severity=").append(other.getSeverity());
            }
            out.append(" 首次=").append(TIME.format(other.getFirstSeen())).append('\n');
        }
    }

    private static String join(StringBuilder history, StringBuilder concurrent) {
        String head = cap(history.toString(), MAX_HISTORY_CHARS);
        String all = head + concurrent;
        if (all.length() <= MAX_CHARS) {
            return all;
        }
        return all.substring(0, MAX_CHARS - 12) + "…（已截断）\n";
    }

    private static String cap(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 12) + "…（已截断）\n";
    }

    private static String duration(OpsIncidentMapper.HistoryRow row) {
        long minutes = Duration.between(row.firstSeen(), row.lastSeen()).toMinutes();
        if (minutes < 1) {
            return "不足 1 分钟";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        return (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟";
    }

    private static String rating(Integer rating) {
        if (rating == null) {
            return "未评分";
        }
        return switch (rating) {
            case 1 -> "准";
            case -1 -> "不准";
            default -> "部分准";
        };
    }

    private static String conclusion(String report) {
        if (report == null || report.isBlank()) {
            return "无结论";
        }
        for (String line : report.split("\\R")) {
            String stripped = line.strip();
            // 跳过 Markdown 标题：报告的第一行是 `## 结论`，要的是它下面那句
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            return stripped.length() <= MAX_CONCLUSION_CHARS
                    ? stripped
                    : stripped.substring(0, MAX_CONCLUSION_CHARS) + "…";
        }
        return "无结论";
    }
}
