package io.github.xiaou61.ai.internal.memory;

import io.github.xiaou61.ai.MemoryCandidate;
import io.github.xiaou61.ai.internal.settings.AiSettingsStore;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 半自动提案（WORK-050 TASK-009 / REQ-006..008）。
 *
 * <p><b>为什么要它</b>：自动整理只有两个极端——直接写成事实，或者什么都不记。中间那种
 * "我看见了，但拿不准该不该长期记"没有表达方式。基线实测里，模型对灰区内容会吐出结构不完整的
 * 对象，导致**整次整理作废**（2/40 条用例）。给它一个"提议"的出口，既救回那些轮次，也把
 * "猜测变事实"的风险交给用户一次点击。
 *
 * <p><b>被敏感信息过滤的内容绝不进这张表的正文</b>：只留字节数与原因。把含口令的原文存进
 * 候选表等于换个地方泄露——而且候选表还会被面板渲染出来。
 *
 * <p>候选在 {@link MemoryProperties#getCandidateExpiryDays()} 天后过期（默认 14 天）：
 * 面板不是每天都会打开，7 天太短；再长会让过期候选挤占注意力。
 */
@Service
public class MemoryCandidateService {

    static final String STATUS_PENDING = "pending";
    static final String STATUS_FILTERED = "filtered";

    /** 面板要的形状。`content` 对被过滤的行为 null——见类注释。 */
    public record Candidate(long id, String store, String kind, String content, String reason,
                            Long conversationId, String status, String decisionReason,
                            Instant createdAt, Instant expiresAt) {
    }

    private final MemoryCandidateMapper mapper;
    private final MemoryStore memories;
    private final MemoryProperties properties;
    private final AiSettingsStore settings;

    MemoryCandidateService(MemoryCandidateMapper mapper, MemoryStore memories,
                           MemoryProperties properties, AiSettingsStore settings) {
        this.mapper = mapper;
        this.memories = memories;
        this.properties = properties;
        this.settings = settings;
    }

    /** 模型说"拿不准"的那一条落这里。返回新行 id。 */
    @Transactional
    public long propose(long userId, MemoryCandidate.Store store, String kind, Long refId,
                        String content, Long conversationId, Long refMessageId, String reason) {
        return insert(userId, store, kind, refId, content, conversationId, refMessageId, reason,
                STATUS_PENDING, null);
    }

    /**
     * 被敏感信息过滤掉的一条：**只留长度与原因，正文不写**。
     *
     * <p>它存在的意义是"让用户知道有一条没记"，而不是让用户看那条内容。
     */
    @Transactional
    public long recordFiltered(long userId, MemoryCandidate.Store store, String content,
                              Long conversationId, Long refMessageId) {
        return insert(userId, store, null, null, null, conversationId, refMessageId, null,
                STATUS_FILTERED, "sensitive", content == null ? 0 : content.length());
    }

    /** 待确认的候选。总开关关着时返回空——与"关掉后不召回、不整理"同一条闸。 */
    public List<Candidate> pending(long userId, int limit, int offset) {
        if (!settings.get(userId).memoryEnabled()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        for (MemoryCandidateRow row : mapper.list(userId, STATUS_PENDING, Math.max(limit, 1),
                Math.max(offset, 0))) {
            out.add(toView(row));
        }
        return out;
    }

    /**
     * 接受一条：先抢占状态，再写正式表。
     *
     * <p>顺序重要：`markAccepted` 的 `WHERE status='pending'` 是并发闸，抢不到就直接返回冲突，
     * **不会写第二条正式记忆**。事务保证写正式表失败时状态一起回滚。
     */
    @Transactional
    public void accept(long userId, long id) {
        requireEnabled(userId);
        MemoryCandidateRow row = mapper.find(userId, id);
        if (row == null || !STATUS_PENDING.equals(row.getStatus())) {
            throw notFound();
        }
        if (row.getContent() == null || row.getContent().isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "这一条没有内容，无法写入");
        }
        if (mapper.markAccepted(userId, id) == 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "这一条已经被处理过了");
        }
        // 会话/消息可能早已不在：候选的生命周期比一段对话长，用 0 表示"没有出处"，
        // 与正式表那两列可空并不冲突（0 只是"不指向任何一条"）。
        memories.rememberAuto(userId,
                "structured".equals(row.getStore())
                        ? MemoryStore.MemoryItem.Source.STRUCTURED : MemoryStore.MemoryItem.Source.TEXT,
                row.getKind(), row.getRefId(), row.getContent(),
                row.getConversationId() == null ? 0L : row.getConversationId(),
                row.getRefMessageId() == null ? 0L : row.getRefMessageId(),
                row.getReason());
    }

    /** 拒绝一条。保留 7 天再清，方便用户"刚才手滑了"回看。 */
    @Transactional
    public void reject(long userId, long id) {
        requireEnabled(userId);
        if (mapper.markRejected(userId, id, Instant.now().plus(7, ChronoUnit.DAYS)) == 0) {
            throw notFound();
        }
    }

    /** 过期清理。与记忆淘汰同一个小时间隔的任务调用。 */
    @Transactional
    public int dropExpired(Instant now, int batch) {
        int total = 0;
        while (true) {
            int deleted = mapper.dropExpired(now, batch);
            total += deleted;
            if (deleted < batch) {
                return total;
            }
        }
    }

    /** 注销级联。 */
    @Transactional
    public int deleteByUser(long userId) {
        return mapper.deleteByUser(userId);
    }

    private long insert(long userId, MemoryCandidate.Store store, String kind, Long refId,
                        String content, Long conversationId, Long refMessageId, String reason,
                        String status, String decisionReason) {
        return insert(userId, store, kind, refId, content, conversationId, refMessageId, reason,
                status, decisionReason, content == null ? 0 : content.length());
    }

    private long insert(long userId, MemoryCandidate.Store store, String kind, Long refId,
                        String content, Long conversationId, Long refMessageId, String reason,
                        String status, String decisionReason, int contentBytes) {
        Instant now = Instant.now();
        MemoryCandidateRow row = new MemoryCandidateRow();
        row.setUserId(userId);
        row.setStore(store == MemoryCandidate.Store.STRUCTURED ? "structured" : "text");
        row.setKind(kind);
        row.setRefId(refId);
        row.setContent(content);
        row.setContentBytes(contentBytes);
        row.setSource(MemoryStore.SOURCE_AUTO);
        row.setConversationId(conversationId);
        row.setRefMessageId(refMessageId);
        row.setReason(clamp(reason));
        row.setStatus(status);
        row.setDecisionReason(decisionReason);
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(Math.max(properties.getCandidateExpiryDays(), 1), ChronoUnit.DAYS));
        mapper.insert(row);
        return row.getId() == null ? 0 : row.getId();
    }

    private void requireEnabled(long userId) {
        if (!settings.get(userId).memoryEnabled()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "个性化记忆已关闭");
        }
    }

    private static BizException notFound() {
        return new BizException(ErrorCode.INVALID_ARGUMENT, "这条候选不存在或已经处理过");
    }

    private static String clamp(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    private static Candidate toView(MemoryCandidateRow row) {
        return new Candidate(row.getId(), row.getStore(), row.getKind(), row.getContent(),
                row.getReason(), row.getConversationId(), row.getStatus(), row.getDecisionReason(),
                row.getCreatedAt(), row.getExpiresAt());
    }
}
