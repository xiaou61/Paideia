-- 个人知识库与笔记树（WORK-087 TASK-001）。
--
-- 把 WORK-006 的扁平笔记升级为「多知识库 + 可嵌套目录/文档树」，并补上双链关系。
-- 纪律与 V7/V8 一致：不建跨模块外键、级联删除由应用层清单负责。
--
-- 兼容取舍：note_shares 采用「新增 target_type/target_id 并回填、保留 note_id」的方式，
-- 而不是删列。原因是举报列表 NoteReportMapper.list 仍 JOIN note_shares.note_id，
-- 保留它可以让存量分享/举报链路在本次迁移里零改动通过；单篇分享后续同时写两列，
-- 整库分享只用 target_type='book' + target_id。

-- 1) notes 增加层级列与存活标记
ALTER TABLE notes
  ADD COLUMN book_id    BIGINT UNSIGNED NULL      AFTER user_id,
  ADD COLUMN kind       VARCHAR(8)      NOT NULL DEFAULT 'doc' AFTER book_id,
  ADD COLUMN parent_id  BIGINT UNSIGNED NULL      AFTER kind,
  ADD COLUMN sort_order INT             NOT NULL DEFAULT 0 AFTER parent_id,
  ADD COLUMN deleted_at DATETIME(3)     NULL      AFTER updated_at,
  ADD KEY idx_note_book_tree  (book_id, parent_id, sort_order),
  ADD KEY idx_note_user_alive (user_id, deleted_at);

-- 2) 知识库表
CREATE TABLE note_books (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  name        VARCHAR(200)    NOT NULL,
  description VARCHAR(1000)   NULL,
  sort_order  INT             NOT NULL DEFAULT 0,
  deleted_at  DATETIME(3)     NULL,
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_note_book_user (user_id, deleted_at, sort_order)
) ENGINE = InnoDB;

-- 3) 存量迁移：给每个有笔记的用户建一个默认库「我的笔记」，把现有笔记挂到该库根节点
INSERT INTO note_books (user_id, name, sort_order)
SELECT DISTINCT user_id, '我的笔记', 0 FROM notes WHERE deleted_at IS NULL;

UPDATE notes n
  JOIN note_books b ON b.user_id = n.user_id AND b.name = '我的笔记'
  SET n.book_id = b.id
  WHERE n.book_id IS NULL;

-- 4) 双链/反链关系表（保存文档时按 [[标题]] / [[id:N]] 重建）
CREATE TABLE note_links (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL,
  source_note_id BIGINT UNSIGNED NOT NULL,
  target_note_id BIGINT UNSIGNED NULL,
  target_title   VARCHAR(200)    NOT NULL,
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_note_link_source (source_note_id),
  KEY idx_note_link_target (target_note_id),
  KEY idx_note_link_user   (user_id)
) ENGINE = InnoDB;

-- 5) 中文全文检索（标题 + 正文）
ALTER TABLE notes
  ADD FULLTEXT KEY ft_note_title_body (title, body) WITH PARSER ngram;

-- 6) 分享扩展：单篇 / 整库
ALTER TABLE note_shares
  ADD COLUMN target_type VARCHAR(8)      NOT NULL DEFAULT 'note' AFTER share_id,
  ADD COLUMN target_id   BIGINT UNSIGNED NULL      AFTER target_type,
  ADD KEY idx_note_share_target (target_type, target_id);

UPDATE note_shares SET target_id = note_id WHERE target_id IS NULL;
