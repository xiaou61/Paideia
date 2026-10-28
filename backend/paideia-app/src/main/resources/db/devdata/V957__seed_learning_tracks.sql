-- 学习轨道的 e2e / 集成测试种子（WORK-047 补）。
--
-- **补这一条的原因**：`/learn` 的 e2e 一直在等 `learn-track-*` 出现，但库里从来没有轨道种子——
-- 它此前能过，是因为跑在了被别的会话导入过内容的共用库上。换成每次独占的新库之后，
-- 那六条用例全部超时。这不是产品缺陷，是**测试一直没被真正执行**。
--
-- 只放一条理论轨道、三关，挂在 V951 已有的条目上（id 1..3）。线上轨道由导入脚本建立。
--
-- **只用 V22 时就有的列**：`mode` / `theory_track_slug` / `level` 是后续版本加的，
-- 而 devdata 的 V957 排在那些时间戳迁移**之前**执行，写它们会报 Unknown column。
-- 缺的列交给各自的默认值。
INSERT INTO learning_tracks (id, slug, name, summary, kind, sort_order, created_at, updated_at)
VALUES
  (9501, 'backend-basics', '后端基础', '测试种子：三关，覆盖地图、难度与进度三种状态。', 'reading', 10,
   '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO learning_track_stages (track_id, entry_id, seq, chapter, requires_entry_id, created_at)
VALUES
  (9501, 1, 1, '入门篇', NULL, '2026-01-01 00:00:00.000'),
  (9501, 2, 2, '入门篇', 1, '2026-01-01 00:00:00.000'),
  (9501, 3, 3, '进阶篇', 2, '2026-01-01 00:00:00.000');
