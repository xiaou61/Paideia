package io.github.xiaou61.ai.internal.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 轮次表的读写（WORK-022 T01）。
 *
 * <p>`attrs` 存 JSON 文本：与 `ai_messages.token_usage` 同一做法（列是 JSON，Java 侧收 String，
 * 由调用方序列化）。**显式起别名**——`attrs` 与字段 `attrsJson` 不是下划线转驼峰能对上的关系，
 * 自动映射会静默给 null（WORK-011 踩过同一个坑）。
 */
@Mapper
public interface AiRunMapper {

    ObjectMapper JSON = new ObjectMapper();

    /**
     * 开始一轮：先写 running。
     *
     * <p>只写"开始时就知道"的列；其余留空，结束时由 {@link #finish} 补。
     */
    @Insert("""
            INSERT INTO ai_runs
                (trace_id, user_id, conversation_id, kind, harness, model, prompt_version,
                 status, started_at, attrs)
            VALUES (#{traceId}, #{userId}, #{conversationId}, #{kind}, #{harness}, #{model}, #{promptVersion},
                    'running', #{startedAt}, #{attrsJson})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRunning(AiRunRow row);

    /** 写终态。**带 status='running' 条件**：已采样的行被删掉后不该被这条"复活"成终态。 */
    @Update("""
            UPDATE ai_runs SET
                status = #{status}, error_type = #{errorType}, failure_kind = #{failureKind},
                ended_at = #{endedAt},
                queue_ms = #{queueMs}, duration_ms = #{durationMs}, ttft_ms = #{ttftMs},
                llm_rounds = #{llmRounds}, tool_calls = #{toolCalls},
                input_tokens = #{inputTokens}, output_tokens = #{outputTokens},
                cache_read_tokens = #{cacheReadTokens}, credits = #{credits},
                attrs = #{attrsJson}
            WHERE trace_id = #{traceId} AND status = 'running'
            """)
    int finish(AiRunRow row);

    /** 采样丢弃：删掉那条 running 行。条件里的 status 防止误删已写终态的行。 */
    @Delete("DELETE FROM ai_runs WHERE trace_id = #{traceId} AND status = 'running'")
    int deleteRunning(@Param("traceId") String traceId);

    /** 保留期清理：按 trace 批量删轮次（步骤由调用方先删）。 */
    @Delete("""
            <script>
            DELETE FROM ai_runs WHERE trace_id IN
            <foreach collection="traceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteByTraceIds(@Param("traceIds") List<String> traceIds);

    @Select("""
            SELECT id, trace_id, user_id, conversation_id, kind, harness, model, prompt_version,
                   status, error_type, failure_kind, started_at, ended_at, queue_ms, duration_ms, ttft_ms,
                   llm_rounds, tool_calls, input_tokens, output_tokens, cache_read_tokens,
                   credits, attrs AS attrsJson
            FROM ai_runs WHERE trace_id = #{traceId}
            """)
    AiRunRow findByTraceId(@Param("traceId") String traceId);

    @Select("""
            <script>
            SELECT id, trace_id, user_id, conversation_id, kind, harness, model, prompt_version,
                   status, error_type, failure_kind, started_at, ended_at, queue_ms, duration_ms, ttft_ms,
                   llm_rounds, tool_calls, input_tokens, output_tokens, cache_read_tokens,
                   credits, attrs AS attrsJson
            FROM ai_runs
            WHERE started_at &gt;= #{from} AND started_at &lt; #{to}
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="failureKind != null">AND failure_kind = #{failureKind}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
            <if test="model != null">AND model = #{model}</if>
            <if test="tracePrefix != null">AND trace_id LIKE CONCAT(#{tracePrefix}, '%')</if>
            ORDER BY started_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AiRunRow> page(@Param("from") Instant from, @Param("to") Instant to,
                        @Param("kind") String kind, @Param("status") String status,
                        @Param("failureKind") String failureKind,
                        @Param("userId") Long userId, @Param("model") String model,
                        @Param("tracePrefix") String tracePrefix,
                        @Param("limit") int limit, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_runs
            WHERE started_at &gt;= #{from} AND started_at &lt; #{to}
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="failureKind != null">AND failure_kind = #{failureKind}</if>
            <if test="userId != null">AND user_id = #{userId}</if>
            <if test="model != null">AND model = #{model}</if>
            <if test="tracePrefix != null">AND trace_id LIKE CONCAT(#{tracePrefix}, '%')</if>
            </script>
            """)
    long countPage(@Param("from") Instant from, @Param("to") Instant to,
                   @Param("kind") String kind, @Param("status") String status,
                   @Param("failureKind") String failureKind,
                   @Param("userId") Long userId, @Param("model") String model,
                   @Param("tracePrefix") String tracePrefix);

    /** 保留期清理：先取一批 trace（分批删，不锁全表）。 */
    @Select("""
            SELECT trace_id FROM ai_runs
            WHERE started_at < #{before}
            ORDER BY started_at
            LIMIT #{limit}
            """)
    List<String> findExpired(@Param("before") Instant before, @Param("limit") int limit);

    /** 僵尸行：一直停在 running 的（进程崩溃 / 线程卡死）。 */
    @Update("""
            UPDATE ai_runs SET status = 'interrupted', error_type = 'stuck', ended_at = #{now}
            WHERE status = 'running' AND started_at < #{before}
            """)
    int markStuck(@Param("before") Instant before, @Param("now") Instant now);

    /**
     * 一行的可变视图（MyBatis 用 setter 填充）。
     *
     * <p>开始与结束共用同一个类：结束那次把该填的都填上，`insertRunning` 里为 null 的列
     * 会被 MyBatis 忽略（值确实是 null）。
     */
    class AiRunRow {
        private Long id;
        private String traceId;
        private Long userId;
        private Long conversationId;
        private String kind;
        private String harness;
        private String model;
        private String promptVersion;
        private String status;
        private String errorType;
        private String failureKind;
        private Instant startedAt;
        private Instant endedAt;
        private Integer queueMs;
        private Integer durationMs;
        private Integer ttftMs;
        private Integer llmRounds;
        private Integer toolCalls;
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer cacheReadTokens;
        private Integer credits;
        private String attrsJson;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getConversationId() { return conversationId; }
        public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getHarness() { return harness; }
        public void setHarness(String harness) { this.harness = harness; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getPromptVersion() { return promptVersion; }
        public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getErrorType() { return errorType; }
        public void setErrorType(String errorType) { this.errorType = errorType; }
        public String getFailureKind() { return failureKind; }
        public void setFailureKind(String failureKind) { this.failureKind = failureKind; }
        public Instant getStartedAt() { return startedAt; }
        public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
        public Instant getEndedAt() { return endedAt; }
        public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
        public Integer getQueueMs() { return queueMs; }
        public void setQueueMs(Integer queueMs) { this.queueMs = queueMs; }
        public Integer getDurationMs() { return durationMs; }
        public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
        public Integer getTtftMs() { return ttftMs; }
        public void setTtftMs(Integer ttftMs) { this.ttftMs = ttftMs; }
        public Integer getLlmRounds() { return llmRounds; }
        public void setLlmRounds(Integer llmRounds) { this.llmRounds = llmRounds; }
        public Integer getToolCalls() { return toolCalls; }
        public void setToolCalls(Integer toolCalls) { this.toolCalls = toolCalls; }
        public Integer getInputTokens() { return inputTokens; }
        public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
        public Integer getOutputTokens() { return outputTokens; }
        public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
        public Integer getCacheReadTokens() { return cacheReadTokens; }
        public void setCacheReadTokens(Integer cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
        public Integer getCredits() { return credits; }
        public void setCredits(Integer credits) { this.credits = credits; }
        public String getAttrsJson() { return attrsJson; }
        public void setAttrsJson(String attrsJson) { this.attrsJson = attrsJson; }

        /** 序列化 attrs；失败只记空（统计不该因为一个字段写不进去而丢整行）。 */
        static String toJson(java.util.Map<String, Object> attrs) {
            if (attrs == null || attrs.isEmpty()) {
                return null;
            }
            try {
                return JSON.writeValueAsString(attrs);
            } catch (JsonProcessingException failure) {
                return null;
            }
        }
    }
}
