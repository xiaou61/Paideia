-- 刷题与错题本按「用户 + 题目身份」聚合最近一次作答与作答次数（question_attempts 原先只有
-- (user_id, created_at) 与 fingerprint 两个索引），这些查询只能按 user_id 范围扫描该用户的
-- 全部作答。题库与作答量增长后，一次刷题请求会明显变慢。
CREATE INDEX idx_attempt_user_question ON question_attempts (user_id, entry_id, block_key, question_key);
