package io.github.xiaou61.ops;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 模板/异常的只读查询与证据拼装（WORK-085）。
 *
 * <p>两处消费者：① {@link OpsEvidenceCollector} 把「Top 模板 + 近期异常」拼进诊断证据；
 * ② 管理端只读端点展示。文本一律截断到 {@code maxPatternChars}，避免超长模板把证据预算吃光。
 */
@Service
class PatternQueryService {

    private final OpsProperties properties;
    private final PatternMapper mapper;

    PatternQueryService(OpsProperties properties, PatternMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 拼成证据里的一段；关闭或无数据时返回空串（prompt 会写「（不可达或无数据）」）。 */
    String evidenceText(Instant now) {
        OpsProperties.Patterns cfg = properties.getPatterns();
        if (!cfg.isEnabled()) {
            return "";
        }
        Instant windowStart = PatternSyncService.bucketStart(now, cfg.getWindowMinutes());
        StringBuilder out = new StringBuilder();
        int budget = cfg.getMaxPatternChars();
        // REQ-008：最小拓扑一行——让模型能区分「依赖挂了」与「应用自身报错」。
        String topology = cfg.getTopology();
        if (topology != null && !topology.isBlank()) {
            out.append("依赖拓扑：").append(topology).append("\n");
        }
        for (String unit : cfg.getUnits()) {
            if (unit == null || unit.isBlank()) {
                continue;
            }
            List<PatternMapper.TopTemplate> top =
                    mapper.topTemplates(unit, windowStart, now, cfg.getTopN());
            if (top.isEmpty()) {
                continue;
            }
            out.append("[").append(unit).append("] Top 模板（近 ")
                    .append(cfg.getWindowMinutes()).append(" 分钟）：\n");
            for (PatternMapper.TopTemplate t : top) {
                String line = "- " + t.count() + " 次 | " + PatternSyncService.truncate(t.pattern(), 160) + "\n";
                if (out.length() + line.length() > budget) {
                    out.append("- …（已截断）\n");
                    return out.toString();
                }
                out.append(line);
            }
        }
        return out.toString();
    }

    List<PatternView> top(Instant now, String unit) {
        OpsProperties.Patterns cfg = properties.getPatterns();
        Instant windowStart = PatternSyncService.bucketStart(now, cfg.getWindowMinutes());
        List<PatternView> views = new ArrayList<>();
        for (String u : cfg.getUnits()) {
            if (unit != null && !unit.isBlank() && !unit.equals(u)) {
                continue;
            }
            for (PatternMapper.TopTemplate t : mapper.topTemplates(u, windowStart, now, cfg.getTopN())) {
                views.add(new PatternView(t.unit(), t.templateHash(), t.pattern(), t.count()));
            }
        }
        return views;
    }

    List<AnomalyView> recentAnomalies(Instant now, int hours, int limit) {
        return mapper.recentAnomalies(now.minusSeconds(Math.max(1, hours) * 3600L), limit)
                .stream().map(AnomalyView::of).toList();
    }

    /** REQ-007：历史诊断与历史模板异常的全文检索（MySQL FULLTEXT，不做向量化）。 */
    SearchResult search(String q, int limit) {
        if (q == null || q.isBlank()) {
            return new SearchResult(List.of(), List.of());
        }
        int capped = Math.min(Math.max(1, limit), 50);
        List<DiagnosisView> diagnoses = mapper.searchDiagnoses(q, capped).stream()
                .map(d -> new DiagnosisView(d.id(), excerpt(d.reportMd()), d.createdAt()))
                .toList();
        List<AnomalyView> anomalies = mapper.searchAnomalies(q, capped).stream()
                .map(AnomalyView::of).toList();
        return new SearchResult(diagnoses, anomalies);
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    record PatternView(String unit, String templateHash, String pattern, long count) {
    }

    record AnomalyView(long id, String kind, String templateHash, String unit, Double ratio, Double z,
                       Integer baselineCount, Integer currentCount, String status, Long diagnosisId,
                       String detail, Instant createdAt) {
        static AnomalyView of(PatternMapper.AnomalyRow r) {
            return new AnomalyView(r.getId() == null ? 0 : r.getId(), r.getKind(), r.getTemplateHash(),
                    r.getUnit(), r.getRatio(), r.getZ(), r.getBaselineCount(), r.getCurrentCount(),
                    r.getStatus(), r.getDiagnosisId(), r.getDetail(), r.getCreatedAt());
        }
    }

    record DiagnosisView(long id, String excerpt, Instant createdAt) {
    }

    record SearchResult(List<DiagnosisView> diagnoses, List<AnomalyView> anomalies) {
    }
}
