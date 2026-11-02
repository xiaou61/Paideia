package io.github.xiaou61.knowledge.internal.plan;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「今天要做的事」（WORK-053 阶段二）。
 *
 * <p><b>写入只由用户触发</b>：助手只给候选（提示词里明确"你无法加入计划"），
 * 真正落库发生在用户点「采用」或手动添加之后（REQ-007）。
 *
 * <p>日期由客户端给：服务端不知道用户在哪个时区，"今天"是浏览器算出来的那一天。
 */
@Service
public class LearningPlanService {

    private static final int MAX_GOAL = 200;
    private static final int MAX_REASON = 300;

    private final LearningPlanMapper mapper;

    LearningPlanService(LearningPlanMapper mapper) {
        this.mapper = mapper;
    }

    public record View(long id, LocalDate date, String goal, String reason, boolean done) {
        static View of(PlanRow row) {
            return new View(row.getId(), row.getPlanDate(), row.getGoal(), row.getReason(),
                    row.getDoneAt() != null);
        }
    }

    public record NewItem(LocalDate date, String goal, String reason) {
    }

    public List<View> list(long userId, LocalDate date) {
        return mapper.findByDate(userId, date).stream().map(View::of).toList();
    }

    @Transactional
    public View add(long userId, NewItem item) {
        if (item.date() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "计划项缺少日期");
        }
        String goal = item.goal() == null ? "" : item.goal().trim();
        if (goal.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "计划项不能为空");
        }
        if (goal.length() > MAX_GOAL) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "计划项最多 " + MAX_GOAL + " 个字");
        }
        String reason = item.reason() == null ? "" : item.reason().trim();
        if (reason.length() > MAX_REASON) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "理由最多 " + MAX_REASON + " 个字");
        }
        PlanRow row = new PlanRow();
        mapper.insert(userId, item.date(), goal, reason, Instant.now(), row);
        return new View(row.getId(), item.date(), goal, reason, false);
    }

    /** 勾选/取消。`done` 为真时记完成时间，为假时清空。 */
    @Transactional
    public void setDone(long userId, long id, boolean done) {
        Instant now = Instant.now();
        if (mapper.setDone(userId, id, done ? now : null, now) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "没有这条计划项");
        }
    }

    @Transactional
    public void remove(long userId, long id) {
        if (mapper.delete(userId, id) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "没有这条计划项");
        }
    }
}
