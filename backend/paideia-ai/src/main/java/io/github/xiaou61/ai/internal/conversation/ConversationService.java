package io.github.xiaou61.ai.internal.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.ConversationTurn;
import io.github.xiaou61.ai.Harness;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.memory.MemoryStore;
import io.github.xiaou61.ai.internal.settings.AiSettingsStore;
import io.github.xiaou61.ai.internal.support.UserRateLimiter;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话的编排：归属校验、上下文装配、写入与裁剪。
 *
 * <p>端口（{@link io.github.xiaou61.ai.AssistantConversation}）只负责"调模型"，这里负责"围绕它的一切"。
 * 分开的好处是模型可以被桩替换，而本类的行为（归属、上限、过期、上下文顺序）能脱离网络单测。
 *
 * <p>三条纪律：
 * <ul>
 *   <li><b>归属一律经 SQL 校验</b>：拿不到（不存在／不是本人的／已过期）就当作"会话不存在"，
 *       对外返回同一个错误，不区分原因——区分开就等于确认"某个会话存在"。</li>
 *   <li><b>用户消息在调模型之前写</b>：模型失败时用户那句话不该丢；助手消息只在成功后才写，
 *       失败轮次不落库（也就不会进后续上下文）。</li>
 *   <li><b>滑动续期与裁剪都在同一处</b>：否则"什么时候会过期""历史为什么变短"会散落成多处。</li>
 * </ul>
 */
@Service
public class ConversationService {

    private final ConversationMapper conversations;
    private final ConversationMessageMapper messages;
    private final ConversationProperties properties;
    private final CredentialService credentials;
    private final KnowledgeApi knowledge;
    private final MemoryStore memories;
    private final io.github.xiaou61.ai.internal.rag.KnowledgeRagService rag;
    /** 解析落库的用量 JSON 用。自带一份即可，不必依赖容器里的 ObjectMapper。 */
    private static final ObjectMapper USAGE_JSON = new ObjectMapper();

    private final UserRateLimiter limiter;
    private final CreditService credits;
    /** 两个开关与总闸（WORK-013）：装配上下文时按它短路。 */
    private final AiSettingsStore settingsStore;

    ConversationService(ConversationMapper conversations,
                        ConversationMessageMapper messages,
                        ConversationProperties properties,
                        CredentialService credentials,
                        KnowledgeApi knowledge,
                        MemoryStore memories,
                        io.github.xiaou61.ai.internal.rag.KnowledgeRagService rag,
                        UserRateLimiter limiter,
                        CreditService credits,
                        AiSettingsStore settingsStore) {
        this.conversations = conversations;
        this.messages = messages;
        this.properties = properties;
        this.credentials = credentials;
        this.knowledge = knowledge;
        this.memories = memories;
        this.rag = rag;
        this.limiter = limiter;
        this.credits = credits;
        this.settingsStore = settingsStore;
    }

    /** 会话列表项。**不含消息内容**：列表只用来选，拉开某一条才取内容。 */
    public record ConversationView(long id, Long entryId, Instant updatedAt) {
    }

    /**
     * 自动整理要用的一段增量（WORK-013；WORK-061 增加起点）。
     *
     * <p>它是**会话内部**（游标、消息表）与记忆那边之间的唯一交接物：记忆模块拿到的只有"哪几轮对话"
     * 与"整理到哪条为止"，不必也不该碰 {@code ConversationMapper} 这些内部件。
     *
     * @param fromMessageId 切片起点（不含）：抢锁时读到的旧游标；重试按它重读同一段
     * @param upToMessageId 本次整理覆盖到的最后一条消息 id（也用它当流水的 ref，便于对账）
     */
    public record ExtractionSlice(long conversationId,
                                  long fromMessageId,
                                  long upToMessageId,
                                  List<ConversationTurn> turns) {
    }

    /**
     * 一次整理最多吃多少条消息。攒太久（或一口气聊很多轮）时只取最早这一批，剩下的下次接着整理。
     */
    private static final int MAX_EXTRACTION_MESSAGES = 60;

    /**
     * 页面场景的长度兜底（WORK-035 T-15）。
     *
     * <p>前端按 `PAGE_CONTEXT_LIMITS`（草稿 2000 + 结果 800）截过，这里留 1.5 倍余量。
     * **存在这一层的唯一理由是防绕过**：有人直接调接口塞 100 KB 进来，提示词会被撑爆。
     */
    private static final int MAX_PAGE_CONTEXT_CHARS = 4000;

    /**
     * 该不该自动整理？该就**当场抢下来**并返回这一段（WORK-013）。
     *
     * <p>三步都在这里，因为"攒了几轮"与"游标"都是会话自己的事：
     * <ol>
     *   <li>数游标之后有多少条**助手**消息——等于"攒了几轮"（失败轮次不落助手消息，不该算进去）；</li>
     *   <li>取这一段增量（带上限）；</li>
     *   <li>条件 UPDATE 抢锁：只有把游标从我读到的那个值改成新值的那一个线程才算抢到。
     *       抢到即推进游标——所以它天然单飞，也不需要分布式锁。</li>
     * </ol>
     *
     * <p>**抢锁本身不加事务**（它是单条原子语句）；调用方（{@code MemoryExtractionJobs}）会把
     * "抢锁 + 写任务行"包进一个**小事务**，回滚时两者都不发生。模型调用与写记忆仍在事务之外，
     * 失败也不会回滚游标——**重试的凭据是任务行，不再是游标**（WORK-061 关掉了原来的已知缺口）。
     */
    public Optional<ExtractionSlice> claimExtractionSlice(long userId, long conversationId, int rounds) {
        Instant now = Instant.now();
        Optional<Conversation> found = conversations.findActiveByIdAndUser(conversationId, userId, now);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Long cursor = found.get().getLastMemoryExtractMessageId();
        if (messages.countAssistantAfter(conversationId, cursor) < rounds) {
            return Optional.empty();
        }
        List<ConversationMessage> increment =
                messages.selectAfter(conversationId, cursor, MAX_EXTRACTION_MESSAGES);
        if (increment.isEmpty()) {
            return Optional.empty();
        }
        long upTo = increment.get(increment.size() - 1).getId();
        if (conversations.claimExtraction(conversationId, userId, upTo, cursor, now) == 0) {
            return Optional.empty();
        }
        return Optional.of(new ExtractionSlice(conversationId, cursor == null ? 0L : cursor, upTo,
                increment.stream()
                .map(it -> new ConversationTurn(ConversationTurn.Role.of(it.getRole()), it.getContent()))
                .toList()));
    }

    /**
     * 按任务行的边界重读一段切片（WORK-061 的重试路径）。
     *
     * <p>与抢锁那一刻读到的必须是同一段：切片上限固定，取 {@code from} 之后的同一批再按
     * {@code <= to} 截断，结果一致。**只读，不推进游标**——游标在入队那次事务里就推到位了。
     *
     * @return 会话已过期/不存在，或这一段读不出任何消息时为空（调用方据此收掉任务行）
     */
    public Optional<ExtractionSlice> materializeSlice(long userId, long conversationId,
                                                      long fromMessageId, long toMessageId) {
        Instant now = Instant.now();
        if (conversations.findActiveByIdAndUser(conversationId, userId, now).isEmpty()) {
            return Optional.empty();
        }
        List<ConversationTurn> turns =
                messages.selectAfter(conversationId, fromMessageId, MAX_EXTRACTION_MESSAGES).stream()
                        .filter(it -> it.getId() <= toMessageId)
                        .map(it -> new ConversationTurn(ConversationTurn.Role.of(it.getRole()), it.getContent()))
                        .toList();
        if (turns.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ExtractionSlice(conversationId, fromMessageId, toMessageId, turns));
    }

    /**
     * 一轮对话开始前所需的一切。
     *
     * @param conversationId 已校验归属的会话 id
     * @param history        进上下文的历史（旧 → 新，已按窗口裁剪）
     * @param userMessage    本轮用户输入
     * @param entryTitle     可选条目上下文
     * @param entryBody      可选条目上下文
     * @param memorySnippets 召回的记忆（本人私有，每条一行）
     * @param access         凭据；调用方据此给出"尚未配置"的提示
     * @param reservedCredits 已从余额里预扣的积分（0 表示未计量）；结算时调用方要把它带回来
     */
    public record TurnPreparation(long conversationId,
                                  List<ConversationTurn> history,
                                  String userMessage,
                                  String entryTitle,
                                  String entryBody,
                                  /** 本人这一站的学习记录摘要；只有 TUTOR 会用它（WORK-020）。 */
                                  String learningSummary,
                                  List<String> memorySnippets,
                                  ModelAccess access,
                                  long reservedCredits,
                                  Harness harness,
                                  /** 页面场景（WORK-035 T-15）。只进提示词，不落库。 */
                                  String pageContext,
                                  /** RAG 检索上下文（WORK-072）。只进提示词，不落库。 */
                                  String knowledgeContext) {

        /** 不带页面场景/RAG 的形态：既有调用点与测试用它。 */
        public TurnPreparation(long conversationId, List<ConversationTurn> history, String userMessage,
                               String entryTitle, String entryBody, String learningSummary,
                               List<String> memorySnippets, ModelAccess access, long reservedCredits,
                               Harness harness) {
            this(conversationId, history, userMessage, entryTitle, entryBody, learningSummary,
                    memorySnippets, access, reservedCredits, harness, null, null);
        }
    }

    public long create(long userId, Long entryId) {
        return create(userId, entryId, Harness.ASSISTANT);
    }

    /**
     * 建一段**带公共行为**的会话（WORK-010）。
     *
     * <p>harness 存进会话里，恢复时按它选行为——否则用户从通用入口继续一段面试对话会中途换人格。
     * 通用助手也显式写 `assistant`（不写 NULL）：否则"NULL 是什么行为"要靠读代码才知道。
     */
    public long create(long userId, Long entryId, Harness harness) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setEntryId(entryId);
        conversation.setExpiresAt(Instant.now().plus(properties.getTtl()));
        conversation.setHarness((harness == null ? Harness.ASSISTANT : harness).key());
        conversations.insert(conversation);
        return conversation.getId();
    }

    public List<ConversationView> list(long userId) {
        return conversations.listActiveByUser(userId, Instant.now()).stream()
                .map(it -> new ConversationView(it.getId(), it.getEntryId(), it.getUpdatedAt()))
                .toList();
    }

    /**
     * 校验归属、写入用户消息、装配上下文。
     *
     * <p>凭据缺失不算"会话不存在"，所以单独抛一条更明确的错误：界面要据此提示"平台还没配 AI 服务"，
     * 而不是让人以为会话丢了。**WORK-009 起凭据由平台持有**，所以措辞从"填你自己的 Key"改成
     * "联系管理员"——用户已经没有可填的地方了。
     *
     * <p><b>本方法刻意不加 `@Transactional`</b>（2026-10-17 修）：加了的话，`credits.reserve()` 在
     * "已写入用户消息"之后抛 402，会把那句话一起回滚——而界面早已乐观地画出了它，于是**刷新即丢**。
     * 用户确实说过那句话，扣费失败不该抹掉输入。代价是"写消息 + 滑动续期"不再原子：续期失败只会
     * 让会话按原过期时间到期，不会丢数据。
     *
     * <p>由此得到一条不变量：**`credits.reserve()` 必须是最后一个可能抛异常的动作**，否则预留会漏在
     * 账上。所以下面所有读操作（历史、条目、记忆召回）都排在它前面。
     */
    public TurnPreparation prepare(long userId, long conversationId, String prompt) {
        return prepare(userId, conversationId, prompt, null);
    }

    /**
     * 带页面场景的版本（WORK-035 T-15）。
     *
     * <p>`pageContext` 是**前端已截断的一段文本**（SQL 工作台的草稿与最近报错）。
     * 它只进提示词，不落库——会话历史里不该出现"某次页面上写了什么"。
     *
     * <p>**服务端不再截断**：前端按 `PAGE_CONTEXT_LIMITS` 截过了，服务端再截一次会让
     * "到底哪边生效"变成两处都要看的事。这里只做长度上限的兜底（防有人绕过前端直接调接口）。
     */
    public TurnPreparation prepare(long userId, long conversationId, String prompt, String pageContext) {
        String trimmed = prompt == null ? "" : prompt.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "消息内容不能为空");
        }
        Instant now = Instant.now();
        Conversation conversation = conversations.findActiveByIdAndUser(conversationId, userId, now)
                .orElseThrow(ConversationService::conversationNotFound);

        Optional<ModelAccess> access = credentials.access(userId);
        if (access.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "平台尚未配置 AI 服务，请联系管理员");
        }
        // 限流放在凭据校验**之后**：没配凭据的请求根本不会打到上游，不该占用户的额度
        // （与"命中缓存不计入"同一个口径）
        limiter.checkAndCount(userId, "发送消息",
                properties.getPerMinuteLimit(), properties.getPerDayLimit());

        // 先取历史、再写本轮消息：history() 取的是"最近 N 条"，写在前面的话这条用户消息
        // 会同时出现在 history 里和 toMessages() 末尾追加的 userMessage 里，模型会看到两遍。
        //
        // 两个开关在这里生效（WORK-013）：
        // - 「参考当前对话的历史消息」关掉 → history 为空，助手只回答本轮这一句；
        // - 「使用已保存的记忆」或总开关关掉 → 召回为空（不读记忆）。
        // 都只影响"拼给模型什么"，不改任何存储行为——**关掉记忆不会删除记忆**。
        AiSettingsStore.Settings settings = settingsStore.get(userId);
        List<ConversationTurn> history = settings.referenceChatHistory() ? history(conversationId) : List.of();
        // 同样排在写库之前：它只读，且能抛（DB 异常），不能留到预留之后
        EntryContext entry = entryContext(conversation.getEntryId());
        List<String> snippets = memory(settings, userId, trimmed);
        // RAG 只读且失败自降级；放在写库与预扣之前，与历史/记忆/学情同一顺序
        String knowledgeContext = rag.retrieveContext(trimmed);
        Harness harness = Harness.ofOrDefault(conversation.getHarness());
        // 学情在写库与预扣**之前**取：它只读，且能抛（DB 异常），不能留到 reserve 之后。
        // 只有导师行为需要它——其它行为的提示词里没有对应的段落，注入反而浪费上下文。
        String learning = "";
        if (harness == Harness.TUTOR && conversation.getEntryId() != null) {
            learning = knowledge.learningSummary(userId, conversation.getEntryId());
        }

        appendMessage(conversationId, ConversationMessage.ROLE_USER, trimmed, null);
        // 滑动续期：聊到一半过期比"聊完就丢"更让人费解
        conversations.slideExpiry(conversationId, userId, now.plus(properties.getTtl()), now);

        // 预扣在**调模型之前**同步做完：余额不足时一个上游请求都不会发出去（REQ-006）。
        // 放在限流之后：被限流的那一轮本来就不该占用户的额度。**它的后面不能再有会抛的动作。**
        long reserved = credits.reserve(userId, credits.estimate());
        // 页面场景只做长度兜底（前端已按自己的上限截过）：防有人绕过前端直接调接口塞一大段
        String context = pageContext == null || pageContext.isBlank()
                ? null
                : pageContext.length() > MAX_PAGE_CONTEXT_CHARS
                        ? pageContext.substring(0, MAX_PAGE_CONTEXT_CHARS)
                        : pageContext;
        return new TurnPreparation(conversationId, history, trimmed, entry.title(), entry.body(),
                learning, snippets, access.get(), reserved, harness, context, knowledgeContext);
    }

    /**
     * 按两个开关决定要不要召回（WORK-013）。
     *
     * <p>总开关与「使用已保存的记忆」是**且**的关系：总开关是闸，那一轴是"要不要用它"。
     * 分开是为了让用户能"留着记忆但这次别用"——把两者合成一个，这种用法就没有表达了。
     */
    private List<String> memory(AiSettingsStore.Settings settings, long userId, String keyword) {
        if (!settings.memoryEnabled() || !settings.referenceSavedMemories()) {
            return List.of();
        }
        return memorySnippets(userId, keyword);
    }

    /**
     * 召回本人记忆并拼成给模型看的几行。
     *
     * <p>用**本轮用户输入**当检索关键词：这一步是"这轮的话可能和过去说过什么有关"，
     * 关键词为空（理论上不会，消息已校验非空）时退回"取最近的记忆"。
     *
     * <p>两路在提示词里**标注来源**：结构化的是"关于学习者的事实"，全文的是"他说过的话"。
     * 不标的话，模型会把"用户曾经随口说的一句"当成确定的事实。
     */
    private List<String> memorySnippets(long userId, String keyword) {
        return memories.recall(userId, keyword).stream()
                .map(item -> (item.source() == MemoryStore.MemoryItem.Source.STRUCTURED
                        ? "[学习者事实] " : "[他说过] ") + item.text())
                .toList();
    }

    /**
     * 一轮成功结束：写入助手消息并执行存储裁剪。
     *
     * <p><b>正文为空但有思考</b>时不写空消息——调用方会传入一段可见提示（"思考 token 吃满了"这类），
     * 那才是用户该看到的内容。
     *
     * @return 落库那条助手消息的 id。调用方用它做积分结算的 {@code ref_message_id}——
     *         "逐条可对账"（AC-006）就是靠这个对上
     */
    @Transactional
    public long complete(long userId, long conversationId, String assistantContent, TokenUsage usage) {
        Instant now = Instant.now();
        conversations.findActiveByIdAndUser(conversationId, userId, now)
                .orElseThrow(ConversationService::conversationNotFound);

        long messageId = appendMessage(conversationId, ConversationMessage.ROLE_ASSISTANT,
                assistantContent, toUsageJson(usage));
        conversations.slideExpiry(conversationId, userId, now.plus(properties.getTtl()), now);
        return messageId;
    }

    /** @return 落库那条消息的 id（{@code useGeneratedKeys} 回写） */
    private long appendMessage(long conversationId, String role, String content, String usageJson) {
        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setTokenUsageJson(usageJson);
        messages.insert(message);

        long stored = messages.countByConversation(conversationId);
        int drop = ConversationWindow.messagesToDrop(stored, properties.getMaxStoredMessages());
        if (drop > 0) {
            messages.deleteOldest(conversationId, drop);
        }
        return message.getId() == null ? 0 : message.getId();
    }

    /**
     * 一条历史消息（给界面读回用）。`usage` 可能为 null——旧消息或上游没给用量时都是。
     */
    public record MessageView(String role, String content, TokenUsage usage, Instant createdAt) {
    }

    /**
     * 读回某会话的消息（**旧 → 新**），供界面恢复历史（WORK-011）。
     *
     * <p>三条与发送路径一致的纪律：
     * <ul>
     *   <li><b>归属一律经 SQL 校验</b>：拿不到就抛与其它会话端点同一条错误，不区分原因；</li>
     *   <li><b>条数用库里保留的上限</b>（{@code maxStoredMessages}）：复用既有的"取最近 N 条"，
     *       不新增一条全量查询，也不会无限拉；</li>
     *   <li><b>不落库、不改状态</b>：这是一个纯读操作，进不了后续上下文，也不触发限流。</li>
     * </ul>
     */
    public List<MessageView> readMessages(long userId, long conversationId) {
        Instant now = Instant.now();
        conversations.findActiveByIdAndUser(conversationId, userId, now)
                .orElseThrow(ConversationService::conversationNotFound);
        List<ConversationMessage> newestFirst =
                messages.selectRecentNewestFirst(conversationId, properties.getMaxStoredMessages());
        List<ConversationMessage> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst.stream()
                .map(it -> new MessageView(it.getRole(), it.getContent(),
                        fromUsageJson(it.getTokenUsageJson()), it.getCreatedAt()))
                .toList();
    }

    /**
     * 历史消息里的用量。落库的是本类写出去的固定形状（见 {@link #toUsageJson}）。
     *
     * <p>读不出来时**返回 null 而不抛**：历史里一条坏消息不该让整个会话打不开——用量只是附注。
     */
    private static TokenUsage fromUsageJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var root = USAGE_JSON.readTree(json);
            return new TokenUsage(root.path("input").asInt(0), root.path("output").asInt(0),
                    root.path("total").asInt(0));
        } catch (JsonProcessingException failure) {
            return null;
        }
    }

    /** 历史按窗口取最近 N 条，再翻成旧 → 新（模型要的顺序）。 */
    private List<ConversationTurn> history(long conversationId) {
        List<ConversationMessage> newestFirst =
                messages.selectRecentNewestFirst(conversationId, properties.getMaxContextMessages());
        List<ConversationMessage> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst.stream()
                .map(it -> new ConversationTurn(ConversationTurn.Role.of(it.getRole()), it.getContent()))
                .toList();
    }

    /** 条目上下文只取**已发布**的；草稿或已删条目当作"没有上下文"，不让别的模块读到草稿。 */
    private EntryContext entryContext(Long entryId) {
        if (entryId == null) {
            return EntryContext.NONE;
        }
        return knowledge.findPublishedContent(entryId)
                .map(it -> new EntryContext(it.title(), it.body()))
                .orElse(EntryContext.NONE);
    }

    private record EntryContext(String title, String body) {

        static final EntryContext NONE = new EntryContext(null, null);
    }

    /**
     * 对外一律这一条：不区分"不存在"与"不是你的"。
     *
     * <p>用量落库用简单 JSON（BYOK 下要返还给用户，随消息存一份就够，不需要再建表）。
     */
    private static String toUsageJson(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        return "{\"input\":" + usage.inputTokens()
                + ",\"output\":" + usage.outputTokens()
                + ",\"total\":" + usage.totalTokens() + "}";
    }

    private static BizException conversationNotFound() {
        return new BizException(ErrorCode.NOT_FOUND, "会话不存在或已过期");
    }
}
