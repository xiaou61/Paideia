package io.github.xiaou61.ai.internal.question;

import io.github.xiaou61.knowledge.QuestionLabelingPort;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 难度 AI 标注端口的 ai 侧实现（WORK-083 TASK-008）。
 *
 * <p>v1 占位：未接入模型调用（无凭据/未开启时不产生额外计费），统一返回空结果，
 * 让索引回退到规则兜底 {@code DifficultyHeuristic}（未校准新题）。后续接入模型时在此实现
 * {@link #tag} 并用 {@code RunKind} 记录消耗，端口契约不变。
 */
@Component
public class QuestionLabelingAdapter implements QuestionLabelingPort {

    @Override
    public Result tag(List<Question> questions) {
        return new Result(Map.of());
    }
}
