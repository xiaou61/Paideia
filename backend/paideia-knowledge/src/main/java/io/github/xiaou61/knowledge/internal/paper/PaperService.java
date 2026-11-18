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
        List<PaperAssembler.Candidate> candidates = new ArrayList<>();
        for (PaperMapper.CandidateRow row : rows) {
            List<Long> conceptIds = paperMapper.findConceptIds(row.entryId(), row.blockKey(), row.questionKey());
            candidates.add(new PaperAssembler.Candidate(row.entryId(), row.blockKey(), row.questionKey(),
                    row.fingerprint(), row.optionCount(), row.difficultyLabel(), conceptIds));
        }

        PaperAssembler.AssemblyResult assembled = PaperAssembler.assemble(candidates, rule);
        String title = rule.title() == null || rule.title().isBlank() ? "自测试卷" : rule.title().strip();
        Instant now = Instant.now();
        paperMapper.insertPaper(userId, title, toJson(rule), "ready", now);
        long paperId = paperMapper.lastInsertId();

        int seq = 1;
        List<PaperItemView> items = new ArrayList<>();
        for (PaperAssembler.Candidate candidate : assembled.items()) {
            QuestionIndexService.QuestionSnapshot snapshot =
                    indexService.snapshot(candidate.entryId(), candidate.questionKey());
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
        paperMapper.updateTotals(paperId, seq - 1);
        return new PaperView(paperId, title, "ready", assembled.shortfall(), List.copyOf(items),
                null, seq - 1, null);
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

        List<PaperScorer.PaperItemSnapshot> snapshots = new ArrayList<>();
        for (PaperMapper.ItemRow item : items) {
            snapshots.add(new PaperScorer.PaperItemSnapshot(item.id(), item.entryId(), item.blockKey(),
                    item.questionKey(), item.fingerprint(), item.conceptId(), item.difficultyLabel(),
                    item.answerIndexSnapshot(), parseOptions(item.optionsJson()).size()));
        }
        PaperScorer.ScoreResult score = PaperScorer.score(snapshots, picked);
        Instant now = Instant.now();
        for (int i = 0; i < items.size(); i++) {
            PaperMapper.ItemRow item = items.get(i);
            PaperScorer.PaperItemSnapshot snap = snapshots.get(i);
            Integer choice = picked.get(item.id());
            boolean correct = choice != null && choice == item.answerIndexSnapshot();
            paperMapper.insertAnswer(paperId, userId, item.id(), choice, correct, now);
            List<Long> conceptIds = item.conceptId() == null ? List.of() : List.of(item.conceptId());
            questionAttemptService.recordJudged(userId, item.entryId(), item.blockKey(),
                    item.questionKey(), item.fingerprint(), snap.optionCount(), correct, conceptIds, null, now);
        }
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
        List<PaperScorer.PaperItemSnapshot> snapshots = new ArrayList<>();
        for (PaperMapper.ItemRow item : items) {
            snapshots.add(new PaperScorer.PaperItemSnapshot(item.id(), item.entryId(), item.blockKey(),
                    item.questionKey(), item.fingerprint(), item.conceptId(), item.difficultyLabel(),
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
