package io.github.xiaou61.ops;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 数据大屏的只读聚合（WORK-045）。
 *
 * <p><b>为什么直接在 {@code paideia-app} 写 SQL</b>：`ModularityTest` 禁止跨模块引用其它模块的
 * `internal` 包，给六个模块各加一个公开统计接口只为这一页不值得。这些查询**只读、不修改**
 * 任何模块的数据，也不复制它的业务规则——口径（什么算"用过"）定义在大屏侧。
 *
 * <p><b>全部有界</b>：每条要么 `COUNT(*)`（走索引或表很小）、要么带时间窗。
 * 表行数上限 4609（`refresh_tokens`），在可预见期内不需要预聚合。
 *
 * <p><b>时区</b>：`GROUP BY DATE(...)` 用的是会话时区，而 JDBC 已强制 UTC，
 * 所以按天聚合必须显式 `CONVERT_TZ(..., '+00:00', offset)`。**用数字偏移而不是时区名**：
 * 生产库账号读不到 `mysql.time_zone_name`，时区名会返回 NULL（2026-10-27 实测）。
 */
@Mapper
interface AnalyticsQueries {

    // ── 各表总量 ──────────────────────────────────────────────────────────────

    @Select("SELECT COUNT(*) FROM knowledge_entries WHERE status = 'published'")
    long publishedEntries();

    @Select("SELECT COUNT(*) FROM knowledge_categories")
    long categories();

    @Select("SELECT COUNT(*) FROM knowledge_entry_views")
    long entryViewRows();

    /** 浏览总次数（每人每篇一行累加，所以这里求和才是"次数"）。 */
    @Select("SELECT COALESCE(SUM(view_count), 0) FROM knowledge_entry_views")
    long entryViewTotal();

    @Select("SELECT COUNT(*) FROM digest_items")
    long digestItems();

    @Select("SELECT COUNT(*) FROM digest_item_clicks")
    long digestClicks();

    @Select("SELECT COUNT(*) FROM ai_runs")
    long aiRuns();

    @Select("SELECT COUNT(*) FROM ai_conversations")
    long aiConversations();

    @Select("SELECT COUNT(*) FROM ai_messages")
    long aiMessages();

    @Select("SELECT COUNT(*) FROM ai_credit_ledger")
    long creditLedgerRows();

    @Select("SELECT COUNT(*) FROM learning_progress")
    long learningProgress();

    @Select("SELECT COUNT(*) FROM question_attempts")
    long questionAttempts();

    @Select("SELECT COUNT(*) FROM concept_mastery_state")
    long masteryStates();

    @Select("SELECT COUNT(*) FROM review_sessions")
    long reviewSessions();

    @Select("SELECT COUNT(*) FROM resume_documents")
    long resumeDocuments();

    @Select("SELECT COUNT(*) FROM resume_edit_history")
    long resumeEdits();

    @Select("SELECT COUNT(*) FROM code_runs")
    long codeRuns();

    @Select("SELECT COUNT(*) FROM knowledge_search_log")
    long searchLogs();

    @Select("SELECT COUNT(*) FROM notes")
    long notes();

    @Select("SELECT COUNT(*) FROM users")
    long accounts();

    // ── 各表近 N 天增量 ───────────────────────────────────────────────────────

    @Select("SELECT COUNT(*) FROM ai_runs WHERE started_at >= #{from}")
    long aiRunsSince(@Param("from") Instant from);

    @Select("SELECT COUNT(*) FROM digest_items WHERE created_at >= #{from}")
    long digestItemsSince(@Param("from") Instant from);

    @Select("SELECT COUNT(*) FROM digest_item_clicks WHERE clicked_at >= #{from}")
    long digestClicksSince(@Param("from") Instant from);

    @Select("SELECT COUNT(*) FROM knowledge_search_log WHERE searched_at >= #{from}")
    long searchesSince(@Param("from") Instant from);

    @Select("SELECT COUNT(*) FROM knowledge_entry_views WHERE last_viewed_at >= #{from}")
    long entryViewsSince(@Param("from") Instant from);

    @Select("SELECT COUNT(*) FROM resume_documents WHERE created_at >= #{from}")
    long resumesSince(@Param("from") Instant from);

    @Select("SELECT COUNT(*) FROM question_attempts WHERE created_at >= #{from}")
    long attemptsSince(@Param("from") Instant from);

    @Select("SELECT COUNT(*) FROM code_runs WHERE created_at >= #{from}")
    long codeRunsSince(@Param("from") Instant from);

    // ── 按天时序（北京自然日）────────────────────────────────────────────────

    /**
     * 近 N 天的四条主线新增量。
     *
     * <p>一条 SQL 出四个 series：四次往返换来的只是更清楚的代码，而这是页面首屏最重的一次查询。
     * `CONVERT_TZ` 的第三个参数是**数字偏移**（`+08:00`），由调用方从业务时区算出来传进来。
     */
    @Select("""
            SELECT d, series, COUNT(*) AS total FROM (
                SELECT DATE(CONVERT_TZ(started_at, '+00:00', #{offset})) AS d, 'ai_runs' AS series
                FROM ai_runs WHERE started_at >= #{from}
                UNION ALL
                SELECT DATE(CONVERT_TZ(created_at, '+00:00', #{offset})), 'digest_items'
                FROM digest_items WHERE created_at >= #{from}
                UNION ALL
                SELECT DATE(CONVERT_TZ(last_viewed_at, '+00:00', #{offset})), 'entry_views'
                FROM knowledge_entry_views WHERE last_viewed_at >= #{from}
                UNION ALL
                SELECT DATE(CONVERT_TZ(created_at, '+00:00', #{offset})), 'resumes'
                FROM resume_documents WHERE created_at >= #{from}
                UNION ALL
                SELECT DATE(CONVERT_TZ(clicked_at, '+00:00', #{offset})), 'digest_clicks'
                FROM digest_item_clicks WHERE clicked_at >= #{from}
            ) daily
            GROUP BY d, series
            ORDER BY d
            """)
    List<DailyRow> daily(@Param("from") Instant from, @Param("offset") String offset);

    // ── AI 汇总 ───────────────────────────────────────────────────────────────

    @Select("""
            SELECT kind AS name, COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN status = 'ok' THEN 1 ELSE 0 END), 0) AS ok
            FROM ai_runs
            GROUP BY kind
            ORDER BY total DESC
            """)
    List<KindRow> aiByKind();

    @Select("SELECT COUNT(*) FROM ai_runs WHERE status = 'ok'")
    long aiRunsOk();

    /** 积分：发放（正值）与消耗（负值取绝对值）。只认流水，不认账户余额列的累加。 */
    @Select("SELECT COALESCE(SUM(CASE WHEN delta > 0 THEN delta ELSE 0 END), 0) FROM ai_credit_ledger")
    long creditsGranted();

    @Select("SELECT COALESCE(-SUM(CASE WHEN delta < 0 THEN delta ELSE 0 END), 0) FROM ai_credit_ledger")
    long creditsSpent();

    @Select("SELECT COALESCE(SUM(balance), 0) FROM ai_credit_accounts")
    long creditsBalance();

    // ── 搜索词 ────────────────────────────────────────────────────────────────

    @Select("""
            SELECT keyword AS name, COUNT(*) AS total
            FROM knowledge_search_log
            WHERE searched_at >= #{from}
            GROUP BY keyword
            ORDER BY total DESC, name
            LIMIT #{limit}
            """)
    List<NameCount> topKeywords(@Param("from") Instant from, @Param("limit") int limit);

    /**
     * 零结果搜索词：**内容缺口清单**。
     *
     * <p>同一关键词可能既搜到过也搜不到过（内容后来补上了），所以这类只统计"最近一次结果
     * 仍为 0"的词会太绕；这里直接取"有零结果记录"的词按次数排——它回答的是"用户反复在找
     * 什么而我们没有"。
     */
    @Select("""
            SELECT keyword AS name, COUNT(*) AS total
            FROM knowledge_search_log
            WHERE searched_at >= #{from} AND result_count = 0
            GROUP BY keyword
            ORDER BY total DESC, name
            LIMIT #{limit}
            """)
    List<NameCount> zeroResultKeywords(@Param("from") Instant from, @Param("limit") int limit);

    // ── 速递点击 ──────────────────────────────────────────────────────────────

    /** 有点击的条目数与总点击次数——「点击率」的分子。 */
    @Select("SELECT COUNT(DISTINCT item_id) FROM digest_item_clicks WHERE clicked_at >= #{from}")
    long clickedItemCount(@Param("from") Instant from);

    @Select("SELECT COUNT(*) FROM digest_item_clicks WHERE clicked_at >= #{from}")
    long clickCount(@Param("from") Instant from);

    /** 热门速递前 N：带上标题，条目被删时标题为 null（左连接）。 */
    @Select("""
            SELECT c.item_id AS id, COALESCE(i.title, CONCAT('已删除条目 #', c.item_id)) AS title,
                   COUNT(*) AS total
            FROM digest_item_clicks c
            LEFT JOIN digest_items i ON i.id = c.item_id
            WHERE c.clicked_at >= #{from}
            GROUP BY c.item_id, i.title
            ORDER BY total DESC, c.item_id
            LIMIT #{limit}
            """)
    List<ClickRow> topClickedItems(@Param("from") Instant from, @Param("limit") int limit);

    // ── 行类型 ────────────────────────────────────────────────────────────────

    record DailyRow(String d, String series, long total) {
    }

    record KindRow(String name, long total, long ok) {
    }

    record NameCount(String name, long total) {
    }

    record ClickRow(long id, String title, long total) {
    }
}
