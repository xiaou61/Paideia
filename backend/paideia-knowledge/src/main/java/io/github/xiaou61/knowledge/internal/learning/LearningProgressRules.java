package io.github.xiaou61.knowledge.internal.learning;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过判定与复习排期的**纯函数**（WORK-020）。
 *
 * <p>单独拎出来是为了能不看数据库地测边界：0 分、刚好 80%、缺一类记录、复习阶梯到顶。
 * 通过判定**只在服务端做**——客户端只上报分数，不发 `passed`。
 */
public final class LearningProgressRules {

    /** 一个块的得分。`total` 是题数，不是分值。 */
    public record BlockScore(int score, int total) {
    }

    /** 通过门槛：每一项都至少 80%。整数比较，不用浮点。 */
    private static final int PASS_NUMERATOR = 8;
    private static final int PASS_DENOMINATOR = 10;

    /** 复习阶梯：通过后 1 / 3 / 7 / 21 天。到顶就不再增长。 */
    private static final int[] INTERVAL_DAYS = {1, 3, 7, 21};

    private LearningProgressRules() {
    }

    /**
     * 这一站算不算学会。
     *
     * <p>三条同时成立：有挑战记录、有小测记录、每一条都 ≥ 80%。**没有 total 的记录视为不通过**
     * ——空块（total=0）既不能算对也不能算 100%。
     */
    /**
     * 这一关该按哪套规则判（WORK-038）。
     *
     * <ul>
     *   <li>`COURSE`：既有课程语义——动手题与小测都非空且各自 ≥80%；</li>
     *   <li>`THEORY`：理论线——只认小测（阅读线没有动手题，按旧规则永远过不了）；</li>
     *   <li>`PRACTICE`：实战线——只认可验收的块（动手题）。</li>
     * </ul>
     */
    public enum Rule {
        COURSE, THEORY, PRACTICE
    }

    /**
     * 一条 entry 属于多条轨道时取**最严**的那套规则：COURSE > PRACTICE > THEORY。
     *
     * <p>不属于任何轨道时返回 COURSE——保持既有语义，不给未接轨道的条目放宽。
     */
    public static Rule resolveRule(java.util.Collection<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Rule.COURSE;
        }
        if (rules.contains(Rule.COURSE)) {
            return Rule.COURSE;
        }
        return rules.contains(Rule.PRACTICE) ? Rule.PRACTICE : Rule.THEORY;
    }

    /** 既有课程规则：动手题与小测都要有，且各自 ≥80%。 */
    public static boolean passed(Map<String, BlockScore> challenges, Map<String, BlockScore> quizzes) {
        return passed(Rule.COURSE, challenges, quizzes);
    }

    /** 按规则判定。理论只看小测，实战只看动手题，课程两者都要。 */
    public static boolean passed(Rule rule, Map<String, BlockScore> challenges,
                                 Map<String, BlockScore> quizzes) {
        return switch (rule) {
            case COURSE -> !challenges.isEmpty() && !quizzes.isEmpty()
                    && allStrong(challenges) && allStrong(quizzes);
            case THEORY -> !quizzes.isEmpty() && allStrong(quizzes);
            case PRACTICE -> !challenges.isEmpty() && allStrong(challenges);
        };
    }

    private static boolean allStrong(Map<String, BlockScore> scores) {
        for (BlockScore score : scores.values()) {
            if (score.total() <= 0) {
                return false;
            }
            if ((long) score.score() * PASS_DENOMINATOR < (long) score.total() * PASS_NUMERATOR) {
                return false;
            }
        }
        return true;
    }

    /** 合并分数：同一块多次提交取**更高分**，重做一次手滑不会把已通过的降级。 */
    public static Map<String, BlockScore> merge(Map<String, BlockScore> previous, Map<String, BlockScore> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return previous;
        }
        Map<String, BlockScore> merged = new LinkedHashMap<>(previous);
        incoming.forEach((id, next) -> merged.merge(id, next,
                (old, candidate) -> candidate.total() == 0 || old.total() == 0
                        ? candidate
                        : candidate.score() * old.total() >= old.score() * candidate.total() ? candidate : old));
        return merged;
    }

    /** 再次通过时前进一档；到顶停在最后一档。 */
    public static int nextStage(int current) {
        return Math.min(Math.max(current, 0) + 1, INTERVAL_DAYS.length - 1);
    }

    /** 某一档的间隔天数。越界按边界处理，不抛错（脏数据不该让读路径失败）。 */
    public static int intervalDays(int stage) {
        int index = Math.min(Math.max(stage, 0), INTERVAL_DAYS.length - 1);
        return INTERVAL_DAYS[index];
    }

    public static Instant dueAt(Instant from, int stage) {
        return from.plus(Duration.ofDays(intervalDays(stage)));
    }

    /** 复习交卷后的新排期。答错档位不变、明天再来；全对才前进一档。 */
    public record ReviewOutcome(int stage, java.time.Instant dueAt) {
    }

    public static ReviewOutcome afterReview(int currentStage, boolean allCorrect, java.time.Instant now) {
        if (!allCorrect) {
            return new ReviewOutcome(Math.max(currentStage, 0), now.plus(Duration.ofDays(1)));
        }
        int stage = nextStage(currentStage);
        return new ReviewOutcome(stage, dueAt(now, stage));
    }

    /** 复习阶梯的总档数，供测试与文档引用。 */
    public static int stageCount() {
        return INTERVAL_DAYS.length;
    }
}
