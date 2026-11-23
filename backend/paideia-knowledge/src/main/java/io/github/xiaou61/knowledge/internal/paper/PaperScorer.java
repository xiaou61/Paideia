package io.github.xiaou61.knowledge.internal.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 试卷判分与成绩聚合（纯函数，WORK-083 TASK-003）。
 *
 * <p>只读 {@code paper_items} 快照判定，不碰正文、无 DB、无时钟。薄弱判据沿用
 * REQ-016：该概念答对率 &lt; 80%、或错 ≥ 2 题、或难档题全错。不出 0–100 总分。
 */
public final class PaperScorer {

    private PaperScorer() {
    }

    /**
     * 一题的判分快照。{@code conceptIds} 是**全部**关联概念：一题挂多个概念时每个概念都要记一次
     * 对错，否则其余概念拿不到 BKT/Elo 更新，薄弱识别也会漏判。
     */
    public record PaperItemSnapshot(long itemId, long entryId, String blockKey, String questionKey,
                                    String fingerprint, List<Long> conceptIds, String difficultyLabel,
                                    int answerIndex, int optionCount) {

        public PaperItemSnapshot {
            conceptIds = conceptIds == null ? List.of() : List.copyOf(conceptIds);
        }
    }

    public record ConceptStat(long conceptId, int correct, int total, boolean weak) {
    }

    public record DifficultyStat(String label, int correct, int total) {
    }

    public record ScoreResult(int correct, int total, List<ConceptStat> concepts,
                              List<DifficultyStat> difficulties, List<Long> weakConceptIds) {
    }

    public static ScoreResult score(List<PaperItemSnapshot> items, Map<Long, Integer> answersByItemId) {
        List<PaperItemSnapshot> list = items == null ? List.of() : items;
        Map<Long, Integer> answers = answersByItemId == null ? Map.of() : answersByItemId;
        int total = list.size();
        int correctAll = 0;
        Map<Long, int[]> byConcept = new LinkedHashMap<>();
        Map<String, int[]> byDifficulty = new LinkedHashMap<>();
        for (PaperItemSnapshot item : list) {
            Integer answer = answers.get(item.itemId());
            boolean ok = answer != null && answer == item.answerIndex();
            if (ok) {
                correctAll++;
            }
            String label = item.difficultyLabel() == null ? "medium" : item.difficultyLabel();
            int[] diff = byDifficulty.computeIfAbsent(label, key -> new int[2]);
            diff[1]++;
            if (ok) {
                diff[0]++;
            }
            // 一题多概念：每个概念各记一次，概念统计因此是「题目-概念」对的口径，与作答轨迹一致
            for (Long conceptId : item.conceptIds()) {
                if (conceptId == null) {
                    continue;
                }
                int[] concept = byConcept.computeIfAbsent(conceptId, key -> new int[4]);
                concept[0]++;
                if (ok) {
                    concept[1]++;
                }
                if ("hard".equals(label)) {
                    concept[2]++;
                    if (ok) {
                        concept[3]++;
                    }
                }
            }
        }

        List<ConceptStat> concepts = new ArrayList<>();
        List<Long> weak = new ArrayList<>();
        for (Map.Entry<Long, int[]> entry : byConcept.entrySet()) {
            int[] concept = entry.getValue();
            int conceptTotal = concept[0];
            int conceptCorrect = concept[1];
            int hardTotal = concept[2];
            int hardCorrect = concept[3];
            double ratio = conceptTotal == 0 ? 0 : (double) conceptCorrect / conceptTotal;
            int wrong = conceptTotal - conceptCorrect;
            boolean isWeak = ratio < 0.8 || wrong >= 2 || (hardTotal > 0 && hardCorrect == 0);
            concepts.add(new ConceptStat(entry.getKey(), conceptCorrect, conceptTotal, isWeak));
            if (isWeak) {
                weak.add(entry.getKey());
            }
        }

        List<DifficultyStat> difficulties = new ArrayList<>();
        for (String label : List.of("easy", "medium", "hard")) {
            int[] diff = byDifficulty.get(label);
            if (diff != null) {
                difficulties.add(new DifficultyStat(label, diff[0], diff[1]));
            }
        }
        return new ScoreResult(correctAll, total, List.copyOf(concepts),
                List.copyOf(difficulties), List.copyOf(weak));
    }
}
