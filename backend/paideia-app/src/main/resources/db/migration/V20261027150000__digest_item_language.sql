-- GitHub 条目按语言分组（2026-10-27）。
--
-- `language` 一直被抓下来，但只拼进了 `meta` 文本（"Python · 252000★"），
-- 于是前端只能当装饰显示，没法按语言分组。存成独立列之后：
--   ① GitHub 组可以按语言分类，不再是一坨混排；
--   ② `meta` 回归"一条给人看的副信息"，不再兼职承载可查询的维度。
--
-- 可空：新闻 / HN / 社区三类没有语言概念，保持 NULL。
-- 只建结构、不插业务数据（MigrationSeedOverlapTest 守着）。

ALTER TABLE digest_items
  ADD COLUMN language VARCHAR(32) NULL AFTER meta;
