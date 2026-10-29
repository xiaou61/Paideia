package io.github.xiaou61.knowledge.internal.question;

import io.github.xiaou61.knowledge.internal.entry.Entry;
import io.github.xiaou61.knowledge.internal.entry.EntryMapper;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次作答：判定、留痕、更新难度与掌握度、给出补课建议（WORK-033）。
 *
 * <p>**判定在服务端**：客户端只发「选了第几个」，对错由正文里的答案决定。这样即便前端被改，
 * 记录也不会被污染；同时保住了「题目内容只有一个真相」这条线。
 */
@Service
public class QuestionAttemptService {

    private static final int MAX_LATENCY_MS = 10 * 60 * 1000;

    private final QuestionAttemptMapper mapper;
    private final EntryMapper entryMapper;

    QuestionAttemptService(QuestionAttemptMapper mapper, EntryMapper entryMapper) {
        this.mapper = mapper;
        this.entryMapper = entryMapper;
    }

    /** 请求体：**没有 correct 字段**，判定权在服务端。 */
    public record AttemptRequest(long entryId, String blockKey, String questionKey,
                                 int choice, Integer latencyMs) {
    }

    public record ConceptMasteryView(long conceptId, String name, double mastery, String tier) {
    }

    /** 我在这条里已经选过的答案。前端据此把上次的作答恢复出来，而不是每次打开都从零开始。 */
    public record AnsweredChoice(String blockKey, String questionKey, int choice) {
    }

    public record AttemptResult(boolean correct, String fingerprint,
                                List<ConceptMasteryView> concepts,
                                List<PrerequisiteDiagnosis.Suggestion> suggestions) {
    }

    @Transactional
    public AttemptResult record(long userId, AttemptRequest request) {
        if (request.entryId() <= 0 || request.blockKey() == null || request.questionKey() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少题目身份");
        }
        Entry entry = entryMapper.findPublishedById(request.entryId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "条目不存在"));
        QuestionCatalog.Question question = QuestionCatalog
                .find(entry.getId(), entry.getBody(), request.blockKey(), request.questionKey())
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT, "找不到这道题"));
        if (request.choice() < 0 || request.choice() >= question.options().size()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "选项越界");
        }
        boolean correct = request.choice() == question.answerIndex();
        Integer latency = request.latencyMs() == null ? null
                : Math.max(0, Math.min(MAX_LATENCY_MS, request.latencyMs()));
        Instant now = Instant.now();

        mapper.insertAttempt(userId, entry.getId(), question.blockKey(), question.questionKey(),
                question.fingerprint(), request.choice(), correct, latency, "server", now);
        if (!correct) {
            // 答错即回到复习队列：明天再来（REQ-009）。只影响已有学习记录的条目。
            mapper.scheduleReview(userId, entry.getId(), now.plus(1, java.time.temporal.ChronoUnit.DAYS));
        }

        List<QuestionAttemptMapper.LinkRow> links = mapper.findLinks(entry.getId(),
                question.blockKey(), question.questionKey());

        updateDifficulty(userId, entry.getId(), question, links, correct, now);
        Map<Long, String> names = new HashMap<>();
        for (QuestionAttemptMapper.ConceptRow row : mapper.findAllConcepts()) {
            names.put(row.id(), row.name());
        }
        List<ConceptMasteryView> masteryViews = updateMastery(userId, question, links, correct, names, now);
        List<PrerequisiteDiagnosis.Suggestion> suggestions = diagnose(userId, links);
        return new AttemptResult(correct, question.fingerprint(), masteryViews, suggestions);
    }

    /**
     * 题目难度：能力分取关联概念掌握度的均值；没有关联概念时按 0.5（中性）。
     *
     * <p>`shortcut: 一题多概念时用均值能力，不做逐概念难度；达到每题 4 个概念以上时升级。`
     */
    private void updateDifficulty(long userId, long entryId, QuestionCatalog.Question question,
                                  List<QuestionAttemptMapper.LinkRow> links, boolean correct,
                                  Instant now) {
        double ability;
        if (links.isEmpty()) {
            ability = ItemRating.ability(0.5);
        } else {
            double sum = 0;
            for (QuestionAttemptMapper.LinkRow link : links) {
                sum += mapper.findMasteryOf(userId, link.conceptId())
                        .map(QuestionAttemptMapper.MasteryRow::mastery)
                        .orElse(MasteryModel.PRIOR);
            }
            ability = ItemRating.ability(sum / links.size());
        }
        QuestionAttemptMapper.RatingRow row = mapper.findRating(question.fingerprint()).orElse(null);
        double rating = row == null ? ItemRating.INITIAL : row.rating();
        int attempts = row == null ? 0 : row.attempts();
        int correctCount = row == null ? 0 : row.correct();
        double next = ItemRating.updateRating(rating, correct, ability);
        mapper.upsertRating(question.fingerprint(), entryId, question.blockKey(),
                question.questionKey(), next, attempts + 1, correctCount + (correct ? 1 : 0), now);
    }

    /** 对每个关联概念各更新一次 BKT。多概念的联合概率暂不建模。 */
    private List<ConceptMasteryView> updateMastery(long userId, QuestionCatalog.Question question,
                                                   List<QuestionAttemptMapper.LinkRow> links,
                                                   boolean correct, Map<Long, String> names,
                                                   Instant now) {
        List<ConceptMasteryView> views = new ArrayList<>();
        double guess = MasteryModel.guessFor(question.options().size());
        for (QuestionAttemptMapper.LinkRow link : links) {
            QuestionAttemptMapper.MasteryRow current = mapper.findMasteryOf(userId, link.conceptId()).orElse(null);
            double prior = current == null ? MasteryModel.PRIOR : current.mastery();
            int attempts = current == null ? 0 : current.attempts();
            int correctCount = current == null ? 0 : current.correct();
            double mastery = MasteryModel.update(prior, correct, guess);
            mapper.upsertMastery(userId, link.conceptId(), mastery, attempts + 1,
                    correctCount + (correct ? 1 : 0), now);
            views.add(new ConceptMasteryView(link.conceptId(),
                    names.getOrDefault(link.conceptId(), ""),
                    mastery, MasteryModel.tier(mastery, attempts + 1)));
        }
        return views;
    }

    /** 用更新后的掌握度做前置缺口诊断；没有关联概念时返回空列表。 */
    private List<PrerequisiteDiagnosis.Suggestion> diagnose(long userId,
                                                            List<QuestionAttemptMapper.LinkRow> links) {
        if (links.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> mastery = new HashMap<>();
        for (QuestionAttemptMapper.MasteryRow row : mapper.findMastery(userId)) {
            mastery.put(row.conceptId(), row.mastery());
        }
        List<PrerequisiteDiagnosis.Edge> edges = mapper.findEdges().stream()
                .map(edge -> new PrerequisiteDiagnosis.Edge(edge.fromConceptId(), edge.toConceptId()))
                .toList();
        long failed = links.get(0).conceptId();
        return PrerequisiteDiagnosis.suggest(failed, mastery, edges,
                PrerequisiteDiagnosis.DEFAULT_MAX_DEPTH);
    }

    /**
     * 我在这一条里已经答过的题。
     *
     * <p>只认**与当前正文指纹一致**的作答：题目内容改过就不再算答过，否则旧答案会锁住一道新题、
     * 还会显示一个跟当前选项对不上的对错。
     */
    public List<AnsweredChoice> answered(long userId, long entryId) {
        Entry entry = entryMapper.findPublishedById(entryId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "条目不存在"));
        Map<String, Integer> latest = new HashMap<>();
        for (QuestionAttemptMapper.ChoiceRow row : mapper.findChoices(userId, entryId)) {
            // 最新在前：同一道题第一次出现的才是"上次选的"
            if (row.choiceIndex() != null) {
                latest.putIfAbsent(row.questionKey() + '\u0000' + row.fingerprint(), row.choiceIndex());
            }
        }
        List<AnsweredChoice> answers = new ArrayList<>();
        for (QuestionCatalog.Question question : QuestionCatalog.parse(entry.getId(), entry.getBody())) {
            Integer choice = latest.get(question.questionKey() + '\u0000' + question.fingerprint());
            if (choice != null && choice < question.options().size()) {
                answers.add(new AnsweredChoice(question.blockKey(), question.questionKey(), choice));
            }
        }
        return answers;
    }

    /**
     * 练习选题（REQ-008）：在给定概念集合里挑一道信息量最大的题。
     *
     * <p>没有候选、或概念都没有关联题目时返回空，不报错。
     */
    public java.util.Optional<PracticeSelector.Candidate> nextPractice(long userId, List<Long> conceptIds) {
        if (conceptIds == null || conceptIds.isEmpty()) {
            return java.util.Optional.empty();
        }
        // 同一道题在内容改动后可能有多条难度行，取最近更新的一条
        Map<String, QuestionAttemptMapper.CandidateRow> latest = new HashMap<>();
        for (QuestionAttemptMapper.CandidateRow row : mapper.findCandidates(conceptIds)) {
            String key = row.entryId() + "/" + row.blockKey() + "/" + row.questionKey();
            QuestionAttemptMapper.CandidateRow previous = latest.get(key);
            if (previous == null || (row.updatedAt() != null
                    && (previous.updatedAt() == null || row.updatedAt().isAfter(previous.updatedAt())))) {
                latest.put(key, row);
            }
        }
        Map<Long, Double> mastery = new HashMap<>();
        for (QuestionAttemptMapper.MasteryRow row : mapper.findMastery(userId)) {
            mastery.put(row.conceptId(), row.mastery());
        }
        double average = conceptIds.stream()
                .mapToDouble(id -> mastery.getOrDefault(id, MasteryModel.PRIOR))
                .average().orElse(MasteryModel.PRIOR);
        List<PracticeSelector.Candidate> candidates = latest.values().stream()
                .map(row -> new PracticeSelector.Candidate(row.entryId(), row.blockKey(),
                        row.questionKey(), row.fingerprint() == null ? "unknown" : row.fingerprint(),
                        row.rating() == null ? ItemRating.INITIAL : row.rating(),
                        row.attempts() == null ? 0 : row.attempts()))
                .toList();
        return PracticeSelector.choose(candidates, PracticeSelector.abilityOf(average));
    }

    /** 评估导出（REQ-012）：最近作答明细，够回放与算指标。 */
    public List<QuestionAttemptMapper.AttemptRow> exportAttempts(int limit) {
        return mapper.recentAttempts(Math.max(1, Math.min(5000, limit)));
    }

    /**
     * 复习会话用：按一次作答更新某个概念的 BKT 掌握度（WORK-034 TASK-005 冻结点）。
     *
     * <p>必须开事务：这是"读当前掌握度 → 计算 → upsert"的复合操作，`findMasteryOf` 的
     * `FOR UPDATE` 只在事务里才拿锁，否则并发交卷会各自读到同一份旧值、互相覆盖计数。
     */
    @Transactional
    public void recordMastery(long userId, long conceptId, boolean correct, int optionCount) {
        Instant now = Instant.now();
        QuestionAttemptMapper.MasteryRow current = mapper.findMasteryOf(userId, conceptId).orElse(null);
        double prior = current == null ? MasteryModel.PRIOR : current.mastery();
        int attempts = current == null ? 0 : current.attempts();
        int correctCount = current == null ? 0 : current.correct();
        double mastery = MasteryModel.update(prior, correct, MasteryModel.guessFor(optionCount));
        mapper.upsertMastery(userId, conceptId, mastery, attempts + 1,
                correctCount + (correct ? 1 : 0), now);
    }

    /** 我自己的全部掌握状态，按概念 id 升序。 */
    public List<ConceptMasteryView> mastery(long userId) {
        Map<Long, String> names = new HashMap<>();
        for (QuestionAttemptMapper.ConceptRow row : mapper.findAllConcepts()) {
            names.put(row.id(), row.name());
        }
        return mapper.findMastery(userId).stream()
                .map(row -> new ConceptMasteryView(row.conceptId(),
                        names.getOrDefault(row.conceptId(), ""),
                        row.mastery(), MasteryModel.tier(row.mastery(), row.attempts())))
                .toList();
    }

    /** 以某个概念为起点的补课建议；概念 id 非法时返回空列表而不是报错。 */
    public List<PrerequisiteDiagnosis.Suggestion> diagnosis(long userId, long conceptId) {
        Map<Long, Double> mastery = new HashMap<>();
        for (QuestionAttemptMapper.MasteryRow row : mapper.findMastery(userId)) {
            mastery.put(row.conceptId(), row.mastery());
        }
        List<PrerequisiteDiagnosis.Edge> edges = mapper.findEdges().stream()
                .map(edge -> new PrerequisiteDiagnosis.Edge(edge.fromConceptId(), edge.toConceptId()))
                .toList();
        return PrerequisiteDiagnosis.suggest(conceptId, mastery, edges,
                PrerequisiteDiagnosis.DEFAULT_MAX_DEPTH);
    }
}
