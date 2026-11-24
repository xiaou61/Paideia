-- 给笔记加「教程 / 查漏补缺」配对能力。
-- doc_type: tutorial / gap_fill / NULL(普通文档)；pair_key: 同一知识库内的学习单元标识。
-- 只建结构，不写业务数据。
ALTER TABLE notes
    ADD COLUMN doc_type VARCHAR(16) NULL AFTER kind,
    ADD COLUMN pair_key VARCHAR(64) NULL AFTER doc_type,
    ADD KEY idx_note_pair (user_id, pair_key);
