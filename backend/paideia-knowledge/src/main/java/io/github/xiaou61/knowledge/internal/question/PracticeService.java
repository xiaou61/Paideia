package io.github.xiaou61.knowledge.internal.question;

import io.github.xiaou61.knowledge.internal.question.QuestionIndexService.QuestionSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 刷题会话（WORK-083 TASK-004）：按分类/概念/难度筛选、只刷错题、只刷未做。
 *
 * <p>只出题面，**不含答案**；作答走既有 `POST /api/v1/learning/attempts`（服务端判定）。
 * 刷题不生成试卷行，不写 `papers`。
 */
@Service
public class PracticeService {

    private static final Logger log = LoggerFactory.getLogger(PracticeService.class);

    private final QuestionAttemptMapper mapper;
    private final QuestionIndexService indexService;

    PracticeService(QuestionAttemptMapper mapper, QuestionIndexService indexService) {
        this.mapper = mapper;
        this.indexService = indexService;
    }

    /** 过滤条件；布尔/整数用包装类型，缺省（未传）即视为 false / 20。 */
    public record PracticeFilter(Long categoryId, List<Long> conceptIds, String difficultyLabel,
                                 Boolean onlyWrong, Boolean onlyUndone, Integer limit) {
    }

    public record PracticeQuestion(String blockKey, String questionKey, long entryId,
                                   String stem, List<String> options, String difficultyLabel) {
    }

    public List<PracticeQuestion> listQuestions(long userId, PracticeFilter filter) {
        PracticeFilter f = filter == null
                ? new PracticeFilter(null, null, null, false, false, 20) : filter;
        boolean onlyWrong = Boolean.TRUE.equals(f.onlyWrong());
        boolean onlyUndone = Boolean.TRUE.equals(f.onlyUndone());
        int limit = Math.max(1, Math.min(100, f.limit() == null || f.limit() <= 0 ? 20 : f.limit()));

        List<QuestionAttemptMapper.PracticeCandidateRow> rows = mapper.findPracticeCandidates(
                userId, f.categoryId(), f.conceptIds(), f.difficultyLabel(),
                onlyWrong, onlyUndone, limit);

        // 快照按条目分组：同一 entry 的正文只解析一次（原先每题一次 DB + 整篇正文解析）
        Map<Long, Map<String, QuestionSnapshot>> snapshotsByEntry = new HashMap<>();
        List<PracticeQuestion> result = new ArrayList<>();
        for (QuestionAttemptMapper.PracticeCandidateRow row : rows) {
            // 过滤只由 SQL 做（LIMIT 在 SQL 里，取回后再丢行会让学生拿到少于 limit 的题）。
            // passes() 只作为语义参照：两边不一致时记日志，而不是静默少出题。
            if (!passes(row.lastCorrect(), row.attemptCount(), onlyWrong, onlyUndone)) {
                log.warn("刷题筛选与 SQL 不一致：entry={} block={} question={} lastCorrect={} attemptCount={}"
                                + " onlyWrong={} onlyUndone={}",
                        row.entryId(), row.blockKey(), row.questionKey(), row.lastCorrect(),
                        row.attemptCount(), onlyWrong, onlyUndone);
            }
            Map<String, QuestionSnapshot> byKey = snapshotsByEntry.computeIfAbsent(row.entryId(),
                    entryId -> {
                        Map<String, QuestionSnapshot> grouped = new HashMap<>();
                        for (QuestionSnapshot snapshot : indexService.snapshots(entryId)) {
                            grouped.put(snapshot.questionKey(), snapshot);
                        }
                        return grouped;
                    });
            QuestionSnapshot snapshot = byKey.get(row.questionKey());
            if (snapshot == null) {
                continue;
            }
            result.add(new PracticeQuestion(row.blockKey(), row.questionKey(), row.entryId(),
                    snapshot.stem(), snapshot.options(), row.difficultyLabel()));
        }
        return result;
    }

    /** 过滤语义（纯，可单测）：只刷未做=无作答；只刷错题=最新一次作答为错；两者取交集。 */
    static boolean passes(Boolean lastCorrect, int attemptCount, boolean onlyWrong, boolean onlyUndone) {
        if (onlyUndone && attemptCount > 0) {
            return false;
        }
        if (onlyWrong && !(attemptCount > 0 && Boolean.FALSE.equals(lastCorrect))) {
            return false;
        }
        return true;
    }
}
