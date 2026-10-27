package io.github.xiaou61.knowledge.internal.entry;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 搜索记录的写入（WORK-045）。
 *
 * <p><b>放在服务端而不是前端上报</b>：`/entries?q=` 已经是唯一搜索入口（页头搜索框与首页
 * 搜索框都跳到这里），服务端记不需要改前端，且不会因为用户屏蔽脚本、快速切页而漏记。
 * `result_count` 直接取本次返回的 `total`，不会与实际结果不一致。
 *
 * <p><b>失败不影响搜索</b>：这是旁路数据，写不进去只记 WARN。与浏览上报同一纪律
 * （见 `EntryViewService` 的说明）。
 */
@Service
public class SearchLogService {

    private static final Logger log = LoggerFactory.getLogger(SearchLogService.class);

    /** 关键词上限。与列宽一致；超了截断而不是拒绝——用户搜长句是他的自由。 */
    static final int MAX_KEYWORD_CHARS = 100;

    private final SearchLogMapper mapper;

    SearchLogService(SearchLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记一次搜索。`keyword` 为空或全空白时**不记**：那不是搜索，只是列表页首次加载。
     *
     * @param keyword     用户输入的检索串（会在本方法内截断）
     * @param resultCount 本次命中的总数
     */
    public void record(Long userId, String keyword, long resultCount) {
        if (userId == null || keyword == null) {
            return;
        }
        String trimmed = keyword.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        String stored = trimmed.length() <= MAX_KEYWORD_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_KEYWORD_CHARS);
        try {
            // 截成 int：命中数不可能超过 int 上界，但列类型是 INT，跨类型传参更容易在
            // 驱动层出歧义
            mapper.insert(userId, stored, (int) Math.min(resultCount, Integer.MAX_VALUE), Instant.now());
        } catch (RuntimeException failure) {
            log.warn("knowledge.search.record-failed cause={}", failure.toString());
        }
    }
}
