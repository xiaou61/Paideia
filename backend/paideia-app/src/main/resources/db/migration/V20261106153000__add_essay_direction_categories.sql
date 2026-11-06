-- 八股文专区扩三个方向（2026-11-06）。
--
-- 背景：抓取原来只跑一个牛客合集（jobId=639，Java 工程师）。牛客的面经合集本身就按岗位分，
-- 实测可用的另外三个合集：前端=644、测试/测开=680、数据/大数据=684。每个方向落一个分类，
-- 方向由**源**决定，不经模型判主题（决策模型的主题标签只有 java/database/career/other）。
--
-- **为什么显式写 id（901~903）**：本表同时被 devdata 种子使用（种子占低位 id、夹具占 900+），
-- 用自增会在全新库上拿到 1，与 devdata/V951 的 `id=1` 撞主键——V24 就是这么踩的
-- （`MigrationSeedOverlapTest` 现在会挡住这种写法）。900 已被 devdata/V949 用给 essay-java，
-- 故从 901 起。
--
-- **为什么用 INSERT ... SELECT ... WHERE NOT EXISTS 而不是 INSERT IGNORE**：后者会吞掉所有错误
-- （名字超长这类真问题也一起吞）。前者在分类已存在时插入 0 行，语义明确，且可重复执行。
--
-- 三个分类建出来后**为空**：第一次抓取（部署后 5 分钟）才会填内容。若某个方向当天没有帖子过
-- 技术过滤，它会在学习者导航里短暂显示为空分类——这是已知代价，好过完全没有入口。

INSERT INTO knowledge_categories (id, parent_id, name, slug, sort_order)
SELECT 901, NULL, '八股文 · 前端面经', 'essay-frontend', 51
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM knowledge_categories WHERE slug = 'essay-frontend');

INSERT INTO knowledge_categories (id, parent_id, name, slug, sort_order)
SELECT 902, NULL, '八股文 · 测试面经', 'essay-testing', 52
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM knowledge_categories WHERE slug = 'essay-testing');

INSERT INTO knowledge_categories (id, parent_id, name, slug, sort_order)
SELECT 903, NULL, '八股文 · 数据面经', 'essay-data', 53
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM knowledge_categories WHERE slug = 'essay-data');

-- 判重改为**跨分类**按指纹查（EntryMapper.findIdByFingerprint 不再带 category_id）。
-- V24 建的 idx_knowledge_entries_fingerprint 是 (category_id, fingerprint)，按最左前缀
-- 服务不了"只有 fingerprint"的查询，这里补一个单列索引。
CREATE INDEX idx_knowledge_entries_fingerprint_any
  ON knowledge_entries (fingerprint);
