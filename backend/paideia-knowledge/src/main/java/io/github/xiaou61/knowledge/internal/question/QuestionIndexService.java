package io.github.xiaou61.knowledge.internal.question;

import io.github.xiaou61.knowledge.QuestionLabelingPort;
import io.github.xiaou61.knowledge.internal.entry.Entry;
import io.github.xiaou61.knowledge.internal.entry.EntryMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 题库派生索引服务（WORK-083 TASK-001）。
 *
 * <p>正文是唯一权威：这里只把已发布条目正文里的题目身份与难度标签（从
 * {@code question_difficulty} 派生）落进 {@code question_index}，供刷题/组卷筛选。
 * 条目保存/发布后调 {@link #reconcile(long)}；也可 {@link #refreshAll()} 全量重建。
 */
@Service
public class QuestionIndexService {

    private final QuestionIndexMapper mapper;
    private final EntryMapper entryMapper;
    private final ObjectProvider<QuestionLabelingPort> labelingPorts;

    QuestionIndexService(QuestionIndexMapper mapper, EntryMapper entryMapper,
                         ObjectProvider<QuestionLabelingPort> labelingPorts) {
        this.mapper = mapper;
        this.entryMapper = entryMapper;
        this.labelingPorts = labelingPorts;
    }

    public record RebuildResult(int created, int updated, int removed, int skippedBlocks) {
    }

    /**
     * 全量重建：遍历已发布条目，再清掉不在已发布集合里的残留行。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：这里要读全库已发布正文（每篇可能几十 KB），
     * 如果整轮包在一个事务里，重建期间会一直持有行锁并撑大回滚段，题库变大后这条路径会成为
     * 稳定性风险。逐条 upsert 是按 (entry_id, question_key) 幂等的，中途失败下一轮自愈，
     * 不需要整轮原子性；真正的原子性要求落在单条目的 {@link #reconcile(long)} 上。
     */
    public RebuildResult refreshAll() {
        List<Entry> entries = entryMapper.findAllPublishedWithBody();
        int created = 0;
        int updated = 0;
        int removed = 0;
        int skipped = 0;
        List<Long> ids = new ArrayList<>();
        for (Entry entry : entries) {
            ids.add(entry.getId());
            RebuildResult one = reconcileEntry(entry);
            created += one.created();
            updated += one.updated();
            removed += one.removed();
            skipped += one.skippedBlocks();
        }
        // 空集合意味着"一个已发布条目都没有"：这时该显式清空，而不是让 SQL 退化成裸 DELETE
        removed += ids.isEmpty() ? mapper.deleteAll() : mapper.deleteOrphans(ids);
        return new RebuildResult(created, updated, removed, skipped);
    }

    /** 对单个条目对账；条目已非发布态时删除其全部索引行。 */
    @Transactional
    public RebuildResult reconcile(long entryId) {
        Optional<Entry> entry = entryMapper.findPublishedById(entryId);
        if (entry.isEmpty()) {
            int removed = 0;
            for (QuestionIndexMapper.IndexRow row : mapper.listIdentities(entryId)) {
                mapper.deleteByEntryAndKey(entryId, row.questionKey());
                removed++;
            }
            return new RebuildResult(0, 0, removed, 0);
        }
        return reconcileEntry(entry.get());
    }

    private RebuildResult reconcileEntry(Entry entry) {
        List<QuestionCatalog.Question> questions = QuestionCatalog.parse(entry.getId(), entry.getBody());
        Map<String, QuestionIndexMapper.IndexRow> existing = new LinkedHashMap<>();
        for (QuestionIndexMapper.IndexRow row : mapper.listIdentities(entry.getId())) {
            existing.put(row.questionKey(), row);
        }
        int added = 0;
        int updated = 0;
        int removed = 0;
        Set<String> seen = new HashSet<>();
        Instant now = Instant.now();
        Map<String, String> aiLabels = aiLabels(questions);
        Map<String, QuestionIndexMapper.DifficultyRow> difficulties = difficulties(questions);
        for (QuestionCatalog.Question question : questions) {
            seen.add(question.questionKey());
            QuestionIndexMapper.DifficultyRow difficulty = difficulties.get(question.fingerprint());
            Double rating = difficulty == null || difficulty.rating() == null
                    ? null : difficulty.rating().doubleValue();
            int attempts = difficulty == null || difficulty.attempts() == null ? 0 : difficulty.attempts();
            String aiLabel = aiLabels.get(question.questionKey());
            String label = resolveLabel(aiLabel, rating, attempts, question);
            mapper.upsert(entry.getId(), entry.getCategoryId(), question.blockKey(), question.questionKey(),
                    question.fingerprint(), question.options().size(), label, now);
            QuestionIndexMapper.IndexRow previous = existing.get(question.questionKey());
            if (previous == null) {
                added++;
            } else if (!previous.fingerprint().equals(question.fingerprint())
                    || !label.equals(previous.difficultyLabel())) {
                updated++;
            }
        }
        for (String key : existing.keySet()) {
            if (!seen.contains(key)) {
                mapper.deleteByEntryAndKey(entry.getId(), key);
                removed++;
            }
        }
        return new RebuildResult(added, updated, removed, 0);
    }

    /** 一次取回该条目全部题目的难度校准，避免逐题一次 SQL。 */
    private Map<String, QuestionIndexMapper.DifficultyRow> difficulties(
            List<QuestionCatalog.Question> questions) {
        if (questions.isEmpty()) {
            return Map.of();
        }
        List<String> fingerprints = questions.stream()
                .map(QuestionCatalog.Question::fingerprint)
                .distinct()
                .toList();
        Map<String, QuestionIndexMapper.DifficultyRow> byFingerprint = new LinkedHashMap<>();
        for (QuestionIndexMapper.DifficultyRow row : mapper.findDifficulties(fingerprints)) {
            byFingerprint.put(row.fingerprint(), row);
        }
        return byFingerprint;
    }

    /** 端口存在则请 AI 打难度标签；无端口/端口异常一律回退空表（不报错）。 */
    private Map<String, String> aiLabels(List<QuestionCatalog.Question> questions) {
        QuestionLabelingPort port = labelingPorts.getIfAvailable();
        if (port == null || questions.isEmpty()) {
            return Map.of();
        }
        try {
            List<QuestionLabelingPort.Question> asked = questions.stream()
                    .map(question -> new QuestionLabelingPort.Question(
                            question.questionKey(), question.stem(), question.options()))
                    .toList();
            QuestionLabelingPort.Result result = port.tag(asked);
            if (result != null && result.difficultyByQuestionKey() != null) {
                return result.difficultyByQuestionKey();
            }
        } catch (RuntimeException ignored) {
            // 端口是可选增强；失败即回退规则估算
        }
        return Map.of();
    }

    /**
     * 难度来源优先级：AI 标注 &gt; Elo 校准（够次数）&gt; 规则兜底（新题）&gt; medium。
     *
     * <p>规则兜底只在「既无 AI 标签、又未校准」时生效，不覆盖已有的真实作答信号。
     */
    private static String resolveLabel(String aiLabel, Double rating, int attempts,
                                       QuestionCatalog.Question question) {
        if (isValidLabel(aiLabel)) {
            return aiLabel;
        }
        if (rating != null && attempts >= DifficultyLabel.CALIBRATED_ATTEMPTS) {
            return DifficultyLabel.estimate(rating, attempts);
        }
        String rule = DifficultyHeuristic.estimate(question.stem(), question.options());
        return isValidLabel(rule) ? rule : DifficultyLabel.MEDIUM;
    }

    private static boolean isValidLabel(String label) {
        return DifficultyLabel.EASY.equals(label)
                || DifficultyLabel.MEDIUM.equals(label)
                || DifficultyLabel.HARD.equals(label);
    }

    /** 题目快照（含答案，仅内部使用；接口层不得下发 answerIndex）。 */
    public record QuestionSnapshot(String blockKey, String questionKey, String fingerprint,
                                   String stem, List<String> options, int answerIndex) {
    }

    public List<QuestionSnapshot> snapshots(long entryId) {
        return entryMapper.findPublishedById(entryId)
                .map(entry -> QuestionCatalog.parse(entryId, entry.getBody()).stream()
                        .map(question -> new QuestionSnapshot(question.blockKey(), question.questionKey(),
                                question.fingerprint(), question.stem(), question.options(),
                                question.answerIndex()))
                        .toList())
                .orElse(List.of());
    }

    public QuestionSnapshot snapshot(long entryId, String questionKey) {
        for (QuestionSnapshot snapshot : snapshots(entryId)) {
            if (snapshot.questionKey().equals(questionKey)) {
                return snapshot;
            }
        }
        return null;
    }
}
