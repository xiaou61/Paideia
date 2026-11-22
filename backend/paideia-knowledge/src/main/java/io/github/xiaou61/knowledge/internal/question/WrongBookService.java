package io.github.xiaou61.knowledge.internal.question;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 错题本（WORK-083 TASK-005）：只返回每个题目**最新一次作答为错**的题。
 *
 * <p>题面取正文（经 question_index 快照），**不含答案**；可按分类/概念过滤；归属取令牌。
 */
@Service
public class WrongBookService {

    private final QuestionAttemptMapper mapper;
    private final QuestionIndexService indexService;

    WrongBookService(QuestionAttemptMapper mapper, QuestionIndexService indexService) {
        this.mapper = mapper;
        this.indexService = indexService;
    }

    public record WrongQuestionView(String blockKey, String questionKey, long entryId,
                                    String stem, List<String> options, String difficultyLabel,
                                    List<Long> conceptIds, List<String> conceptNames) {
    }

    public List<WrongQuestionView> list(long userId, Long categoryId, Long conceptId, Integer limit) {
        int n = limit == null || limit <= 0 ? 20 : Math.max(1, Math.min(200, limit));
        Map<Long, String> names = new HashMap<>();
        for (QuestionAttemptMapper.ConceptRow row : mapper.findAllConcepts()) {
            names.put(row.id(), row.name());
        }
        List<QuestionAttemptMapper.WrongRow> rows = mapper.findWrongLatest(userId, categoryId, conceptId, n);
        // 按条目分组：一个条目的正文只解析一次，概念关联一条 SQL 取完（原先每题各一次）
        Map<Long, Map<String, QuestionIndexService.QuestionSnapshot>> snapshots = snapshotsByEntry(rows);
        Map<String, List<QuestionAttemptMapper.KeyedLinkRow>> links = linksByQuestion(rows);
        List<WrongQuestionView> result = new ArrayList<>();
        for (QuestionAttemptMapper.WrongRow row : rows) {
            QuestionIndexService.QuestionSnapshot snapshot =
                    snapshots.getOrDefault(row.entryId(), Map.of()).get(row.questionKey());
            if (snapshot == null) {
                continue;
            }
            List<Long> conceptIds = new ArrayList<>();
            List<String> conceptNames = new ArrayList<>();
            for (QuestionAttemptMapper.KeyedLinkRow link :
                    links.getOrDefault(identity(row.entryId(), row.blockKey(), row.questionKey()), List.of())) {
                conceptIds.add(link.conceptId());
                conceptNames.add(names.getOrDefault(link.conceptId(), ""));
            }
            result.add(new WrongQuestionView(row.blockKey(), row.questionKey(), row.entryId(),
                    snapshot.stem(), snapshot.options(), row.difficultyLabel(),
                    conceptIds, conceptNames));
        }
        return result;
    }

    private Map<Long, Map<String, QuestionIndexService.QuestionSnapshot>> snapshotsByEntry(
            List<QuestionAttemptMapper.WrongRow> rows) {
        Map<Long, Map<String, QuestionIndexService.QuestionSnapshot>> grouped = new HashMap<>();
        for (QuestionAttemptMapper.WrongRow row : rows) {
            grouped.computeIfAbsent(row.entryId(), entryId -> {
                Map<String, QuestionIndexService.QuestionSnapshot> byKey = new HashMap<>();
                for (QuestionIndexService.QuestionSnapshot snapshot : indexService.snapshots(entryId)) {
                    byKey.put(snapshot.questionKey(), snapshot);
                }
                return byKey;
            });
        }
        return grouped;
    }

    private Map<String, List<QuestionAttemptMapper.KeyedLinkRow>> linksByQuestion(
            List<QuestionAttemptMapper.WrongRow> rows) {
        List<Long> entryIds = rows.stream().map(QuestionAttemptMapper.WrongRow::entryId).distinct().toList();
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<QuestionAttemptMapper.KeyedLinkRow>> grouped = new HashMap<>();
        for (QuestionAttemptMapper.KeyedLinkRow link : mapper.findLinksForEntries(entryIds)) {
            grouped.computeIfAbsent(identity(link.entryId(), link.blockKey(), link.questionKey()),
                    ignored -> new ArrayList<>()).add(link);
        }
        return grouped;
    }

    private static String identity(long entryId, String blockKey, String questionKey) {
        return entryId + "/" + blockKey + "/" + questionKey;
    }
}
