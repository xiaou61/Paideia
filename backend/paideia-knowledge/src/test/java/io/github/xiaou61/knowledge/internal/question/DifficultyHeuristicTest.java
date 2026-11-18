package io.github.xiaou61.knowledge.internal.question;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DifficultyHeuristicTest {

    @Test
    void twoOptionsAreEasy() {
        assertEquals("easy", DifficultyHeuristic.estimate("两数相加用哪个运算符？", List.of("+", "-")));
    }

    @Test
    void threeOptionsStayMedium() {
        assertEquals("medium", DifficultyHeuristic.estimate("下面哪个是关键字？", List.of("class", "kitty", "colour")));
    }

    @Test
    void fourOptionsAreHard() {
        assertEquals("hard", DifficultyHeuristic.estimate("选一个", List.of("a", "b", "c", "d")));
    }

    @Test
    void longStemIsHard() {
        String stem = "在一个典型的电商系统中，订单创建后会经过库存扣减、支付确认、发货等多个环节，"
                + "请问下列哪一个环节最先发生？";
        assertEquals("hard", DifficultyHeuristic.estimate(stem, List.of("库存扣减", "支付确认", "发货")));
    }

    @Test
    void negationIsHard() {
        assertEquals("hard", DifficultyHeuristic.estimate("下列关于 JVM 的说法错误的是？", List.of("a", "b", "c")));
    }

    @Test
    void fewerThanTwoOptionsFallsBackToMedium() {
        assertEquals("medium", DifficultyHeuristic.estimate("题干", List.of("only")));
        assertEquals("medium", DifficultyHeuristic.estimate(null, null));
    }
}
