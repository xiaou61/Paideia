-- 凭据的每日调用上限（WORK-044）。
--
-- NULL = 不限。按"调用次数"而不是积分：assistant_ask 已经走积分预留-结算，
-- 再叠一层积分配额会让两套账互相解释不清（用户 2026-10-27 裁决）。
--
-- 不需要回填：NULL 就是"不限"，与改动前的语义完全一致。
ALTER TABLE mcp_access_tokens
  ADD COLUMN daily_call_limit INT NULL COMMENT '每日调用上限；NULL = 不限' AFTER toolsets;
