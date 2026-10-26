-- GitHub 日榜/周榜的真实增量与跨天重复（WORK-041）。
--
-- 1) 指纹唯一键原来不含日期，导致同一个仓库在整个平台历史上只能出现一次；
--    热榜的「连续在榜」因此存不下来。改成 (edition_date, fingerprint)：
--    同一天仍只一条，不同天允许重复。
-- 2) GitHub 条目要同时展示「昨日新增」与「本周新增」；其它分组保持 NULL。
--
-- 只建结构、不插业务数据（MigrationSeedOverlapTest 守着）。

ALTER TABLE digest_items
  DROP INDEX uk_digest_items_fingerprint,
  ADD UNIQUE KEY uk_digest_items_edition_fingerprint (edition_date, fingerprint);

ALTER TABLE digest_items
  ADD COLUMN stars_day  INT NULL AFTER heat,
  ADD COLUMN stars_week INT NULL AFTER stars_day;
