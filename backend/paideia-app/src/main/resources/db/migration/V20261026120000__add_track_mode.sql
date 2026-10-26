-- 从零开始分层（WORK-038）：轨道增加"理论 / 实战"与配对引用。
--
-- mode 表达目标（读完能解释 / 做完能验收），与既有的 kind（course/reading，表达载体形态）
-- 是两个正交轴，所以另起一列而不是改 kind 的语义。
--
-- theory_track_slug 只用于"建议先看 X"的提示，不构成门禁；不加外键是因为它允许
-- 指向尚未导入的线（内容先写、后导入）。
ALTER TABLE learning_tracks
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'theory'
        COMMENT 'theory=读完能解释；practice=做完能验收',
    ADD COLUMN theory_track_slug VARCHAR(64) NULL
        COMMENT '实战轨道指向它假定的理论轨道；只用于提示，不构成限制';

CREATE INDEX idx_learning_tracks_mode ON learning_tracks (mode);
