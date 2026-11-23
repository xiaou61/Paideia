package io.github.xiaou61.knowledge.internal.paper;

import io.github.xiaou61.knowledge.internal.question.QuestionAttemptService;
import io.github.xiaou61.knowledge.internal.question.QuestionIndexService;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 试卷服务（WORK-083 TASK-002/003）：组卷、列卷、取卷、交卷判分、成绩、删卷。
 *
 * <p>建卷时把题干/选项/答案**快照**进 `paper_items`（试卷是历史）；判分只读快照，
 * 正文事后被改也不影响已交卷的卷子。卷面/成绩响应**不含** answerIndex 或解析。
 */
@Service
public class PaperService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PaperMapper paperMapper;
    private final QuestionIndexService indexService;
    private final QuestionAttemptService questionAttemptService;

    PaperService(PaperMapper paperMapper, QuestionIndexService indexService,
                 QuestionAttemptService questionAttemptService) {
        this.paperMapper = paperMapper;
        this.indexService = indexService;
        this.questionAttemptService = questionAttemptService;
    }

    public record PaperItemView(long itemId, int seq, String stem, List<String> options,
                                String difficultyLabel, Long conceptId) {
    }

    public record PaperView(long id, String title, String status, int shortfall,
                            List<PaperItemView> items, Integer correctCount, Integer totalCount,
                            Instant submittedAt) {
    }

    public record PaperSummary(long id, String title, String status, Integer totalCount,
                               Instant createdAt) {
    }

    public record ResultView(long id, String title, int correct, int total,
                             List<PaperScorer.ConceptStat> concepts,
                             List<PaperScorer.DifficultyStat> difficulties,
                             List<Long> weakConceptIds) {
    }

    @Transactional
    public PaperView create(long userId, PaperAssembler.PaperRule rule) {
        if (rule == null || rule.count() <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "题量必须大于 0");
        }
        List<PaperMapper.CandidateRow> rows = paperMapper.findCandidates(rule.categoryId(), rule.conceptIds());
        Map<String, List<Long>> conceptsByKey = conceptIdsByKey(rows);
        List<PaperAssembler.Candidate> candidates = new ArrayList<>();
        for (PaperMapper.CandidateRow row : rows) {
            candidates.add(new PaperAssembler.Candidate(row.entryId(), row.blockKey(), row.questionKey(),
                    row.fingerprint(), row.optionCount(), row.difficultyLabel(),
                    conceptsByKey.getOrDefault(questionKey(row.entryId(), row.blockKey(), row.questionKey()),
                            List.of())));
        }

        PaperAssembler.AssemblyResult assembled = PaperAssembler.assemble(candidates, rule);
        String title = rule.title() == null || rule.title().isBlank() ? "自测试卷" : rule.title().strip();
        Instant now = Instant.now();
        paperMapper.insertPaper(userId, title, toJson(rule), "ready", now);
        long paperId = paperMapper.lastInsertId();

        // 快照按条目分组，一个条目只查一次、只解析一次正文（原先每个题目都要一次 DB + 整篇解析）
        Map<Long, Map<String, QuestionIndexService.QuestionSnapshot>> snapshotsByEntry =
                snapshotsByEntry(assembled.items());
        int seq = 1;
        List<PaperItemView> items = new ArrayList<>();
        for (PaperAssembler.Candidate candidate : assembled.items()) {
            QuestionIndexService.QuestionSnapshot snapshot = snapshotsByEntry
                    .getOrDefault(candidate.entryId(), Map.of()).get(candidate.questionKey());
            if (snapshot == null) {
                continue;
            }
            Long conceptId = candidate.conceptIds() == null || candidate.conceptIds().isEmpty()
                    ? null : candidate.conceptIds().get(0);
            paperMapper.insertItem(paperId, userId, seq, candidate.entryId(), candidate.blockKey(),
                    candidate.questionKey(), candidate.fingerprint(), conceptId, candidate.difficultyLabel(),
                    snapshot.stem(), toJson(snapshot.options()), snapshot.answerIndex(), null);
            long itemId = paperMapper.lastInsertId();
            items.add(new PaperItemView(itemId, seq, snapshot.stem(), snapshot.options(),
                    candidate.difficultyLabel(), conceptId));
            seq++;
        }
        // 快照取不到的题不会落库：只报配额不足的 shortfall 会让前端看到「不缺题但总题数变少」。
        // 所以这里按实际落库数补齐 shortfall（含"全取不到"时 shortfall=全部），把缺题如实交给前端提示，
        // 而不是抛错——题库为空时组一张 0 题的卷至少还能进作答页看到"少 N 题"，比 400 更可解释。
        int stored = seq - 1;
        int shortfall = assembled.shortfall() + (assembled.items().size() - stored);
        paperMapper.updateTotals(paperId, stored);
        return new PaperView(paperId, title, "ready", shortfall, List.copyOf(items),
                null, stored, null);
    }

    public List<PaperSummary> list(long userId) {
        return paperMapper.listPapers(userId).stream()
                .map(row -> new PaperSummary(row.id(), row.title(), row.status(), row.totalCount(),
                        row.createdAt()))
                .toList();
    }

    public PaperView get(long userId, long paperId) {
        PaperMapper.PaperRow paper = paperMapper.findPaper(paperId, userId);
        if (paper == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "试卷不存在");
        }
        List<PaperItemView> items = new ArrayList<>();
        for (PaperMapper.ItemRow item : paperMapper.findItems(paperId)) {
            items.add(new PaperItemView(item.id(), item.seq(), item.stemSnapshot(), parseOptions(item.optionsJson()),
                    item.difficultyLabel(), item.conceptId()));
        }
        return new PaperView(paper.id(), paper.title(), paper.status(), 0, List.copyOf(items),
                paper.correctCount(), paper.totalCount(), paper.submittedAt());
    }

    /**
     * 交卷：服务端按快照判分，落 paper_answers，并逐题走既有 recordJudged（作答+Elo+BKT）。
     * 未交卷状态才可交；重复交卷 409。
     */
    @Transactional
    public ResultView submit(long userId, long paperId, Map<Long, Integer> answers) {
        PaperMapper.PaperRow paper = paperMapper.findPaper(paperId, userId);
        if (paper == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "试卷不存在");
        }
        if ("submitted".equals(paper.status())) {
            throw new BizException(ErrorCode.CONFLICT, "试卷已交卷");
        }
        List<PaperMapper.ItemRow> items = paperMapper.findItems(paperId);
        Map<Long, Integer> picked = answers == null ? Map.of() : answers;

        // 一题多概念：题目的全部关联概念都在 question_concepts 里，批量取一次即可，
        // 不必为 paper_items 只存一个 concept_id 而丢掉其余概念的 BKT/Elo 更新。
        Map<String, List<Long>> conceptsByQuestion = conceptsByQuestion(items);
        List<PaperScorer.PaperItemSnapshot> snapshots = new ArrayList<>();
        Map<Long, Integer> optionCounts = new HashMap<>();
        for (PaperMapper.ItemRow item : items) {
            int optionCount = parseOptions(item.optionsJson()).size();
            optionCounts.put(item.id(), optionCount);
            List<Long> conceptIds = conceptsByQuestion.get(
                    questionKey(item.entryId(), item.blockKey(), item.questionKey()));
            if (conceptIds == null || conceptIds.isEmpty()) {
                conceptIds = item.conceptId() == null ? List.of() : List.of(item.conceptId());
            }
            snapshots.add(new PaperScorer.PaperItemSnapshot(item.id(), item.entryId(), item.blockKey(),
                    item.questionKey(), item.fingerprint(), conceptIds, item.difficultyLabel(),
                    item.answerIndexSnapshot(), optionCount));
        }
        // 入口校验：作答项必须属于本卷，选项下标必须在范围内（对照 QuestionAttemptService 的 400）
        for (Map.Entry<Long, Integer> answer : picked.entrySet()) {
            Integer optionCount = optionCounts.get(answer.getKey());
            if (optionCount == null) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "作答项不属于本卷");
            }
            Integer choice = answer.getValue();
            if (choice != null && (choice < 0 || choice >= optionCount)) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "选项下标越界");
            }
        }
        PaperScorer.ScoreResult score = PaperScorer.score(snapshots, picked);
        Instant now = Instant.now();
        List<QuestionAttemptService.JudgedAttempt> judged = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PaperMapper.ItemRow item = items.get(i);
            PaperScorer.PaperItemSnapshot snap = snapshots.get(i);
            Integer choice = picked.get(item.id());
            if (choice == null) {
                // 未作答：不落 paper_answers（choice_index NOT NULL），也不计入学习侧数据，
                // 否则「没点选」的题会进错题本并污染 Elo/BKT 与复习排期
                continue;
            }
            boolean correct = choice == item.answerIndexSnapshot();
            paperMapper.insertAnswer(paperId, userId, item.id(), choice, correct, now);
            judged.add(new QuestionAttemptService.JudgedAttempt(item.entryId(), item.blockKey(),
                    item.questionKey(), item.fingerprint(), snap.optionCount(), correct,
                    snap.conceptIds(), null));
        }
        // 整卷一次落作答：逐题调用会每题重复全表读概念名、并做一份没人用的补课诊断
        questionAttemptService.recordJudgedBatch(userId, judged, now);
        paperMapper.markSubmitted(paperId, "submitted", score.correct(), score.total(), now);
        return new ResultView(paperId, paper.title(), score.correct(), score.total(),
                score.concepts(), score.difficulties(), score.weakConceptIds());
    }

    public ResultView result(long userId, long paperId) {
        PaperMapper.PaperRow paper = paperMapper.findPaper(paperId, userId);
        if (paper == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "试卷不存在");
        }
        if (!"submitted".equals(paper.status())) {
            throw new BizException(ErrorCode.CONFLICT, "试卷尚未交卷");
        }
        List<PaperMapper.ItemRow> items = paperMapper.findItems(paperId);
        Map<String, List<Long>> conceptsByQuestion = conceptsByQuestion(items);
        List<PaperScorer.PaperItemSnapshot> snapshots = new ArrayList<>();
        for (PaperMapper.ItemRow item : items) {
            List<Long> conceptIds = conceptsByQuestion.get(
                    questionKey(item.entryId(), item.blockKey(), item.questionKey()));
            if (conceptIds == null || conceptIds.isEmpty()) {
                conceptIds = item.conceptId() == null ? List.of() : List.of(item.conceptId());
            }
            snapshots.add(new PaperScorer.PaperItemSnapshot(item.id(), item.entryId(), item.blockKey(),
                    item.questionKey(), item.fingerprint(), conceptIds, item.difficultyLabel(),
                    item.answerIndexSnapshot(), parseOptions(item.optionsJson()).size()));
        }
        Map<Long, Integer> picked = new HashMap<>();
        for (PaperMapper.AnswerRow answer : paperMapper.findAnswers(paperId)) {
            if (answer.choiceIndex() != null) {
                picked.put(answer.itemId(), answer.choiceIndex());
            }
        }
        PaperScorer.ScoreResult score = PaperScorer.score(snapshots, picked);
        return new ResultView(paperId, paper.title(), score.correct(), score.total(),
                score.concepts(), score.difficulties(), score.weakConceptIds());
    }

    @Transactional
    public void delete(long userId, long paperId) {
        if (paperMapper.findPaper(paperId, userId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "试卷不存在");
        }
        paperMapper.deleteAnswers(paperId);
        paperMapper.deleteItems(paperId);
        paperMapper.deletePaper(paperId, userId);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("序列化失败", failure);
        }
    }

    /** 题目身份：{@code questionKey} 只在条目内唯一，跨条目必须带 entryId + blockKey。 */
    private static String questionKey(long entryId, String blockKey, String questionKey) {
        return entryId + "/" + blockKey + "/" + questionKey;
    }

    /** 一次查出这批条目下的全部题目—概念关联，避免逐候选一次 SQL。 */
    private Map<String, List<Long>> conceptIdsByKey(List<PaperMapper.CandidateRow> rows) {
        List<Long> entryIds = rows.stream().map(PaperMapper.CandidateRow::entryId).distinct().toList();
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Long>> grouped = new HashMap<>();
        for (PaperMapper.ConceptLinkRow link : paperMapper.findConceptLinks(entryIds)) {
            grouped.computeIfAbsent(questionKey(link.entryId(), link.blockKey(), link.questionKey()),
                    ignored -> new ArrayList<>()).add(link.conceptId());
        }
        return grouped;
    }

    /** 已落库试卷的题目—概念关联（交卷与查成绩都要用，按题目身份分组）。 */
    private Map<String, List<Long>> conceptsByQuestion(List<PaperMapper.ItemRow> items) {
        List<Long> entryIds = items.stream().map(PaperMapper.ItemRow::entryId).distinct().toList();
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Long>> grouped = new HashMap<>();
        for (PaperMapper.ConceptLinkRow link : paperMapper.findConceptLinks(entryIds)) {
            grouped.computeIfAbsent(questionKey(link.entryId(), link.blockKey(), link.questionKey()),
                    ignored -> new ArrayList<>()).add(link.conceptId());
        }
        return grouped;
    }

    /** 按条目分组取快照：同一 entry 的正文只解析一次。 */
    private Map<Long, Map<String, QuestionIndexService.QuestionSnapshot>> snapshotsByEntry(
            List<PaperAssembler.Candidate> selected) {
        Map<Long, Map<String, QuestionIndexService.QuestionSnapshot>> grouped = new HashMap<>();
        for (PaperAssembler.Candidate candidate : selected) {
            grouped.computeIfAbsent(candidate.entryId(), entryId -> {
                Map<String, QuestionIndexService.QuestionSnapshot> byKey = new HashMap<>();
                for (QuestionIndexService.QuestionSnapshot snapshot : indexService.snapshots(entryId)) {
                    byKey.put(snapshot.questionKey(), snapshot);
                }
                return byKey;
            });
        }
        return grouped;
    }

    private static List<String> parseOptions(String json) {
        List<String> options = new ArrayList<>();
        try {
            JsonNode node = JSON.readTree(json);
            if (node != null && node.isArray()) {
                node.forEach(item -> options.add(item.asText("")));
            }
        } catch (Exception ignored) {
            // 快照坏了就返回空选项，不抛；卷面仍可显示题干
        }
        return options;
    }
}
