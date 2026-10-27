-- 凭据的按天用量（WORK-044）。
--
-- 为什么按 (token_id, day) 一行而不是在 mcp_access_tokens 上加两个计数列：
--   1. 每日配额要问"今天的计数"，单行累计值必须靠 updated_at 判断跨天，跨天那一刻有竞态；
--      按天分行后跨天天然是新行。
--   2. mcp_access_tokens 每次认证都要读，加高频写的计数列会让它成为热点
--      （PatService 已经在为 last_used_at 做 60 秒节流，就是这个原因）。
--
-- 历史累计 = SUM(calls) GROUP BY token_id，不需要额外列。
-- day 是 UTC 日期，与应用内时间一律 UTC 的约定一致。
--
-- 无保留策略：每凭据每天一行，20 个凭据一年约 7300 行。要清理时删 day < now-90d，
-- 但那会让"累计调用"变小，所以现在不清理。
CREATE TABLE mcp_token_usage (
  token_id   BIGINT UNSIGNED NOT NULL,
  day        DATE            NOT NULL,
  calls      INT UNSIGNED    NOT NULL DEFAULT 0,
  errors     INT UNSIGNED    NOT NULL DEFAULT 0,
  updated_at DATETIME(3)     NOT NULL,
  PRIMARY KEY (token_id, day)
) ENGINE = InnoDB;
