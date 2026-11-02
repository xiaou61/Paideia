package io.github.xiaou61.ai.internal.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.EntrySummarizer;
import io.github.xiaou61.ai.GeneratedSummary;
import io.github.xiaou61.ai.SummaryCard;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.support.UserRateLimiter;
import io.github.xiaou61.ai.internal.provider.SummaryPrompt;
import io.github.xiaou61.knowledge.EntryContent;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 精简的读取与生成。
 *
 * <p>编排顺序：**取正文 → 找可见的缓存 →（没有才）调模型 → 落库**。端口只负责调模型，
 * 凭据只在调用前一刻取，缓存与可见性都在这里。
 *
 * <p>两条不可动摇的规则：
 * <ol>
 *   <li>命中缓存时**不调模型**，且响应里的用量是 {@code null}（"本次没有产生调用"），
 *       而不是 0（那会被读成"只花了 0 个 token"）。</li>
 *   <li>失败与空结果**绝不落库**：校验器已经把空的挡掉，这里负责不写失败结果。</li>
 * </ol>
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final KnowledgeApi knowledge;
    private final CredentialService credentials;
    private final EntrySummarizer summarizer;
    private final EntrySummaryMapper mapper;
    private final UserRateLimiter limiter;
    /** 限流口径来自配置（每分钟／每天），所以这里也要拿到它。 */
    private final io.github.xiaou61.ai.internal.credential.AiProperties properties;
    /** 卡片与库里的 JSON 互转；用默认配置即可（不依赖容器里的 ObjectMapper）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CreditService credits;
    private final io.github.xiaou61.ai.RunRecorder recorder;

    SummaryService(KnowledgeApi knowledge,
                   CredentialService credentials,
                   EntrySummarizer summarizer,
                   EntrySummaryMapper mapper,
                   UserRateLimiter limiter,
                   io.github.xiaou61.ai.internal.credential.AiProperties properties,
                   CreditService credits,
                   io.github.xiaou61.ai.RunRecorder recorder) {
        this.knowledge = knowledge;
        this.credentials = credentials;
        this.summarizer = summarizer;
        this.mapper = mapper;
        this.limiter = limiter;
        this.properties = properties;
        this.credits = credits;
        this.recorder = recorder;
    }

    /** 响应形状。`usage` 命中缓存时为 null；`source` 说明这份结果是谁生成的。 */
    public record SummaryView(SummaryCard card,
                              boolean cached,
                              String source,
                              String visibility,
                              TokenUsage usage,
                              boolean truncated,
                              Instant generatedAt) {
    }

    /** 只读：有可见结果就返回，没有返回空（**不触发生成**，也不计入限流）。 */
    public Optional<SummaryView> read(long entryId, long userId) {
        return knowledge.findPublishedContent(entryId)
                .flatMap(content -> readCached(entryId, content.body(), userId));
    }

    /** 生成或命中。 */
    public SummaryView generate(long entryId, long userId, SummaryVisibility visibility) {
        EntryContent content = knowledge.findPublishedContent(entryId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "条目不存在或尚未发布"));

        Optional<SummaryView> cached = readCached(entryId, content.body(), userId);
        if (cached.isPresent()) {
            log.info("ai.summary.cached entryId={} userId={}", entryId, userId);
            return cached.get();
        }

        limiter.checkAndCount(userId, "生成精简", properties.getPerMinuteLimit(), properties.getPerDayLimit());
        var access = credentials.access(userId)
                .orElseThrow(() -> new BizException(ErrorCode.CONFLICT,
                        "平台尚未配置 AI 服务，请联系管理员"));
        // 预扣在**调模型之前**同步做完：余额不足时一个上游请求都不会发出去（REQ-006）。
        // 放在限流之后：被限流的那次本来就不该占用户的额度。
        long reserved = credits.reserve(userId, credits.estimate());
        GeneratedSummary generated;
        var run = recorder.begin(io.github.xiaou61.ai.RunSpec
                .of(io.github.xiaou61.ai.RunKind.ENTRY_SUMMARY, userId)
                .withModel(access.model())
                .withPromptVersion(SummaryPrompt.VERSION));
        try {
            run.attribute("reqChars", content.body() == null ? 0 : content.body().length());
            generated = summarizer.summarize(content.title(), content.body(), access);
            TokenUsage usage = generated.usage();
            int charged;
            if (usage == null) {
                // 上游没给用量：没有依据就不扣（宁可漏扣，审计页上看得出来）
                credits.refund(userId, reserved, null, "上游未返回用量");
                charged = 0;
            } else {
                charged = credits.settle(userId, reserved, access.model(),
                        usage.inputTokens(), usage.outputTokens(), null);
                run.tokens(usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens());
            }
            run.addCredits(charged);
            run.finish(io.github.xiaou61.ai.RunOutcome.OK, null);
        } catch (RuntimeException failure) {
            // 调用没成功 → 预留全额退回。**失败不落库不够**：钱那一步得显式退。
            credits.refund(userId, reserved, null, "上游失败或返回不可用结果");
            run.finish(io.github.xiaou61.ai.RunOutcome.FAILED, "summary_failed");
            throw failure;
        } finally {
            run.close();
        }

        EntrySummary row = new EntrySummary();
        row.setEntryId(entryId);
        row.setContentHash(SummaryFingerprint.of(content.body()));
        row.setModel(access.model());
        row.setPromptVersion(SummaryPrompt.VERSION);
        row.setOwnerUserId(userId);
        row.setVisibility(visibility.wireName());
        row.setCardJson(toJson(generated.card()));

        boolean stored = true;
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException exception) {
            // 并发下同一指纹被同时生成：唯一键兜住，回读已有的那份，不把 500 抛给用户
            stored = false;
        }
        log.info("ai.summary.generated entryId={} userId={} stored={} truncated={}",
                entryId, userId, stored, generated.truncated());

        if (!stored) {
            return readCached(entryId, content.body(), userId).orElseGet(
                    () -> view(generated.card(), false, "mine", visibility.wireName(),
                            generated.usage(), generated.truncated(), Instant.now()));
        }
        return view(generated.card(), false, "mine", visibility.wireName(),
                generated.usage(), generated.truncated(), Instant.now());
    }

    private Optional<SummaryView> readCached(long entryId, String body, long userId) {
        return mapper.findVisible(entryId, SummaryFingerprint.of(body), SummaryPrompt.VERSION, userId)
                .map(it -> view(fromJson(it.getCardJson()), true,
                        it.getOwnerUserId() != null && it.getOwnerUserId() == userId ? "mine" : "others",
                        it.getVisibility(), null, false, it.getCreatedAt()));
    }

    private static SummaryView view(SummaryCard card, boolean cached, String source, String visibility,
                                    TokenUsage usage, boolean truncated, Instant generatedAt) {
        return new SummaryView(card, cached, source, visibility, usage, truncated, generatedAt);
    }

    private String toJson(SummaryCard card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化卡片失败", exception);
        }
    }

    /**
     * 按 JSON 树显式构造卡片，而不是 {@code readValue(json, SummaryCard.class)}。
     *
     * <p>原因：record 的反序列化要靠 Jackson 的参数名模块，而这里用的是**自建的** ObjectMapper
     * （容器没暴露该 Bean）。按树构造不依赖任何模块，顺带对库里的数据再做一次形状检查——
     * 读出来的字段缺失或为空时得到的就是一张空卡片，调用方能立刻看出不对。
     */
    private SummaryCard fromJson(String json) {
        try {
            var root = objectMapper.readTree(json);
            List<SummaryCard.SummarySection> sections = new ArrayList<>();
            for (var node : root.path("sections")) {
                sections.add(new SummaryCard.SummarySection(
                        node.path("heading").asText(""), node.path("body").asText("")));
            }
            return new SummaryCard(root.path("headline").asText(""), sections);
        } catch (JsonProcessingException exception) {
            // 落库的都是校验过的卡片，读不出来只可能是数据被外部改过
            throw new IllegalStateException("已保存的卡片无法解析", exception);
        }
    }
}
