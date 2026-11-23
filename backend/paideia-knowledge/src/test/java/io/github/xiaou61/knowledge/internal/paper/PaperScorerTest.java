package io.github.xiaou61.knowledge.internal.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaperScorerTest {

    private static PaperScorer.PaperItemSnapshot item(long id, Long conceptId, String difficulty,
                                                      int answerIndex) {
        return new PaperScorer.PaperItemSnapshot(id, 1L, "b", "b#" + id, "fp" + id,
                conceptId == null ? List.of() : List.of(conceptId), difficulty, answerIndex, 2);
    }

    private static PaperScorer.PaperItemSnapshot multiConceptItem(long id, List<Long> conceptIds,
                                                                  String difficulty, int answerIndex) {
        return new PaperScorer.PaperItemSnapshot(id, 1L, "b", "b#" + id, "fp" + id, conceptIds,
                difficulty, answerIndex, 2);
    }

    @Test
    void everyLinkedConceptIsScored() {
        // 一题挂两个概念：两个概念都要记对错，否则第二个概念拿不到薄弱判定
        List<PaperScorer.PaperItemSnapshot> items = List.of(
                multiConceptItem(1, List.of(10L, 20L), "medium", 0),
                multiConceptItem(2, List.of(20L), "medium", 0));
        Map<Long, Integer> answers = Map.of(1L, 1, 2L, 1);

        PaperScorer.ScoreResult result = PaperScorer.score(items, answers);

        assertEquals(2, result.concepts().size());
        assertEquals(1, result.concepts().get(0).total());
        assertEquals(2, result.concepts().get(1).total());
        assertTrue(result.weakConceptIds().containsAll(List.of(10L, 20L)));
    }

    @Test
    void allCorrectHasNoWeakConcept() {
        List<PaperScorer.PaperItemSnapshot> items = List.of(
                item(1, 10L, "easy", 0),
                item(2, 10L, "medium", 0),
                item(3, 10L, "hard", 0));
        Map<Long, Integer> answers = Map.of(1L, 0, 2L, 0, 3L, 0);

        PaperScorer.ScoreResult result = PaperScorer.score(items, answers);

        assertEquals(3, result.correct());
        assertEquals(3, result.total());
        assertEquals(1, result.concepts().size());
        assertFalse(result.concepts().get(0).weak());
        assertTrue(result.weakConceptIds().isEmpty());
    }

    @Test
    void twoWrongMarksConceptWeak() {
        List<PaperScorer.PaperItemSnapshot> items = List.of(
                item(1, 10L, "medium", 0),
                item(2, 10L, "medium", 0),
                item(3, 10L, "medium", 0));
        Map<Long, Integer> answers = Map.of(1L, 1, 2L, 1, 3L, 0);

        PaperScorer.ScoreResult result = PaperScorer.score(items, answers);

        assertEquals(1, result.correct());
        assertTrue(result.concepts().get(0).weak());
        assertEquals(List.of(10L), result.weakConceptIds());
    }

    @Test
    void hardAllWrongMarksConceptWeakEvenIfRatioHigh() {
        List<PaperScorer.PaperItemSnapshot> items = List.of(
                item(1, 10L, "easy", 0),
                item(2, 10L, "easy", 0),
                item(3, 10L, "easy", 0),
                item(4, 10L, "hard", 0));
        Map<Long, Integer> answers = Map.of(1L, 0, 2L, 0, 3L, 0, 4L, 1);

        PaperScorer.ScoreResult result = PaperScorer.score(items, answers);

        assertEquals(3, result.correct());
        assertTrue(result.concepts().get(0).weak());
    }

    @Test
    void ratioBoundaryAtEightyPercentIsNotWeak() {
        List<PaperScorer.PaperItemSnapshot> items = List.of(
                item(1, 10L, "medium", 0),
                item(2, 10L, "medium", 0),
                item(3, 10L, "medium", 0),
                item(4, 10L, "medium", 0),
                item(5, 10L, "medium", 0));
        Map<Long, Integer> answers = Map.of(1L, 0, 2L, 0, 3L, 0, 4L, 0, 5L, 1);

        PaperScorer.ScoreResult result = PaperScorer.score(items, answers);

        assertEquals(4, result.correct());
        assertFalse(result.concepts().get(0).weak());
    }

    @Test
    void difficultyBreakdownCounts() {
        List<PaperScorer.PaperItemSnapshot> items = List.of(
                item(1, 10L, "easy", 0),
                item(2, 10L, "hard", 0));
        Map<Long, Integer> answers = Map.of(1L, 0, 2L, 1);

        PaperScorer.ScoreResult result = PaperScorer.score(items, answers);

        assertEquals(2, result.difficulties().size());
        assertEquals("easy", result.difficulties().get(0).label());
        assertEquals(1, result.difficulties().get(0).correct());
        assertEquals("hard", result.difficulties().get(1).label());
        assertEquals(0, result.difficulties().get(1).correct());
    }
}
