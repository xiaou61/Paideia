package io.github.xiaou61.knowledge.internal.plan;

import java.time.Instant;
import java.time.LocalDate;

/** `learning_plan_items` 的一行。setter 供 MyBatis 与回填主键用。 */
public class PlanRow {

    private Long id;
    private Long userId;
    private LocalDate planDate;
    private String goal;
    private String reason;
    private Instant doneAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDate getPlanDate() {
        return planDate;
    }

    public void setPlanDate(LocalDate planDate) {
        this.planDate = planDate;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    public void setDoneAt(Instant doneAt) {
        this.doneAt = doneAt;
    }
}
