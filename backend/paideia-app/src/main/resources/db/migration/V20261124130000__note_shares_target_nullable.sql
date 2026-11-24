-- WORK-087 TASK-007：整库分享需要 note_shares 能表达"分享的是一个知识库"。
-- V20261124120000 已加 target_type('note'|'book') / target_id 并回填单篇分享；
-- 这里把历史遗留的 note_id 放宽为可空——整库分享没有单篇 note_id。
-- 保留 note_id 列不删：NoteReportMapper.list 仍以它关联笔记标题（单篇分享报告用）。
ALTER TABLE note_shares MODIFY COLUMN note_id BIGINT UNSIGNED NULL;
