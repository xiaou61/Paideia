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
 * 自动整理记忆的编排（WORK-013）。
 *
 * <p><b>两步，刻意分开</b>：
 * <ol>
 *   <li>{@link #scheduleIfDue}：**同步、极轻**。问会话服务"该不该整理"——它顺手就把这次资格抢下来
 *       （条件 UPDATE 抢游标，见 {@code ConversationService.claimExtractionSlice}）。</li>
 *   <li>{@link #run}：**异步、重**。发模型调用、过账、写记忆。失败只记日志。</li>
 * </ol>
 * 分开的理由是用户体验：调用方（{@code AssistantConversationController}）在**回答已经发给用户之后**
 * 调第一步，第二步跑在自己的线程里，用户永远不等它。
 *
 * <p><b>三个"绝不"</b>：绝不因为整理失败让那一轮对话失败；绝不在余额不足时发起上游请求；
 * 绝不把"没返回到可用结果"当成"这几轮没什么可记的"（后者是合法结果，前者是故障）。
 *
 * <p>已知缺口（设计 §10 已登记）：抢到即推进游标 ⇒ **整理失败不重跑**。重跑要引入重试状态机与
 * "失败要不要计费"的一串问题，而漏一条记忆的代价远小于那套复杂度。
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

    MemoryExtractionService(MemoryProperties properties,
                            AiSettingsStore settings,
                            ConversationService conversations,
                            MemoryStore memories,
                            MemoryCandidateService candidates,
                            MemoryExtractor extractor,
                            CredentialService credentials,
                            CreditService credits,
                            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                            io.github.xiaou61.ai.RunRecorder recorder) {
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
    }

    /**
     * 一轮结束之后调一次（**必须在回答已经发出之后**）。
     *
     * <p>不满足条件时什么都不做，也不抛异常：它是"顺手看一眼"，任何失败都不该影响那一轮对话。
     *
     * <p><b>队列满时直接放弃这次整理</b>（2026-10-19 起执行器队列有界）：调用点就在
     * {@code emitter.complete()} 之后，异常会窜回"已经结束的那一轮"，把一次成功的回答变成
     * 错误事件。少整理一次记忆没有代价——游标没推进，下次到点会再来。
     */
    public void scheduleIfDue(long userId, long conversationId) {
        int rounds = properties.getRoundsPerExtraction();
        if (rounds <= 0) {
            return;
        }
        if (!settings.get(userId).memoryEnabled()) {
            return;
        }
        conversations.claimExtractionSlice(userId, conversationId, rounds)
                .ifPresent(slice -> {
                    try {
                        executor.execute(() -> run(userId, slice));
                    } catch (RejectedExecutionException busy) {
                        log.info("ai.memory.skip_pool_full userId={} conversationId={}",
                                userId, conversationId);
                    }
                });
    }

    /**
     * 真正干活的那一段：发调用、过账、写记忆。**跑在回答之后的独立线程里**。
     *
     * <p>用量为空时**全额退回**预留（与对话那条同一口径）：没有依据就不该扣。
     */
    private void run(long userId, ConversationService.ExtractionSlice slice) {
        try {
            // 期间可能被关掉了（用户在别的标签页关了记忆）——那就白跑一趟，也不能写
            if (!settings.get(userId).memoryEnabled()) {
                return;
            }
            Optional<ModelAccess> access = credentials.access(userId);
            if (access.isEmpty()) {
                return;
            }

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
                // 余额不足**静默跳过**：这一轮的回答早就发出去了，不该因为整理而报错给用户
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
                run.finish(io.github.xiaou61.ai.RunOutcome.OK, null);
                log.info("ai.memory.extracted userId={} conversationId={} candidates={} affected={}",
                        userId, slice.conversationId(), result.candidates().size(), affected);
            } catch (RuntimeException failure) {
                settleOrRefund(userId, reserved, usage, slice.upToMessageId(), access.get());
                run.finish(io.github.xiaou61.ai.RunOutcome.FAILED, "extraction_failed");
                log.warn("ai.memory.extraction_failed userId={} conversationId={} reason={}",
                        userId, slice.conversationId(), failure.getMessage());
            } finally {
                run.close();
            }
        } catch (RuntimeException unexpected) {
            // 到这里说明连准备阶段就炸了（读设置/读记忆等）。不能让它冒到线程池外：那会只剩一个
            // 没人看的堆栈，用户与我们都不知道"记忆为什么不长了"。
            log.warn("ai.memory.extraction_crashed userId={} conversationId={}",
                    userId, slice.conversationId(), unexpected);
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
            if (candidate.action() == MemoryCandidate.Action.PROPOSE) {
                this.candidates.propose(userId, candidate.store(), candidate.kind(), candidate.refId(),
                        candidate.content(), slice.conversationId(), slice.upToMessageId(),
                        candidate.reason());
                proposed++;
                continue;
            }
            if (candidate.content() != null && SensitiveContent.find(candidate.content()).isPresent()) {
                this.candidates.recordFiltered(userId, candidate.store(), candidate.content(),
                        slice.conversationId(), slice.upToMessageId());
                filtered++;
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
