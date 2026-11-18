package io.github.xiaou61.knowledge.internal.question;

import java.util.List;

/**
 * 题目难度规则兜底（WORK-083）。
 *
 * <p>新题还没有 Elo 校准（作答不足 {@code DifficultyLabel.CALIBRATED_ATTEMPTS} 次）、
 * 也还没有 AI 标注时，用它给一个可解释的初始难度，免得全库落成 {@code medium}、
 * 让组卷的「难度分布」形同虚设。纯函数、无 DB、无时钟：只看题干与选项。
 *
 * <p>信号都很弱，所以规则保守（只做「明显偏易/偏难」的判断，其余保持 medium）：
 * <ul>
 *   <li>只有 2 个选项（判断题 / 二选一）→ 偏易；</li>
 *   <li>4 个及以上选项 → 偏难；</li>
 *   <li>题干较长（≥ {@value #LONG_STEM} 字，多为情境题 / 代码题）→ 偏难；</li>
 *   <li>否定式提问（「不正确 / 错误的是 / 不属于」等）→ 偏难。</li>
 * </ul>
 */
public final class DifficultyHeuristic {

    static final int LONG_STEM = 50;

    private static final String[] NEGATION_MARKERS = {
            "不正确", "错误", "不属于", "除外", "不对", "不包括", "不恰当"
    };

    private DifficultyHeuristic() {
    }

    public static String estimate(String stem, List<String> options) {
        int optionCount = options == null ? 0 : options.size();
        if (optionCount < 2) {
            return DifficultyLabel.MEDIUM;
        }
        int score = 0;
        if (optionCount == 2) {
            score -= 1;
        } else if (optionCount >= 4) {
            score += 1;
        }
        String text = stem == null ? "" : stem.strip();
        if (text.length() >= LONG_STEM) {
            score += 1;
        }
        for (String marker : NEGATION_MARKERS) {
            if (text.contains(marker)) {
                score += 1;
                break;
            }
        }
        if (score <= -1) {
            return DifficultyLabel.EASY;
        }
        if (score >= 1) {
            return DifficultyLabel.HARD;
        }
        return DifficultyLabel.MEDIUM;
    }
}
