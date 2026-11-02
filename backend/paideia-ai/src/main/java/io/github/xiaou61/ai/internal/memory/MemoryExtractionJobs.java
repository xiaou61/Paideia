package io.github.xiaou61.ai.internal.memory;

import io.github.xiaou61.ai.internal.conversation.ConversationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记忆整理任务行的语义（WORK-061 TASK-004）。
 *
 * <p><b>独立成一个 Bean 是为了 {@code @Transactional} 生效</b>：把「抢游标 + 写任务行」
 * 放进同一个事务里，回滚时两者都不发生——不能出现「游标动了但任务没了」（那段切片永远
 * 不会重提）或「任务在但游标没动」（下一次触发会重复整理同一段）。
 *
 * <p>重试语义（见 design「任务表」）：
 * <ul>
 *   <li>每次 {@link #claim} 记一次尝试，上限 {@code maxExtractionAttempts}；</li>
 *   <li>失败按 {@code retryBackoffSeconds × 2^(attempts-1)} 退避（封顶 10 分钟）；</li>
 *   <li>到上限置 {@code failed}，行保留供诊断，由保留清理按时限回收；</li>
 *   <li>{@code running} 且锁定超时的行由中继回收重试（崩溃恢复）。</li>
 * </ul>
 *
 * <p>类与方法为 public 是给 app 模块的集成测试用的（同仓既有做法）；模块边界不受影响——
 * 它仍在 {@code internal} 包下，spring-modulith 依然禁止别的模块引用。
 */
@Service
public class MemoryExtractionJobs {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionJobs.class);

    /** 退避封顶：再久也不该让用户等到下一次中继周期之外好几轮。 */
    private static final long BACKOFF_CAP_SECONDS = 600;

    private final MemoryExtractionJobMapper mapper;
    private final ConversationService conversations;
    private final MemoryProperties properties;

    MemoryExtractionJobs(MemoryExtractionJobMapper mapper,
                         ConversationService conversations,
                         MemoryProperties properties) {
        this.mapper = mapper;
        this.conversations = conversations;
        this.properties = properties;
    }

    /**
     * 抢一段切片并在**同一事务**里记下任务行。
     *
     * @return 任务行；没能抢到切片（轮次不够 / 已过期 / 被并发抢走）时为空
     */
    @Transactional
    public Optional<MemoryExtractionJob> claimAndEnqueue(long userId, long conversationId) {
        int rounds = properties.getRoundsPerExtraction();
        return conversations.claimExtractionSlice(userId, conversationId, rounds)
                .map(slice -> {
                    Instant now = Instant.now();
                    MemoryExtractionJob job = new MemoryExtractionJob();
                    job.setUserId(userId);
                    job.setConversationId(slice.conversationId());
                    job.setFromMessageId(slice.fromMessageId());
                    job.setToMessageId(slice.upToMessageId());
                    job.setCreatedAt(now);
                    job.setNextAttemptAt(now);
                    mapper.insert(job);
                    return job;
                });
    }

    /** 抢占：抢到才返回任务（含已经 +1 的尝试数）。 */
    public Optional<MemoryExtractionJob> claim(long jobId) {
        if (mapper.claim(jobId, Instant.now()) == 0) {
            return Optional.empty();
        }
        return mapper.findById(jobId);
    }

    /** 成功：任务行消失。 */
    public void complete(long jobId) {
        mapper.delete(jobId);
    }

    /** 失败：退避重排或置 failed。{@code attempts} 用抢占后读回的值。 */
    public void fail(long jobId, int attempts, String reason) {
        boolean exhausted = attempts >= properties.getMaxExtractionAttempts();
        Instant next = Instant.now().plusSeconds(exhausted ? 0 : backoffSeconds(attempts));
        mapper.reschedule(jobId, exhausted ? "failed" : "pending", next, trim(reason));
        if (exhausted) {
            log.warn("ai.memory.job_exhausted jobId={} attempts={} reason={}", jobId, attempts, reason);
        } else {
            log.info("ai.memory.job_retry jobId={} attempts={} next={} reason={}",
                    jobId, attempts, next, reason);
        }
    }

    public List<MemoryExtractionJob> due(int limit) {
        return mapper.due(Instant.now(), limit);
    }

    /** 回收崩溃留下的 running 行；返回回收条数。 */
    public int reclaimStuck() {
        return mapper.reclaimStuck(
                Instant.now().minusSeconds(properties.getRunningTimeoutSeconds()));
    }

    public int deleteByUser(long userId) {
        return mapper.deleteByUser(userId);
    }

    /** failed 行的保留回收（由 {@code MemoryRetentionPurger} 按小时批量调）。 */
    public int dropFailed(Instant before) {
        return mapper.dropFailed(before);
    }

    private long backoffSeconds(int attempts) {
        long seconds = properties.getRetryBackoffSeconds() << Math.max(0, attempts - 1);
        return Math.min(seconds, BACKOFF_CAP_SECONDS);
    }

    /** last_error 列宽 500：截断而不是让整条 UPDATE 失败——诊断信息不该反过来变成新的故障。 */
    private static String trim(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}