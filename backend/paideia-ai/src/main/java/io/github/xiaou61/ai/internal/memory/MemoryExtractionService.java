package io.github.xiaou61.ai.internal.memory;

import io.github.xiaou61.ai.MemoryCandidate;
import io.github.xiaou61.ai.MemoryExtractor;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.conversation.ConversationService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.settings.AiSettingsStore;
import io.github.xiaou61.platform.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 自动整理记忆的编排（WORK-013；持久重试见 WORK-061）。
 *
 * <p><b>两步，刻意分开</b>：
 * <ol>
 *   <li>{@link #scheduleIfDue}：**同步、极轻**。抢游标并在**同一个事务**里记一行任务
 *       （{@code MemoryExtractionJobs.claimAndEnqueue}），随后把任务交给线程池。</li>
 *   <li>{@link #run}：**异步、重**。发模型调用、过账、写记忆；失败按退避重排，超上限置 failed。</li>
 * </ol>
 * 分开的理由是用户体验：调用方（{@code AssistantConversationController}）在**回答已经发给用户之后**
 * 调第一步，第二步跑在自己的线程里，用户永远不等它。
 *
 * <p><b>三个"绝不"</b>：绝不因为整理失败让那一轮对话失败；绝不在余额不足时发起上游请求；
 * 绝不把"没返回到可用结果"当成"这几轮没什么可记的"（后者是合法结果，前者是故障）。
 *
 * <p><b>重试的凭据是任务行，不是游标</b>（WORK-061 关掉了原设计 §10 登记的"失败不重跑"缺口）：
 * 游标在入队事务里就推到位，失败留下的任务行由 {@link #dispatch} 与
 * {@code MemoryExtractionRelay} 共同驱动。计费口径不变——每次尝试独立
 * 「预留 → 按用量结算或全额退款」，不双扣。
 */
@Service
public class MemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);

    private final MemoryProperties properties;
    private final AiSettingsStore settings;
    private final ConversationService conversations;
    private final MemoryStore memories;
    private final MemoryCandidateService candidates;
    private final MemoryExtractor extractor;
    private final CredentialService credentials;
    private final CreditService credits;
    private final TaskExecutor executor;
    private final io.github.xiaou61.ai.RunRecorder recorder;
    private final MemoryExtractionJobs jobs;

    MemoryExtractionService(MemoryProperties properties,
                            AiSettingsStore settings,
                            ConversationService conversations,
                            MemoryStore memories,
                            MemoryCandidateService candidates,
                            MemoryExtractor extractor,
                            CredentialService credentials,
                            CreditService credits,
                            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                            io.github.xiaou61.ai.RunRecorder recorder,
                            MemoryExtractionJobs jobs) {
        this.properties = properties;
        this.settings = settings;
        this.conversations = conversations;
        this.memories = memories;
        this.candidates = candidates;
        this.extractor = extractor;
        this.credentials = credentials;
        this.credits = credits;
        this.executor = executor;
        this.recorder = recorder;
        this.jobs = jobs;
    }

    /**
     * 一轮结束之后调一次（**必须在回答已经发出之后**）。
     *
     * <p>不满足条件时什么都不做，也不抛异常：它是"顺手看一眼"，任何失败都不该影响那一轮对话。
     *
     * <p><b>入队与抢游标同一个事务</b>（WORK-061）：回滚时两者都不发生，不会出现
     * "游标动了但任务没了"（那段切片永远不再重提）或"任务在但游标没动"（下次触发重复整理）。
     */
    public void scheduleIfDue(long userId, long conversationId) {
        if (properties.getRoundsPerExtraction() <= 0) {
            return;
        }
        if (!settings.get(userId).memoryEnabled()) {
            return;
        }
        jobs.claimAndEnqueue(userId, conversationId).ifPresent(this::dispatch);
    }

    /**
     * 派发一条任务到线程池（即时路径与中继共用）。
     *
     * <p>先抢占再入队：抢不到说明别人已经在跑（或刚跑完），直接放手——抢占是条件 UPDATE，
     * 两条路撞上只有一个能跑。
     *
     * <p><b>线程池满不再丢任务</b>（与旧行为的分水岭）：记一次失败、按退避重排，
     * 中继会在后面的周期里再拾。调用点仍在 {@code emitter.complete()} 之后，异常绝不外抛——
     * 那会把一次成功的回答变成错误事件。
     */
    void dispatch(MemoryExtractionJob job) {
        jobs.claim(job.getId()).ifPresent(claimed -> {
            try {
                executor.execute(() -> run(claimed));
            } catch (RejectedExecutionException busy) {
                jobs.fail(claimed.getId(), claimed.getAttempts(), "executor_busy");
            }
        });
    }

    /**
     * 真正干活的那一段：发调用、过账、写记忆。**跑在回答之后的独立线程里**。
     *
     * <p>用量为空时**全额退回**预留（与对话那条同一口径）：没有依据就不该扣。
     *
     * <p>收尾分三种：成功、无意义（记忆被关 / 没凭据 / 会话没了 / 欠费）收掉任务行；
     * 失败按尝试数退避重排或置 failed——"没记成"从此有据可查，不再静默消失。
     */
    private void run(MemoryExtractionJob job) {
        long userId = job.getUserId();
        try {
            // 期间可能被关掉了（用户在别的标签页关了记忆）——这段整理没有意义，收掉
            if (!settings.get(userId).memoryEnabled()) {
                jobs.complete(job.getId());
                return;
            }
            Optional<ModelAccess> access = credentials.access(userId);
            if (access.isEmpty()) {
                // 没凭据不是"下次就好"的瞬时故障，重试也是白跑；收掉并留一行日志
                jobs.complete(job.getId());
                log.info("ai.memory.job_dropped_no_credential userId={} conversationId={}",
                        userId, job.getConversationId());
                return;
            }
            Optional<ConversationService.ExtractionSlice> materialized =
                    conversations.materializeSlice(userId, job.getConversationId(),
                            job.getFromMessageId(), job.getToMessageId());
            if (materialized.isEmpty()) {
                jobs.complete(job.getId());
                log.info("ai.memory.job_dropped_conversation_gone userId={} conversationId={}",
                        userId, job.getConversationId());
                return;
            }
            ConversationService.ExtractionSlice slice = materialized.get();

            List<MemoryExtractor.MemoryLine> pool = memories.dedupPool(userId).stream()
                    .limit(Math.max(properties.getDedupPoolSize(), 0))
                    .map(item -> new MemoryExtractor.MemoryLine(
                            item.source() == MemoryStore.MemoryItem.Source.STRUCTURED ? "structured" : "text",
                            item.id(), item.text()))
                    .toList();

            long reserved;
            try {
                reserved = credits.reserve(userId, credits.estimate());
            } catch (BizException insufficient) {
                // 余额不足：收掉任务（重试只会对着空钱包再撞一次）。这一轮的回答早就发出去了，
                // 不该因为整理而报错给用户——静默跳过与旧口径一致，只是留痕更具体了
                jobs.complete(job.getId());
                log.info("ai.memory.skip_insufficient_credits userId={} conversationId={}",
                        userId, slice.conversationId());
                return;
            }

            TokenUsage usage = null;
            var run = recorder.begin(io.github.xiaou61.ai.RunSpec
                    .of(io.github.xiaou61.ai.RunKind.MEMORY_EXTRACT, userId)
                    .withConversation(slice.conversationId())
                    .withModel(access.get().model())
                    .withPromptVersion(MemoryExtractionPrompt.VERSION));
            try {
                MemoryExtractor.MemoryExtraction result = extractor.extract(
                        new MemoryExtractor.MemoryExtractionRequest(slice.turns(), pool), access.get());
                usage = result.usage();
                int affected = apply(userId, slice, result.candidates());
                int charged = credits.settleMemoryExtraction(userId, reserved, access.get().model(),
                        usage == null ? null : usage.inputTokens(),
                        usage == null ? null : usage.outputTokens(),
                        slice.upToMessageId());
                run.tokens(usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(),
                    usage == null ? 0 : usage.cacheReadTokens());
                run.addCredits(charged);
                jobs.complete(job.getId());
                run.finish(io.github.xiaou61.ai.RunOutcome.OK, null);
                log.info("ai.memory.extracted userId={} conversationId={} candidates={} affected={} attempts={}",
                        userId, slice.conversationId(), result.candidates().size(), affected,
                        job.getAttempts());
            } catch (RuntimeException failure) {
                settleOrRefund(userId, reserved, usage, slice.upToMessageId(), access.get());
                jobs.fail(job.getId(), job.getAttempts(), failure.getMessage());
                run.finish(io.github.xiaou61.ai.RunOutcome.FAILED, "extraction_failed");
                log.warn("ai.memory.extraction_failed userId={} conversationId={} attempt={} reason={}",
                        userId, slice.conversationId(), job.getAttempts(), failure.getMessage());
            } finally {
                run.close();
            }
        } catch (RuntimeException unexpected) {
            // 到这里说明连准备阶段就炸了（读设置/读记忆等）。不能让它冒到线程池外：那会只剩一个
            // 没人看的堆栈，用户与我们都不知道"记忆为什么不长了"。记一次失败、退避重试到上限为止
            // ——不会再像以前那样静默消失（WORK-061）。
            jobs.fail(job.getId(), job.getAttempts(), unexpected.getMessage());
            log.warn("ai.memory.extraction_crashed userId={} conversationId={} attempt={}",
                    userId, job.getConversationId(), job.getAttempts(), unexpected);
        }
    }

    /**
     * 把一次判定的候选分流到三个去处（WORK-050 REQ-006）。
     *
     * <p>三路各有自己的语义：`PROPOSE` 进候选表等用户点；命中敏感信息的**只记一条"未记录"**
     * （正文不落库）；其余交给 {@link MemoryStore#applyAuto} 直接生效。
     *
     * <p>顺序与"整批一个事务"的旧语义并不冲突：`applyAuto` 仍然是一个事务，
     * 候选与过滤记录各自是独立的小事务——它们失败不该让已经生效的记忆回滚。
     */
    private int apply(long userId, ConversationService.ExtractionSlice slice,
                      List<MemoryCandidate> candidates) {
        List<MemoryCandidate> direct = new ArrayList<>();
        int proposed = 0;
        int filtered = 0;
        for (MemoryCandidate candidate : candidates) {
            // 敏感信息先判：`PROPOSE` 的正文会写进 ai_memory_candidates，而那张表的正文
            // 会被 `GET /memory/candidates` 渲染到面板上。原先这一支排在下面又 `continue`，
            // 带口令/令牌的提议就绕过过滤原样落库了（2026-11-02 审查抓到）。
            if (candidate.content() != null && SensitiveContent.find(candidate.content()).isPresent()) {
                this.candidates.recordFiltered(userId, candidate.store(), candidate.content(),
                        slice.conversationId(), slice.upToMessageId());
                filtered++;
                continue;
            }
            if (candidate.action() == MemoryCandidate.Action.PROPOSE) {
                this.candidates.propose(userId, candidate.store(), candidate.kind(), candidate.refId(),
                        candidate.content(), slice.conversationId(), slice.upToMessageId(),
                        candidate.reason());
                proposed++;
                continue;
            }
            direct.add(candidate);
        }
        int affected = memories.applyAuto(userId, slice.conversationId(), slice.upToMessageId(), direct);
        if (proposed > 0 || filtered > 0) {
            log.info("ai.memory.split userId={} proposed={} filtered={} applied={}",
                    userId, proposed, filtered, affected);
        }
        return affected;
    }

    /** 失败也要过账：拿到了用量就按用量扣，没拿到就全额退回（与对话那条同一条纪律）。 */
    private void settleOrRefund(long userId, long reserved, TokenUsage usage, long refMessageId, ModelAccess access) {
        if (usage == null) {
            credits.refund(userId, reserved, refMessageId, "记忆整理失败");
            return;
        }
        credits.settleMemoryExtraction(userId, reserved, access.model(),
                usage.inputTokens(), usage.outputTokens(), refMessageId);
    }
}
