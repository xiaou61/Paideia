-- 书籍详解（WORK-046）的 e2e / 集成测试种子。
--
-- 只放最小结构：一个根分类 + 一本书 + 三章，让 /books 与 /books/:slug 有数据可断言。
-- **线上内容不从这里来**：真实书籍写在 content/reading/books-*，由导入脚本上线；
-- 这里只是测试夹具，正文是占位文字。
--
-- 总览里特意放了一张 `code-map` 与一段引用块：窄屏下"概念图不把页面撑宽"要有东西可验
-- （AC-009），而引用块就是出处头在正文里的形态。
--
-- id 从 9201 起：避开 V951 的 1..4、V953 的 10 与 V949 的 900。
INSERT INTO knowledge_categories (id, parent_id, name, slug, sort_order, created_at, updated_at)
VALUES
  (9201, NULL, '书籍详解', 'books', 200, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'),
  (9202, 9201, '人月神话 (The Mythical Man-Month)', 'books-man-month', 200, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

-- 父分类**不带 line_kind**：带了它就会以"一条学习线"的身份出现在 /learn。
UPDATE knowledge_categories SET line_kind = NULL WHERE id IN (9201, 9202);

INSERT INTO knowledge_entries (id, category_id, title, body, status, published_at, sort_order, created_at, updated_at)
VALUES
  (9201, 9202, '人月神话 · 总览',
   CONCAT(
     '> 测试种子出处头：Frederick P. Brooks, Jr. · 1975 · 中译 汪颖',
     CHAR(10), CHAR(10),
     '测试种子：总览。真实正文由 content/reading/books-man-month 导入。',
     CHAR(10), CHAR(10),
     '```code-map', CHAR(10),
     '{"title":"种子论证链","nodes":[{"id":"a","label":"甲","layer":0,"order":0},{"id":"b","label":"乙","layer":1,"order":0}],"edges":[{"from":"a","to":"b","label":"所以"}]}',
     CHAR(10), '```'
   ),
   'published', '2026-01-01 00:00:00.000', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'),
  (9202, 9202, '人月神话：为什么加人反而更慢',
   CONCAT('测试种子：第一章。', CHAR(10), CHAR(10), '真实正文由内容源导入。'),
   'published', '2026-01-01 00:00:01.000', 1, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'),
  (9203, 9202, '概念完整性：为什么一个架构师胜过五个聪明人',
   CONCAT('测试种子：第二章。', CHAR(10), CHAR(10), '真实正文由内容源导入。'),
   'published', '2026-01-01 00:00:02.000', 2, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');
