package io.github.xiaou61.knowledge.internal.question;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DifficultyLabelTest {

    @Test
    void uncalibratedIsMedium() {
        assertEquals("medium", DifficultyLabel.estimate(null, 0));
        assertEquals("medium", DifficultyLabel.estimate(1200.0, 4));
    }

    @Test
    void bucketsByEloWhenCalibrated() {
        assertEquals("easy", DifficultyLabel.estimate(1050.0, 5));
        assertEquals("medium", DifficultyLabel.estimate(1200.0, 6));
        assertEquals("hard", DifficultyLabel.estimate(1350.0, 5));
    }

    @Test
    void boundariesAreInclusive() {
        assertEquals("easy", DifficultyLabel.estimate(1100.0, 5));
        assertEquals("hard", DifficultyLabel.estimate(1300.0, 5));
    }

    @Test
    void portAbsentFallsBackMedium() {
        // 无 AI 标注端口时，未校准题一律回退 medium（不因缺失端口报错）
        assertEquals("medium", DifficultyLabel.estimate(null, 0));
    }
}
