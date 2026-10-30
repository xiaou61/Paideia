-- 复习到期的测试种子（WORK-058 AC-004 的 e2e 需要一条"今天到期"的复习项）。
--
-- 为什么必须种：到期列表来自 `learning_progress.review_due_at <= now`，而自评排期按
-- 1/3/7/21 天往后推——空库上永远没有到期项，那条 e2e 只能永远跳过，等于没验。
--
-- 只造一条：seed-learner 对条目 1（V951 的第一篇），已通过、复习档 1 档，
-- 到期时间固定在 1 小时前，所以每轮跑起来都是"已到期"。
--
-- 线上不加载本目录（`classpath:db/devdata` 只在 e2e 的 locations 里），不会污染真实账号。
INSERT INTO learning_progress
  (user_id, entry_id, tour_step, challenges_json, quizzes_json, status, passed_at,
   review_stage, review_due_at, created_at, updated_at)
VALUES
  ((SELECT id FROM users WHERE username = 'seed-learner'), 1, 0, '{}', '{}', 'passed',
   '2026-01-01 00:00:00.000', 1, DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 HOUR),
   '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');