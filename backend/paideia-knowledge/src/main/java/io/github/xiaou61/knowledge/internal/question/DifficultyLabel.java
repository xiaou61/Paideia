package io.github.xiaou61.knowledge.internal.question;

/**
 * 题目难度标签（WORK-083）。
 *
 * <p>纯函数：Elo 校准够次数（{@value #CALIBRATED_ATTEMPTS}）才按评分分桶，
 * 否则一律 {@code medium}——宁可说"没校准"，也不拿一次作答当难度。
 * 未校准新题的初始难度由 {@link DifficultyHeuristic} 规则兜底；AI 标注优先于本类。
 */
public final class DifficultyLabel {

    public static final String EASY = "easy";
    public static final String MEDIUM = "medium";
    public static final String HARD = "hard";

    /** 与 {@code ItemRating.CALIBRATED_ATTEMPTS} 同口径：够 5 次才敢分桶。 */
    static final int CALIBRATED_ATTEMPTS = 5;
    static final double EASY_MAX = 1100.0;
    static final double HARD_MIN = 1300.0;

    private DifficultyLabel() {
    }

    public static String estimate(Double rating, int attempts) {
        if (rating == null || attempts < CALIBRATED_ATTEMPTS) {
            return MEDIUM;
        }
        if (rating <= EASY_MAX) {
            return EASY;
        }
        if (rating >= HARD_MIN) {
            return HARD;
        }
        return MEDIUM;
    }
}
