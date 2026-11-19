package io.github.xiaou61.ops;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 用 MySQL 全文索引召回历史复盘（WORK-086）。
 *
 * <p>为什么不是向量检索：项目规则（`.agent/rules/always.md`）明确本期不引向量库，内容检索统一走
 * MySQL `FULLTEXT ... WITH PARSER ngram`——中文没有空格，ngram 解析器才能按 2-gram 切出词。
 * 代价是它只会"字面相近"，不会"意思相近"；对"同一条告警反复出现"这个主场景够用。
 *
 * <p>排序里 `alert_name` 精确命中排在最前：同一个告警的复盘比"文字上碰巧像"的复盘有用得多。
 */
@Service
class PostmortemKnowledgeRecall implements PostmortemKnowledge {

    private static final Logger log = LoggerFactory.getLogger(PostmortemKnowledgeRecall.class);

    /** 注入的每条根因/处置上限：一次诊断只该被"提醒"，不该被历史复盘占满证据预算。 */
    static final int MAX_FIELD_CHARS = 400;
    static final int DEFAULT_LIMIT = 2;

    private final PostmortemMapper postmortems;

    @Autowired
    PostmortemKnowledgeRecall(PostmortemMapper postmortems) {
        this.postmortems = postmortems;
    }

    @Override
    public List<Hit> recall(String alertName, String signatures, int limit) {
        String query = normalize(signatures);
        if (query.isEmpty()) {
            // 没有关键词就不查库：空查询在自然语言模式下会命中大量无关文档，等于随机塞经验。
            return List.of();
        }
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 5);
        String alert = alertName == null ? "" : alertName.strip();
        try {
            return postmortems.searchApproved(alert, query, safeLimit).stream()
                    .map(row -> new Hit(row.getId(), row.getTitle(), row.getApprovedAt(),
                            clip(row.getRootCause()), clip(row.getActionsMd())))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("postmortem knowledge unavailable, continue without it: {}", e.toString());
            return List.of();
        }
    }

    /** 签名以换行或空白分隔，全文检索要的是一段话。 */
    private static String normalize(String signatures) {
        if (signatures == null || signatures.isBlank()) {
            return "";
        }
        return signatures.replace('\n', ' ').replace('\u0000', ' ').strip();
    }

    private static String clip(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= MAX_FIELD_CHARS ? flat : flat.substring(0, MAX_FIELD_CHARS - 1) + "…";
    }

    /**
     * 把命中渲染成提示词里的一段（design §6）。
     *
     * <p>没有任何命中时返回空串，由提示词层渲染成"（不可达或无数据）"——不写"本次未取得历史复盘"
     * 这种看似结论的话，那会让模型以为"历史上没发生过"，而事实可能只是没检索到。
     *
     * @param maxChars 整段预算；超出按条丢弃，而不是把最后一条切成半句
     */
    static String render(List<Hit> hits, int maxChars) {
        if (hits == null || hits.isEmpty() || maxChars <= 0) {
            return "";
        }
        java.time.format.DateTimeFormatter day =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                        .withZone(java.time.ZoneId.of("Asia/Shanghai"));
        StringBuilder out = new StringBuilder("## 历史复盘（人工确认过的经验，仅作参考）\n");
        int added = 0;
        for (Hit hit : hits) {
            StringBuilder block = new StringBuilder();
            block.append("### 复盘 #").append(hit.id()).append(" · ")
                    .append(hit.approvedAt() == null ? "未记录日期" : day.format(hit.approvedAt()))
                    .append(" · ").append(hit.title() == null ? "" : hit.title()).append("（APPROVED）\n");
            if (!hit.rootCause().isBlank()) {
                block.append("根因：").append(hit.rootCause()).append('\n');
            }
            if (!hit.actions().isBlank()) {
                block.append("处置：").append(hit.actions()).append('\n');
            }
            if (out.length() + block.length() > maxChars) {
                break;
            }
            out.append(block);
            added++;
        }
        if (added == 0) {
            return "";
        }
        out.append("注意：与本次证据冲突时，以本次证据为准。\n");
        return out.toString().strip();
    }
}
