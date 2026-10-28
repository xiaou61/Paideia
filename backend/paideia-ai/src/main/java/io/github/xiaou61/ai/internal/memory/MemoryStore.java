package io.github.xiaou61.ai.internal.memory;

import io.github.xiaou61.ai.MemoryCandidate;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.platform.SearchQuery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记忆的存取与召回（用户裁决 2：结构化 + 全文两路）。
 *
 * <p>**严格私有**（用户裁决 3）：每个方法都带 `user_id`，没有"读别人记忆"的入口，
 * 也没有分享开关。不是靠调用方自觉，而是这层就没有那条路。
 *
 * <p>召回是**两路合并**：结构化那路给的是"关于这个学习者的既成事实"（哪个条目懂／不懂），
 * 全文那路给的是"他说过的话"。顺序上结构化在前——事实比原话更稳定，也更容易被模型直接用上。
 *
 * <p><b>WORK-013 起写入方有两个</b>：用户点击（`SOURCE_MANUAL`）与自动整理（`SOURCE_AUTO`）。
 * 两者写的是同一套表、同一套上限，差别只在 `source` 与"有没有出处"。所以这里不分成两个类——
 * 分成两份会让"上限、校验、归属"三件事各自漂移。
 */
@Service
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    /**
     * 一次召回的条数上限。
     *
     * <p>ponytail: 固定值，不做成配置。它只影响"进提示词的记忆有多长"，等真的出现
     * "记忆太多把上下文挤爆"的实测再提成配置项。
     */
    private static final int RECALL_LIMIT = 8;

    /**
     * 面板一次最多取多少条（分页的一页）。
     *
     * <p>取满一页不等于"到底了"——调用方按"返回条数 < 请求条数"判断还有没有下一页。
     */
    private static final int PAGE_LIMIT = 200;

    /** 与迁移里的列宽一致：写入前先校验，别让数据库报一个用户看不懂的错。 */
    private static final int MAX_STRUCTURED_CHARS = 512;
    private static final int MAX_TEXT_CHARS = 1024;
    private static final int MAX_REASON_CHARS = 255;

    /**
     * 判重时把多少条已有记忆给模型看（WORK-013）。
     *
     * <p>ponytail: 固定 40 条。**天花板**是"更早的记忆不参与判重，可能出现跨时间重复"——
     * 一个学习者攒到 40 条以上时才会碰到。升级路径是"按 kind/refId 分批"或真做向量检索，
     * 到那个规模再说，现在按设计不做向量。
     */
    private static final int DEDUP_POOL_LIMIT = 40;

    /** 谁写的这条。跟随当前内容：被自动整理覆盖过的，就记 `auto`（见 {@link MemoryRow}）。 */
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_AUTO = "auto";

    /** 一次淘汰删多少行。与 {@code TelemetryRetentionPurger} 同一档，运维只需要记一组数字。 */
    private static final int PURGE_BATCH = 500;

    private final MemoryMapper mapper;
    private final MemoryProperties properties;

    MemoryStore(MemoryMapper mapper, MemoryProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * 一条记忆的对外形状。
     *
     * <p>`origin` / `reason` / `conversationId` 是 WORK-013 加的"它为什么在这里"三件套：
     * 面板要能说清哪条是自动整理的、出自哪段对话、以及当时为什么这么判。
     */
    public record MemoryItem(Source source, long id, String text, Instant createdAt,
                             String origin, String reason, Long conversationId) {

        public enum Source {
            STRUCTURED,
            TEXT
        }
    }

    /** 写一条**结构化**记忆（如"某条目：还不懂"）。手动写入那一路。 */
    @Transactional
    public long rememberStructured(long userId, String kind, Long refId, String content) {
        return insertStructured(userId, kind, refId, content, SOURCE_MANUAL, null, null, null);
    }

    /** 写一条**全文**记忆（"他说过的话"）。手动写入那一路。 */
    @Transactional
    public long rememberText(long userId, String content) {
        return insertText(userId, content, SOURCE_MANUAL, null, null, null);
    }

    /**
     * 自动整理写入的一条（WORK-013）。
     *
     * <p>出处与理由**一起写**：面板要显示"这条出自哪段对话、当时为什么这么判"。缺了它们，
     * 用户看到一条不认同的记忆就只能删，没法回看它从哪冒出来的。
     */
    @Transactional
    public long rememberAuto(long userId, MemoryItem.Source source, String kind, Long refId,
                             String content, long conversationId, long refMessageId, String reason) {
        return source == MemoryItem.Source.STRUCTURED
                ? insertStructured(userId, kind, refId, content, SOURCE_AUTO, conversationId, refMessageId, reason)
                : insertText(userId, content, SOURCE_AUTO, conversationId, refMessageId, reason);
    }

    /**
     * 覆盖一条已存在的记忆（自动整理的 {@code UPDATE} 动作）。
     *
     * <p>**归属仍由 SQL 判**：模型给的 id 是不可信输入（可能幻觉、可能指向别人的行），
     * 删不到/改不到都只返回 false，当作"没这条"，不报错——报错等于告诉模型"你猜的这个 id 存在"。
     *
     * @return 是否真的改到了一行
     */
    @Transactional
    public boolean overwrite(long userId, MemoryItem.Source source, long id, String content,
                             long conversationId, long refMessageId, String reason) {
        String trimmed = requireLength(content, "记忆内容",
                source == MemoryItem.Source.STRUCTURED ? MAX_STRUCTURED_CHARS : MAX_TEXT_CHARS);
        if (SensitiveContent.find(trimmed).isPresent()) {
            log.info("ai.memory.filtered store={} id={} phase=update", source, id);
            return false;
        }
        MemoryRow row = new MemoryRow();
        row.setId(id);
        row.setUserId(userId);
        row.setContent(trimmed);
        row.setSource(SOURCE_AUTO);
        row.setConversationId(conversationId);
        row.setRefMessageId(refMessageId);
        row.setReason(clampReason(reason));
        int affected = source == MemoryItem.Source.STRUCTURED
                ? mapper.updateStructured(row)
                : mapper.updateText(row);
        return affected > 0;
    }

    /**
     * 召回：结构化取最近的若干条，全文按关键词检索（没有关键词时取最近若干条）。
     *
     * <p>关键词走 {@link SearchQuery} 的判定：中文单字在 ngram 索引上召回为零，必须退回 `LIKE`。
     * 这是**已经踩过一次**的坑（知识库检索那里），所以这里直接用同一份判定而不是重写一遍。
     */
    public List<MemoryItem> recall(long userId, String keyword) {
        Instant now = Instant.now();
        String normalized = SearchQuery.normalize(keyword);
        boolean fulltext = SearchQuery.usesFulltext(normalized);
        String like = normalized != null && !fulltext ? SearchQuery.likePattern(normalized) : null;

        List<MemoryItem> items = new ArrayList<>();
        List<Long> structuredIds = new ArrayList<>();
        for (MemoryRow row : mapper.selectStructuredRelevant(userId, normalized, like, fulltext,
                now, RECALL_LIMIT)) {
            items.add(toItem(MemoryItem.Source.STRUCTURED, row));
            structuredIds.add(row.getId());
        }

        List<Long> textIds = new ArrayList<>();
        List<MemoryRow> textRows = normalized == null
                ? mapper.selectTextRecent(userId, RECALL_LIMIT, 0)
                : mapper.searchText(userId, normalized, like, fulltext, now, RECALL_LIMIT);
        for (MemoryRow row : textRows) {
            items.add(toItem(MemoryItem.Source.TEXT, row));
            textIds.add(row.getId());
        }

        touch(userId, structuredIds, textIds, now);
        return items;
    }

    /**
     * 刷新"最近一次被召回"（WORK-050 REQ-005）。**失败只记日志**：这是记账，不是业务——
     * 为了它让一次回答失败，代价与收益完全不成比例。下一次召回会再写一遍。
     */
    public void touch(long userId, List<Long> structuredIds, List<Long> textIds) {
        touch(userId, structuredIds, textIds, Instant.now());
    }

    private void touch(long userId, List<Long> structuredIds, List<Long> textIds, Instant now) {
        try {
            if (!structuredIds.isEmpty()) {
                mapper.touchStructured(userId, structuredIds, now);
            }
            if (!textIds.isEmpty()) {
                mapper.touchText(userId, textIds, now);
            }
        } catch (RuntimeException failure) {
            log.warn("ai.memory.touch_failed userId={} reason={}", userId, failure.getMessage());
        }
    }

    /**
     * 按时间淘汰（WORK-050 REQ-004）。
     *
     * <p>判据是 {@code COALESCE(last_used_at, created_at)}：被持续召回的记忆不会被删，哪怕它很旧；
     * "记了但从来没被用上"的才过期。分档来自 {@link MemoryProperties}，按 kind 各删各的。
     *
     * @return 本次删掉的总行数
     */
    @Transactional
    public int purgeExpired(Instant now) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : properties.getStructuredTtlDays().entrySet()) {
            Instant before = now.minus(Math.max(entry.getValue(), 0), ChronoUnit.DAYS);
            while (true) {
                int deleted = mapper.purgeStructured(entry.getKey(), before, PURGE_BATCH);
                total += deleted;
                if (deleted < PURGE_BATCH) {
                    break;
                }
            }
        }
        Instant textBefore = now.minus(Math.max(properties.getTextTtlDays(), 0), ChronoUnit.DAYS);
        while (true) {
            int deleted = mapper.purgeText(textBefore, PURGE_BATCH);
            total += deleted;
            if (deleted < PURGE_BATCH) {
                break;
            }
        }
        return total;
    }

    /**
     * 判重用的"已有记忆"（WORK-013）。
     *
     * <p>**不按关键词检索**，而是取最近 {@link #DEDUP_POOL_LIMIT} 条直接给模型看：一个学习者的
     * 记忆只有几十条，全喂比检索更准，也不需要向量。两路各自取，顺序与召回一致（事实在前）。
     */
    public List<MemoryItem> dedupPool(long userId) {
        List<MemoryItem> items = new ArrayList<>();
        for (MemoryRow row : mapper.selectStructuredRecent(userId, DEDUP_POOL_LIMIT, 0)) {
            items.add(toItem(MemoryItem.Source.STRUCTURED, row));
        }
        for (MemoryRow row : mapper.selectTextRecent(userId, DEDUP_POOL_LIMIT, 0)) {
            items.add(toItem(MemoryItem.Source.TEXT, row));
        }
        return items;
    }

    /**
     * 本人全部记忆的一页（面板用，用户裁决 13 + WORK-013 的分页）。
     *
     * <p>**不复用 {@link #recall}**：那个带上限（RECALL_LIMIT = 8，只为"进提示词的几行"服务）。
     * 面板要的是"它到底记住了我什么"，被截断会让界面撒谎。
     *
     * <p>分页只解决"条目多到看不完"；不做总数查询——"还有没有下一页"按"这一页是否取满"判断，
     * 少一次 COUNT 也少一次会过期的总数。
     */
    public List<MemoryItem> list(long userId, int limit, int offset) {
        int pageSize = limit <= 0 ? PAGE_LIMIT : Math.min(limit, PAGE_LIMIT);
        int from = Math.max(offset, 0);
        // offset 不能分别下发给两路：那样一页最多 2×pageSize 条，而调用方按 items.length 推进
        // 偏移量，两路各自的第 from 条之后会被整段跳过。改为两路各取 from+pageSize 条、
        // 合并排序后再统一切掉前 from 条。
        int fetch = from + pageSize;
        List<MemoryItem> items = new ArrayList<>();
        for (MemoryRow row : mapper.selectStructuredRecent(userId, fetch, 0)) {
            items.add(toItem(MemoryItem.Source.STRUCTURED, row));
        }
        for (MemoryRow row : mapper.selectTextRecent(userId, fetch, 0)) {
            items.add(toItem(MemoryItem.Source.TEXT, row));
        }
        items.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        if (items.size() <= from) {
            return List.of();
        }
        return List.copyOf(items.subList(from, Math.min(items.size(), from + pageSize)));
    }

    /**
     * 逐条删除。返回是否真的删掉了一行（不是本人的行会返回 false，且**不泄漏它是否存在**）。
     *
     * <p>自动整理判定"这条已不成立"时也走这里——删除的理由记在日志里（设计 §4），
     * 不写回任何一行（那一行已经没了，没地方写）。
     */
    @Transactional
    public boolean delete(long userId, MemoryItem.Source source, long id) {
        int affected = source == MemoryItem.Source.STRUCTURED
                ? mapper.deleteStructured(id, userId)
                : mapper.deleteText(id, userId);
        return affected > 0;
    }

    /**
     * 自评桥的写入（WORK-010 T-08）：同一条目**覆盖**，不追加。
     *
     * <p>内容是给人看的一句话（"第 12 条《模块化单体的边界》：还不懂"），不是 raw 的等级值——
     * 记忆每轮都会被读进提示词，一句能直接读懂的话比 `entry_understanding=unsure` 有用。
     *
     * <p>**只覆盖这条桥自己的记录**（`kind = entry_understanding` + 同一个 `ref_id`）：
     * 用户手点「记住这条」写的是全文那一路，仍然是追加；模型自动整理写的是别的 kind，
     * 也不受这个唯一键影响。
     */
    @Transactional
    public void rememberEntryAssessment(long userId, long entryId, String title, String level) {
        String content = "第 " + entryId + " 条《" + title + "》：" + labelOfLevel(level);
        mapper.upsertStructuredByRef(userId, SelfAssessmentMemoryBridge.KIND, entryId,
                requireLength(content, "记忆内容", MAX_STRUCTURED_CHARS));
    }

    /** 取消自评时删掉那条记忆。返回删掉的行数（0 表示本来就没有，不算错）。 */
    @Transactional
    public int forgetEntryAssessment(long userId, long entryId) {
        return mapper.deleteStructuredByRef(userId, SelfAssessmentMemoryBridge.KIND, entryId);
    }

    /** 等级 → 中文。不认识的值原样带出去：宁可显示一个怪词，也不要静默丢掉用户的选择。 */
    private static String labelOfLevel(String level) {
        return switch (level == null ? "" : level) {
            case "understood" -> "已掌握";
            case "unsure" -> "还不懂";
            default -> level;
        };
    }

    /**
     * 清空两路（REQ-009 的"清空并关闭"）。
     *
     * <p>返回删掉的**总条数**：界面要说"清掉了 N 条"，而不是只说"已清空"——
     * 用户点了这个按钮之后想确认的第一件事就是"到底删掉了什么"。
     */
    @Transactional
    public int clearAll(long userId) {
        return mapper.clearStructured(userId) + mapper.clearText(userId);
    }

    /**
     * 执行一次自动整理的判定结果（WORK-013）。
     *
     * <p><b>整批一个事务、全有或全无</b>：写一半再失败会留下"这次整理写到一半"的状态，
     * 而用户看不到、下次整理又会基于它判重——那比整批放弃坏得多。
     *
     * <p>**为什么不放在 {@code MemoryExtractionService} 里**：那个类要先发一次网络调用再写库，
     * 而 {@code @Transactional} 在同类内部自调用时不生效（走不到代理）。把写入放在这一层，
     * 事务边界与"写记忆"这件事待在一起，也顺带让"所有对记忆的写都经过 MemoryStore"这条纪律成立。
     *
     * <p>{@code UPDATE}/{@code DELETE} 的目标 id 来自模型（不可信）：改不到就记一条日志继续，
     * **不报错**——报错等于告诉模型"你猜的这个 id 存在"，而它是幻觉的时候占多数。
     *
     * @return 实际写入/覆盖/删除的条数，供日志与测试断言
     */
    @Transactional
    public int applyAuto(long userId, long conversationId, long refMessageId,
                         List<MemoryCandidate> candidates) {
        int affected = 0;
        for (MemoryCandidate candidate : candidates) {
            switch (candidate.action()) {
                case ADD -> {
                    if (SensitiveContent.find(candidate.content()).isPresent()) {
                        log.info("ai.memory.filtered store={} phase=add", candidate.store());
                        continue;
                    }
                    rememberAuto(userId, candidate.store() == MemoryCandidate.Store.STRUCTURED
                                    ? MemoryItem.Source.STRUCTURED : MemoryItem.Source.TEXT,
                            candidate.kind(), candidate.refId(), candidate.content(),
                            conversationId, refMessageId, candidate.reason());
                    affected++;
                }
                case UPDATE -> {
                    // targetId 为 null 说明解析阶段没给出来：直接记未命中，不能让自动拆箱抛 NPE
                    // 把同批的其它写入一起回滚（applyAuto 是整批一个事务）。
                    boolean updated = candidate.targetId() != null
                            && overwrite(userId, toItemSource(candidate.store()), candidate.targetId(),
                                    candidate.content(), conversationId, refMessageId, candidate.reason());
                    if (updated) {
                        affected++;
                    } else {
                        log.info("ai.memory.update_miss userId={} store={} targetId={}",
                                userId, candidate.store(), candidate.targetId());
                    }
                }
                case DELETE -> {
                    boolean deleted = delete(userId, toItemSource(candidate.store()), candidate.targetId());
                    if (deleted) {
                        affected++;
                    }
                    // 删除的理由只进日志：那一行已经没了，没有地方可写（设计 §4）
                    log.info("ai.memory.deleted userId={} store={} targetId={} reason={}",
                            userId, candidate.store(), candidate.targetId(), candidate.reason());
                }
                case SKIP -> {
                    // 什么都不做：这正是"看过但不值得记"的表达
                }
                default -> throw new IllegalStateException("未处理的动作：" + candidate.action());
            }
        }
        return affected;
    }

    /**
     * 敏感信息闸（WORK-050 REQ-001）。**唯一一处真正拦写入的地方**——放在两个底层 insert 上，
     * 手动、自动、外部工具三条路都过它。
     *
     * <p>命中就抛：手动写入要让用户看见"这条没记"；自动整理那条路在 {@link #applyAuto} 里
     * 已经先逐条过滤过，不会走到这里的抛错。
     */
    private static String requireClean(String content) {
        SensitiveContent.Category category = SensitiveContent.find(content).orElse(null);
        if (category != null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "这条内容疑似包含" + categoryLabel(category) + "，未保存");
        }
        return content;
    }

    private static String categoryLabel(SensitiveContent.Category category) {
        return switch (category) {
            case PRIVATE_KEY -> "私钥";
            case API_KEY -> "密钥";
            case KEY_VALUE -> "口令或令牌";
            case CONNECTION_STRING -> "带凭据的连接串";
            case ID_CARD -> "身份证号";
            case BANK_CARD -> "银行卡号";
            case PHONE -> "手机号";
        };
    }

    private static MemoryItem.Source toItemSource(MemoryCandidate.Store store) {
        return store == MemoryCandidate.Store.STRUCTURED
                ? MemoryItem.Source.STRUCTURED : MemoryItem.Source.TEXT;
    }

    private long insertStructured(long userId, String kind, Long refId, String content, String source,
                                  Long conversationId, Long refMessageId, String reason) {
        MemoryRow row = new MemoryRow();
        row.setUserId(userId);
        row.setKind(requireNonBlank(kind, "记忆种类", 32));
        row.setRefId(refId);
        row.setContent(requireClean(requireLength(content, "记忆内容", MAX_STRUCTURED_CHARS)));
        row.setSource(source);
        row.setConversationId(conversationId);
        row.setRefMessageId(refMessageId);
        row.setReason(clampReason(reason));
        mapper.insertStructured(row);
        return row.getId();
    }

    private long insertText(long userId, String content, String source,
                            Long conversationId, Long refMessageId, String reason) {
        MemoryRow row = new MemoryRow();
        row.setUserId(userId);
        row.setContent(requireClean(requireLength(content, "记忆内容", MAX_TEXT_CHARS)));
        row.setSource(source);
        row.setConversationId(conversationId);
        row.setRefMessageId(refMessageId);
        row.setReason(clampReason(reason));
        mapper.insertText(row);
        return row.getId();
    }

    private static MemoryItem toItem(MemoryItem.Source source, MemoryRow row) {
        return new MemoryItem(source, row.getId(), row.getContent(), row.getCreatedAt(),
                row.getSource(), row.getReason(), row.getConversationId());
    }

    /** 理由只是个附注，长了就截断——它不该让一条记忆写不进去。 */
    private static String clampReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_REASON_CHARS ? trimmed : trimmed.substring(0, MAX_REASON_CHARS);
    }

    /**
     * 空白内容直接拒，超长也拒。
     *
     * <p>超长不静默截断：记忆是要被后续对话反复读取的东西，悄悄砍掉一半比报错更坏。
     * 这一层是信任边界（写入方包含模型），所以校验放在这里而不是每个调用点。
     */
    private static String requireNonBlank(String raw, String label, int maxChars) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, label + "不能为空");
        }
        if (trimmed.length() > maxChars) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, label + "不能超过 " + maxChars + " 个字符");
        }
        return trimmed;
    }

    private static String requireLength(String raw, String label, int maxChars) {
        return requireNonBlank(raw, label, maxChars);
    }
}
