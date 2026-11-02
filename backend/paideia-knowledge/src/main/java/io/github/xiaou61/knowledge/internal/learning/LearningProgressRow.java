package io.github.xiaou61.knowledge.internal.learning;

import java.time.Instant;

/**
 * 一行学习记录（WORK-020）。
 *
 * <p>与项目其它实体一样是可变类：MyBatis 用字段注入。{@code challengesJson} / {@code quizzesJson}
 * 保持数据库里的原始字符串，解析在服务层做——mapper 里做 JSON 解析会把 SQL 与业务混在一起。
 */
public class LearningProgressRow {

    private Long id;
    private Long userId;
    private Long entryId;
    private int tourStep;
    private String challengesJson;
    private String quizzesJson;
    /** in_progress | passed。只由服务端按分数算。 */
    private String status;
    private Instant passedAt;
    private int reviewStage;
    private Instant reviewDueAt;
    private Instant createdAt;
    private Instant updatedAt;
    /** 列表查询时 JOIN 出来的标题；其它查询不填。 */
    private String title;
    /**
     * 列表查询时 JOIN 出来的线标记（`course` / `reading` / …）；其它查询不填。
     *
     * <p>跟着标题一起取回来，是因为原先每行都要为它单发一次
     * {@code entryMapper.findLineKindOfEntry}：列表上限提到 2000 之后，一次
     * `/learn/tracks` 最多会多打两千次库（2026-11-02 审查抓到的 N+1）。
     */
    private String lineKind;

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

    public Long getEntryId() {
        return entryId;
    }

    public void setEntryId(Long entryId) {
        this.entryId = entryId;
    }

    public int getTourStep() {
        return tourStep;
    }

    public void setTourStep(int tourStep) {
        this.tourStep = tourStep;
    }

    public String getChallengesJson() {
        return challengesJson;
    }

    public void setChallengesJson(String challengesJson) {
        this.challengesJson = challengesJson;
    }

    public String getQuizzesJson() {
        return quizzesJson;
    }

    public void setQuizzesJson(String quizzesJson) {
        this.quizzesJson = quizzesJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getPassedAt() {
        return passedAt;
    }

    public void setPassedAt(Instant passedAt) {
        this.passedAt = passedAt;
    }

    public int getReviewStage() {
        return reviewStage;
    }

    public void setReviewStage(int reviewStage) {
        this.reviewStage = reviewStage;
    }

    public Instant getReviewDueAt() {
        return reviewDueAt;
    }

    public void setReviewDueAt(Instant reviewDueAt) {
        this.reviewDueAt = reviewDueAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getLineKind() {
        return lineKind;
    }

    public void setLineKind(String lineKind) {
        this.lineKind = lineKind;
    }
}
