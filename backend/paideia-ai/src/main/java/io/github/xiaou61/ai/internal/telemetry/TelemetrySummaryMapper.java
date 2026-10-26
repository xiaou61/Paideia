package io.github.xiaou61.ai.internal.telemetry;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 统计聚合（WORK-022 T10）。
 *
 * <p><b>分位数在 SQL 里算</b>，不把明细行拉进应用内存：`ROW_NUMBER() OVER (ORDER BY ...)`
 * 的最近秩法与"排序后手算"一致（AC-010），而一张百万行表拉进 JVM 只是等着 OOM。
 *
 * <p>只读聚合，不返回任何正文——统计页要的是数字，不是内容。
 */
@Mapper
public interface TelemetrySummaryMapper {

    /** 按状态分组的计数。 */
    @Select("""
            SELECT status, COUNT(*) AS count FROM ai_runs
            WHERE started_at >= #{from} AND started_at < #{to}
            GROUP BY status
            """)
    List<CountBy> countByStatus(@Param("from") Instant from, @Param("to") Instant to);

    @Select("""
            <script>
            SELECT kind AS name, COUNT(*) AS count FROM ai_runs
            WHERE started_at >= #{from} AND started_at < #{to}
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="model != null">AND model = #{model}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
                        GROUP BY kind ORDER BY count DESC
            </script>
            """)
    List<CountBy> countByKind(@Param("from") Instant from, @Param("to") Instant to,
            @Param("kind") String kind, @Param("status") String status,
            @Param("model") String model, @Param("userId") Long userId);

    @Select("""
            <script>
            SELECT COALESCE(model, '(unknown)') AS name, COUNT(*) AS count FROM ai_runs
            WHERE started_at >= #{from} AND started_at < #{to}
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="model != null">AND model = #{model}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
                        GROUP BY model ORDER BY count DESC
            </script>
            """)
    List<CountBy> countByModel(@Param("from") Instant from, @Param("to") Instant to,
            @Param("kind") String kind, @Param("status") String status,
            @Param("model") String model, @Param("userId") Long userId);

    @Select("""
            <script>
            SELECT error_type AS name, COUNT(*) AS count FROM ai_runs
            WHERE started_at >= #{from} AND started_at < #{to} AND error_type IS NOT NULL
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="model != null">AND model = #{model}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
                        GROUP BY error_type ORDER BY count DESC LIMIT 10
            </script>
            """)
    List<CountBy> countByErrorType(@Param("from") Instant from, @Param("to") Instant to,
            @Param("kind") String kind, @Param("status") String status,
            @Param("model") String model, @Param("userId") Long userId);

    @Select("""
            SELECT name, COUNT(*) AS count FROM ai_run_spans
            WHERE kind = 'tool' AND started_at >= #{from} AND started_at < #{to}
            GROUP BY name ORDER BY count DESC LIMIT 10
            """)
    List<CountBy> countByTool(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * 耗时分位数（最近秩法）。
     *
     * <p>`duration_ms` 为 NULL 的行（仍在 running）不参与——把它们当 0 会把 p50 拉低到失真。
     */
    @Select("""
            <script>
            WITH ranked AS (
              SELECT duration_ms,
                     ROW_NUMBER() OVER (ORDER BY duration_ms) AS rn,
                     COUNT(*) OVER () AS n
              FROM ai_runs
              WHERE started_at >= #{from} AND started_at < #{to}
                AND duration_ms IS NOT NULL
              <choose>
                <when test="status != null">AND status = #{status}</when>
                <otherwise>AND status IN ('ok', 'failed', 'timeout')</otherwise>
              </choose>
              <if test="kind != null">AND kind = #{kind}</if>
              <if test="model != null">AND model = #{model}</if>
              <if test="userId != null">AND user_id = #{userId}</if>
            )
            SELECT MAX(CASE WHEN rn = CEIL(n * 0.50) THEN duration_ms END) AS p50,
                   MAX(CASE WHEN rn = CEIL(n * 0.95) THEN duration_ms END) AS p95
            FROM ranked
            </script>
            """)
    Percentiles durationPercentiles(@Param("from") Instant from, @Param("to") Instant to,
            @Param("kind") String kind, @Param("status") String status,
            @Param("model") String model, @Param("userId") Long userId);

    @Select("""
            <script>
            WITH ranked AS (
              SELECT ttft_ms,
                     ROW_NUMBER() OVER (ORDER BY ttft_ms) AS rn,
                     COUNT(*) OVER () AS n
              FROM ai_runs
              WHERE started_at >= #{from} AND started_at < #{to}
                AND ttft_ms IS NOT NULL
              <choose>
                <when test="status != null">AND status = #{status}</when>
                <otherwise>AND status = 'ok'</otherwise>
              </choose>
              <if test="kind != null">AND kind = #{kind}</if>
              <if test="model != null">AND model = #{model}</if>
              <if test="userId != null">AND user_id = #{userId}</if>
            )
            SELECT MAX(CASE WHEN rn = CEIL(n * 0.50) THEN ttft_ms END) AS p50,
                   MAX(CASE WHEN rn = CEIL(n * 0.95) THEN ttft_ms END) AS p95
            FROM ranked
            </script>
            """)
    Percentiles ttftPercentiles(@Param("from") Instant from, @Param("to") Instant to,
            @Param("kind") String kind, @Param("status") String status,
            @Param("model") String model, @Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens,
                   COALESCE(SUM(credits), 0) AS credits
            FROM ai_runs WHERE started_at >= #{from} AND started_at < #{to}
            """)
    Totals totals(@Param("from") Instant from, @Param("to") Instant to);

    @Select("""
            SELECT kind AS name, COUNT(*) AS count FROM ai_runs
            WHERE started_at >= #{from} AND started_at < #{to} AND kind IS NOT NULL
            GROUP BY kind
            """)
    List<CountBy> distinctKinds(@Param("from") Instant from, @Param("to") Instant to);

    @Select("SELECT COALESCE(model, '(unknown)') AS name, COUNT(*) AS count FROM ai_runs "
            + "WHERE started_at >= #{from} AND started_at < #{to} AND model IS NOT NULL "
            + "GROUP BY model")
    List<CountBy> distinctModels(@Param("from") Instant from, @Param("to") Instant to);

    /** 带筛选的状态计数（WORK-036 AC-003）：概览必须与明细同一套条件。 */
    @Select("""
            <script>
            SELECT status, COUNT(*) AS count FROM ai_runs
            WHERE started_at &gt;= #{from} AND started_at &lt; #{to}
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="model != null">AND model = #{model}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
            GROUP BY status
            </script>
            """)
    List<CountBy> countByStatusFiltered(@Param("from") Instant from, @Param("to") Instant to,
                                        @Param("kind") String kind, @Param("status") String status,
                                        @Param("model") String model, @Param("userId") Long userId);

    /**
     * 按筛选条件收窄的合计（WORK-036）。
     *
     * <p>与 {@link #totals} 并存而不是替换：概览原先那份全量合计仍在用，
     * 两者差别只有 WHERE 里的四个可选条件。
     */
    @Select("""
            <script>
            SELECT COALESCE(SUM(input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens,
                   COALESCE(SUM(cache_read_tokens), 0) AS cacheReadTokens,
                   COALESCE(SUM(credits), 0) AS credits
            FROM ai_runs
            WHERE started_at &gt;= #{from} AND started_at &lt; #{to}
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="model != null">AND model = #{model}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
            </script>
            """)
    TotalsFiltered totalsFiltered(@Param("from") Instant from, @Param("to") Instant to,
                          @Param("kind") String kind, @Param("status") String status,
                          @Param("model") String model, @Param("userId") Long userId);

    /**
     * 按桶的计数与小计（WORK-036）。
     *
     * <p>分桶在 SQL 里算：把桶交给 Java 等于把整张表拉进内存。
     * {@code fmt} 与 {@code offsetMinutes} 由调用方按粒度与 zone 给
     * （`%Y-%m-%d %H:00` / `%Y-%m-%d`，`480`）。
     *
     * <p>**不用 CONVERT_TZ**：那要求服务器装载时区表，而它明确没有（见 always.md 的时间处理）。
     * 本项目只在一个固定偏移上分桶，`DATE_ADD` 足够，且行为可预测。
     */
    @Select("""
            <script>
            SELECT DATE_FORMAT(DATE_ADD(started_at, INTERVAL #{offsetMinutes} MINUTE), #{fmt}) AS bucket,
                   COUNT(*) AS total,
                   SUM(status = 'ok') AS ok,
                   SUM(status = 'failed') AS failed,
                   SUM(status = 'timeout') AS timeout,
                   COALESCE(SUM(input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens,
                   COALESCE(SUM(cache_read_tokens), 0) AS cacheReadTokens,
                   COALESCE(SUM(credits), 0) AS credits
            FROM ai_runs
            WHERE started_at &gt;= #{from} AND started_at &lt; #{to}
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="model != null">AND model = #{model}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
            GROUP BY bucket
            </script>
            """)
    List<BucketRow> countByBucket(@Param("from") Instant from, @Param("to") Instant to,
                                  @Param("kind") String kind, @Param("status") String status,
                                  @Param("model") String model, @Param("userId") Long userId,
                                  @Param("fmt") String fmt, @Param("offsetMinutes") int offsetMinutes);

    /** 按桶的耗时分位数。与 {@link #durationPercentiles} 同一最近秩法，只是多一层 PARTITION BY。 */
    @Select("""
            <script>
            WITH ranked AS (
              SELECT DATE_FORMAT(DATE_ADD(started_at, INTERVAL #{offsetMinutes} MINUTE), #{fmt}) AS bucket,
                     duration_ms,
                     ROW_NUMBER() OVER (
                       PARTITION BY DATE_FORMAT(DATE_ADD(started_at, INTERVAL #{offsetMinutes} MINUTE), #{fmt})
                       ORDER BY duration_ms) AS rn,
                     COUNT(*) OVER (
                       PARTITION BY DATE_FORMAT(DATE_ADD(started_at, INTERVAL #{offsetMinutes} MINUTE), #{fmt})) AS n
              FROM ai_runs
              WHERE started_at &gt;= #{from} AND started_at &lt; #{to}
                AND duration_ms IS NOT NULL
              <!-- 不传 status 时只看已终态的三种；传了就以调用方为准（否则条件互斥、分位数恒 NULL） -->
              <choose>
                <when test="status != null">AND status = #{status}</when>
                <otherwise>AND status IN ('ok', 'failed', 'timeout')</otherwise>
              </choose>
              <if test="kind != null">AND kind = #{kind}</if>
              <if test="model != null">AND model = #{model}</if>
              <if test="userId != null">AND user_id = #{userId}</if>
            )
            SELECT bucket,
                   MAX(CASE WHEN rn = CEIL(n * 0.50) THEN duration_ms END) AS p50,
                   MAX(CASE WHEN rn = CEIL(n * 0.95) THEN duration_ms END) AS p95
            FROM ranked GROUP BY bucket
            </script>
            """)
    List<BucketPercentiles> durationPercentilesByBucket(
            @Param("from") Instant from, @Param("to") Instant to,
            @Param("kind") String kind, @Param("status") String status,
            @Param("model") String model, @Param("userId") Long userId,
            @Param("fmt") String fmt, @Param("offsetMinutes") int offsetMinutes);

    /** 按桶的首字分位数。只统计成功轮次——失败轮次没有首字。 */
    @Select("""
            <script>
            WITH ranked AS (
              SELECT DATE_FORMAT(DATE_ADD(started_at, INTERVAL #{offsetMinutes} MINUTE), #{fmt}) AS bucket,
                     ttft_ms,
                     ROW_NUMBER() OVER (
                       PARTITION BY DATE_FORMAT(DATE_ADD(started_at, INTERVAL #{offsetMinutes} MINUTE), #{fmt})
                       ORDER BY ttft_ms) AS rn,
                     COUNT(*) OVER (
                       PARTITION BY DATE_FORMAT(DATE_ADD(started_at, INTERVAL #{offsetMinutes} MINUTE), #{fmt})) AS n
              FROM ai_runs
              WHERE started_at &gt;= #{from} AND started_at &lt; #{to}
                AND ttft_ms IS NOT NULL
              <!-- 首字只对成功轮次有意义：调用方不指定状态时锁 ok，指定了则以它为准 -->
              <choose>
                <when test="status != null">AND status = #{status}</when>
                <otherwise>AND status = 'ok'</otherwise>
              </choose>
              <if test="kind != null">AND kind = #{kind}</if>
              <if test="model != null">AND model = #{model}</if>
              <if test="userId != null">AND user_id = #{userId}</if>
            )
            SELECT bucket,
                   MAX(CASE WHEN rn = CEIL(n * 0.50) THEN ttft_ms END) AS p50,
                   MAX(CASE WHEN rn = CEIL(n * 0.95) THEN ttft_ms END) AS p95
            FROM ranked GROUP BY bucket
            </script>
            """)
    List<BucketPercentiles> ttftPercentilesByBucket(
            @Param("from") Instant from, @Param("to") Instant to,
            @Param("kind") String kind, @Param("status") String status,
            @Param("model") String model, @Param("userId") Long userId,
            @Param("fmt") String fmt, @Param("offsetMinutes") int offsetMinutes);

    /**
     * 按天的记录侧积分合计（WORK-036 对账）。
     *
     * <p>与流水侧分开查、在应用层合并：两张表的时间列与筛选口径不同，
     * 拼成一条 JOIN 会让"某天只有一边有数据"这件事难以表达。
     */
    @Select("""
            SELECT DATE_FORMAT(DATE_ADD(started_at, INTERVAL #{offsetMinutes} MINUTE), '%Y-%m-%d') AS day,
                   COALESCE(SUM(credits), 0) AS amount
            FROM ai_runs
            WHERE started_at >= #{from} AND started_at < #{to}
            GROUP BY day
            """)
    List<DayAmount> creditsByDay(@Param("from") Instant from, @Param("to") Instant to,
                                 @Param("offsetMinutes") int offsetMinutes);

    /**
     * 按天的流水侧**消耗**净额（WORK-036 对账）。
     *
     * <p>`RESERVE` 与 `ADJUST` 必须排除：前者只动余额、不写流水行（见 `CreditService` 的类注释），
     * 后者是管理员发分而不是消耗。不排除的话对账每天都会报异常，那种页面等于没有。
     *
     * <p>流水里消耗是负 delta，取反后与 `ai_runs.credits` 同向。
     */
    @Select("""
            SELECT DATE_FORMAT(DATE_ADD(created_at, INTERVAL #{offsetMinutes} MINUTE), '%Y-%m-%d') AS day,
                   COALESCE(-SUM(delta), 0) AS amount
            FROM ai_credit_ledger
            WHERE created_at >= #{from} AND created_at < #{to}
              AND kind NOT IN ('RESERVE', 'ADJUST')
            GROUP BY day
            """)
    List<DayAmount> ledgerSpendByDay(@Param("from") Instant from, @Param("to") Instant to,
                                     @Param("offsetMinutes") int offsetMinutes);

    /** 按模型的 token 合计，用于按价目表折算**名义成本**（WORK-036 REQ-004）。 */
    @Select("""
            <script>
            SELECT COALESCE(model, '(unknown)') AS name,
                   COALESCE(SUM(input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens
            FROM ai_runs
            WHERE started_at &gt;= #{from} AND started_at &lt; #{to}
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="model != null">AND model = #{model}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
            GROUP BY model
            </script>
            """)
    List<ModelTokens> tokensByModel(@Param("from") Instant from, @Param("to") Instant to,
                                    @Param("kind") String kind, @Param("status") String status,
                                    @Param("model") String model, @Param("userId") Long userId);

    record CountBy(String name, long count) {
    }

    record Percentiles(Integer p50, Integer p95) {
    }

    record Totals(long inputTokens, long outputTokens, long credits) {
    }

    /**
     * 带筛选的合计，比 {@link Totals} 多一个缓存命中。
     *
     * <p>**单开一个 record 而不是给 {@link Totals} 加字段**：后者会让既有的
     * `totals()` 调用点全部编译失败，而那一处（概览的全量合计）没有任何理由跟着改。
     */
    record TotalsFiltered(long inputTokens, long outputTokens, long cacheReadTokens, long credits) {
    }

    /** 一个时间桶的计数与小计。{@code bucket} 是格式化后的字符串（见各查询的 {@code fmt}）。 */
    record BucketRow(String bucket, long total, long ok, long failed, long timeout,
                     long inputTokens, long outputTokens, long cacheReadTokens, long credits) {
    }

    record BucketPercentiles(String bucket, Integer p50, Integer p95) {
    }

    record DayAmount(String day, long amount) {
    }

    record ModelTokens(String name, long inputTokens, long outputTokens) {
    }
}
