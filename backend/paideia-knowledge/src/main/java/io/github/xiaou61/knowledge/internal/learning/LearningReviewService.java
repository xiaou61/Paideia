package io.github.xiaou61.knowledge.internal.learning;

import io.github.xiaou61.knowledge.ReviewQuestionPort;
import io.github.xiaou61.knowledge.internal.question.QuestionAttemptService;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 到期列表与复习会话（WORK-034）。
 *
 * <p>S1 的到期列表只读；S2 的会话创建/交卷都在这里。**判定全在服务端**：
 * 答案只存在 `items_json`，响应永远不带 `answerIndex`。
 */
@Service
public class LearningReviewService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CONCEPTS = 5;
    private static final int SESSION_MINUTES = 30;

    private final LearningReviewMapper mapper;
    private final ReviewQuestionPort generator;
    private final QuestionAttemptService mastery;

    LearningReviewService(LearningReviewMapper mapper, ReviewQuestionPort generator,
                          QuestionAttemptService mastery) {
        this.mapper = mapper;
        this.generator = generator;
        this.mastery = mastery;
    }

    public record ReviewDueView(long entryId, String title, Long conceptId, String conceptName,
                                Instant reviewDueAt, int stage) {
    }

    public record DueList(List<ReviewDueView> items, int total) {
    }

    public record StartSession(long sessionId, List<ItemView> items, Instant expiresAt,
                               List<Fallback> fallback) {
    }

    public record ItemView(long itemId, long conceptId, String stem, List<String> options) {
    }

    public record Fallback(long entryId, String title) {
    }

    public record AnswerInput(long itemId, int choice) {
    }

    /** 待写入的掌握度证据：等抢到交卷幂等闸之后再落库。 */
    private record PendingMastery(long conceptId, boolean correct, int optionCount) {
    }

    public record ResultView(long itemId, long conceptId, boolean correct, String explanation) {
    }

    public record ScheduleView(long entryId, int stage, Instant reviewDueAt) {
    }

    public record SubmitResult(List<ResultView> results, List<ScheduleView> schedule) {
    }

    /** 会话内存储的题（含答案，只在服务端存在）。 */
    record StoredItem(long itemId, long entryId, long conceptId, String stem, List<String> options,
                      int answerIndex, String explanation, String fingerprint) {
    }

    public DueList due(long userId, Instant now) {
        List<LearningReviewMapper.DueRow> rows = mapper.findDue(userId, now);
        if (rows.isEmpty()) {
            return new DueList(List.of(), 0);
        }
        Map<Long, LearningReviewMapper.ConceptRow> firstConcept = new HashMap<>();
        List<Long> entryIds = rows.stream().map(LearningReviewMapper.DueRow::entryId).toList();
        for (LearningReviewMapper.ConceptRow row : mapper.findConceptNames(entryIds)) {
            firstConcept.putIfAbsent(row.entryId(), row);
        }
        List<ReviewDueView> items = new ArrayList<>(rows.size());
        for (LearningReviewMapper.DueRow row : rows) {
            LearningReviewMapper.ConceptRow concept = firstConcept.get(row.entryId());
            items.add(new ReviewDueView(row.entryId(), row.title(),
                    concept == null ? null : concept.conceptId(),
                    concept == null ? null : concept.conceptName(),
                    row.reviewDueAt(), row.reviewStage()));
        }
        return new DueList(items, items.size());
    }

    /**
     * 开始复习会话：有未过期未完成的会话就原样返回（幂等）；否则一次生成。
     *
     * <p>没有到期项或生成失败时返回 `fallback`（承载条目列表），不建会话、不报错。
     */
    @Transactional
    public StartSession start(long userId, Instant now) {
        var open = mapper.findOpen(userId, now);
        if (open.isPresent()) {
            return view(open.get());
        }
        List<LearningReviewMapper.DueRow> due = mapper.findDue(userId, now);
        if (due.isEmpty()) {
            return new StartSession(0, List.of(), null, List.of());
        }
        List<LearningReviewMapper.DueRow> chosen = due.stream().limit(MAX_CONCEPTS).toList();
        Map<Long, LearningReviewMapper.ConceptRow> firstConcepts = new HashMap<>();
        for (LearningReviewMapper.ConceptRow row : mapper.findConceptNames(
                chosen.stream().map(LearningReviewMapper.DueRow::entryId).toList())) {
            firstConcepts.putIfAbsent(row.entryId(), row);
        }
        List<Long> conceptIds = new ArrayList<>();
        Map<Long, String> carrierTitles = new HashMap<>();
        // 概念 → 这次实际到期的条目。排期必须落在它上面，而不是「概念的第一条载体」——
        // 同一个概念可能挂在多篇条目上，用第一条会排到用户没在复习的那篇（2026-10-23 真连发现）。
        Map<Long, Long> entryByConcept = new HashMap<>();
        for (LearningReviewMapper.DueRow row : chosen) {
            LearningReviewMapper.ConceptRow concept = firstConcepts.get(row.entryId());
            if (concept != null && !conceptIds.contains(concept.conceptId())) {
                conceptIds.add(concept.conceptId());
                carrierTitles.put(concept.conceptId(), row.title());
                entryByConcept.put(concept.conceptId(), row.entryId());
            }
        }
        List<Fallback> fallback = chosen.stream()
                .map(row -> new Fallback(row.entryId(), row.title())).toList();
        if (conceptIds.isEmpty()) {
            return new StartSession(0, List.of(), null, fallback);
        }
        Map<Long, LearningReviewMapper.ConceptTextRow> texts = new HashMap<>();
        for (LearningReviewMapper.ConceptTextRow row : mapper.findConceptTexts(conceptIds)) {
            texts.put(row.conceptId(), row);
        }
        List<ReviewQuestionPort.Concept> concepts = conceptIds.stream()
                .map(id -> new ReviewQuestionPort.Concept(id,
                        texts.get(id) == null ? "" : texts.get(id).name(),
                        texts.get(id) == null ? null : texts.get(id).summary(),
                        carrierTitles.get(id)))
                .toList();
        var generated = generator.generate(userId, concepts, 1, previousFingerprints(userId));
        if (generated.isEmpty() || generated.get().questions().isEmpty()) {
            return new StartSession(0, List.of(), null, fallback);
        }
        List<StoredItem> items = new ArrayList<>();
        int index = 0;
        for (ReviewQuestionPort.Question question : generated.get().questions()) {
            Long entryId = entryByConcept.get(question.conceptId());
            if (entryId == null) {
                continue;
            }
            items.add(new StoredItem(index++, entryId, question.conceptId(), question.stem(),
                    question.options(), question.answerIndex(), question.explanation(),
                    question.fingerprint()));
        }
        if (items.isEmpty()) {
            return new StartSession(0, List.of(), null, fallback);
        }
        Instant expiresAt = now.plusSeconds(SESSION_MINUTES * 60L);
        mapper.insertSession(userId, JSON.writeValueAsString(conceptIds),
                JSON.writeValueAsString(items), now, expiresAt);
        long id = mapper.lastInsertId();
        return new StartSession(id,
                items.stream().map(item -> new ItemView(item.itemId(), item.conceptId(), item.stem(),
                        item.options())).toList(), expiresAt, List.of());
    }

    /** 交卷：服务端判定、写排期、更新掌握度；重复交卷返回 409。 */
    @Transactional
    public SubmitResult submit(long userId, long sessionId, List<AnswerInput> answers, Instant now) {
        var row = mapper.findSession(sessionId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "复习会话不存在"));
        if (row.completedAt() != null) {
            throw new BizException(ErrorCode.CONFLICT, "这次复习已经交过了");
        }
        if (row.expiresAt().isBefore(now)) {
            throw new BizException(ErrorCode.CONFLICT, "复习会话已过期，请重新开始");
        }
        List<StoredItem> items = List.of(JSON.readValue(row.itemsJson(), StoredItem[].class));
        if (answers == null || answers.size() != items.size()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "每道题都要作答");
        }
        Map<Long, Integer> choices = new HashMap<>();
        for (AnswerInput answer : answers) {
            if (choices.put(answer.itemId(), answer.choice()) != null) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "同一道题重复作答");
            }
        }
        List<ResultView> results = new ArrayList<>();
        List<PendingMastery> pendingMastery = new ArrayList<>();
        Map<Long, Boolean> allCorrectByConcept = new HashMap<>();
        Map<Long, Boolean> allCorrectByEntry = new HashMap<>();
        for (StoredItem item : items) {
            Integer choice = choices.get(item.itemId());
            if (choice == null) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少作答");
            }
            if (choice < 0 || choice >= item.options().size()) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "选项越界");
            }
            boolean correct = choice == item.answerIndex();
            results.add(new ResultView(item.itemId(), item.conceptId(), correct, item.explanation()));
            allCorrectByConcept.merge(item.conceptId(), correct, (left, right) -> left && right);
            allCorrectByEntry.merge(item.entryId(), correct, (left, right) -> left && right);
            // 掌握度先收集、**等抢到幂等闸再写**：并发交卷时两个请求都会走到这里，
            // 若在此处就累加，失败的那个虽然回滚了，但"先加后判"的顺序会把重复计算写进
            // 另一条依赖读-改-写的路径。把写放到闸之后，只有真正完成交卷的那次才记。
            pendingMastery.add(new PendingMastery(item.conceptId(), correct, item.options().size()));
        }
        if (mapper.completeSession(sessionId, userId, JSON.writeValueAsString(answers), now) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "这次复习已经交过了");
        }
        for (PendingMastery pending : pendingMastery) {
            mastery.recordMastery(userId, pending.conceptId(), pending.correct(), pending.optionCount());
        }
        List<ScheduleView> schedule = new ArrayList<>();
        for (var entry : allCorrectByEntry.entrySet()) {
            int stage = mapper.progressStage(userId, entry.getKey()).orElse(0);
            var outcome = LearningProgressRules.afterReview(stage, entry.getValue(), now);
            mapper.updateSchedule(userId, entry.getKey(), outcome.stage(), outcome.dueAt());
            schedule.add(new ScheduleView(entry.getKey(), outcome.stage(), outcome.dueAt()));
        }
        return new SubmitResult(results, schedule);
    }

    private Set<String> previousFingerprints(long userId) {
        Set<String> fingerprints = new HashSet<>();
        for (String itemsJson : mapper.recentItems(userId, 3)) {
            try {
                for (StoredItem item : JSON.readValue(itemsJson, StoredItem[].class)) {
                    fingerprints.add(item.fingerprint());
                }
            } catch (RuntimeException ignored) {
                // 旧数据坏掉不该让"开始复习"失败
            }
        }
        return fingerprints;
    }

    private StartSession view(LearningReviewMapper.SessionRow row) {
        List<StoredItem> items = List.of(JSON.readValue(row.itemsJson(), StoredItem[].class));
        return new StartSession(row.id(),
                items.stream().map(item -> new ItemView(item.itemId(), item.conceptId(), item.stem(),
                        item.options())).toList(), row.expiresAt(), List.of());
    }
}