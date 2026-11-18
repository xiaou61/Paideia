package io.github.xiaou61.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 难度 AI 标注端口（WORK-083 TASK-008）。
 *
 * <p>放在模块对外暴露的根包：ai 侧适配器要实现它，内部包（internal）不对外暴露，
 * 否则会触发模块边界校验。可选增强：实现方对一批题给出 {questionKey: easy|medium|hard}；
 * 没有实现、或实现返回空时，索引回退到规则难度估算。端口失败不得中断索引。
 */
public interface QuestionLabelingPort {

    record Question(String questionKey, String stem, List<String> options) {
    }

    record Result(Map<String, String> difficultyByQuestionKey) {
    }

    Result tag(List<Question> questions);
}
