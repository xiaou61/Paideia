package io.github.xiaou61.knowledge.internal.question;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PracticeFilterTest {

    @Test
    void onlyUndoneExcludesAnswered() {
        assertTrue(PracticeService.passes(null, 0, false, true));
        assertFalse(PracticeService.passes(Boolean.TRUE, 1, false, true));
        assertFalse(PracticeService.passes(Boolean.FALSE, 1, false, true));
    }

    @Test
    void onlyWrongKeepsOnlyLatestWrong() {
        assertFalse(PracticeService.passes(null, 0, true, false));
        assertTrue(PracticeService.passes(Boolean.FALSE, 1, true, false));
        assertFalse(PracticeService.passes(Boolean.TRUE, 2, true, false));
    }

    @Test
    void intersectionOfOnlyWrongAndOnlyUndoneIsEmpty() {
        // "未做"与"最新一次答错"互斥：有作答即不满足未做，无作答即不满足错题
        assertFalse(PracticeService.passes(null, 0, true, true));
        assertFalse(PracticeService.passes(Boolean.FALSE, 1, true, true));
    }

    @Test
    void noFilterAcceptsEverything() {
        assertTrue(PracticeService.passes(null, 0, false, false));
        assertTrue(PracticeService.passes(Boolean.TRUE, 3, false, false));
    }
}
