-- WORK-092 TASK-006 / REQ-005：知识导入的领取令牌与超时恢复。
--
-- 背景：旧实现 markProcessing 只把 status 置 processing，结果写回又不校验领取者。进程在"领取后、
-- 写回前"被杀，这条任务会永远停在 processing（没有任何路径把它改动），用户看到"一直在导入"。
-- 更糟的是，若同一个 id 被另一路重新处理，旧 worker 回来的 updateResult 会直接覆盖新结果。
--
-- 这里补三列：
--   claim_token      本次领取的唯一令牌；写结果必须带上它（fencing），过期的旧写入会被丢弃
--   processing_since 领取时间；超过阈值未完成即视为死掉，可被 requeueStale 回收为 pending
--   attempts         已尝试次数；每次领取 +1，便于观测反复失败的任务
ALTER TABLE ai_knowledge_imports
    ADD COLUMN claim_token CHAR(36) NULL,
    ADD COLUMN processing_since DATETIME(3) NULL,
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD KEY idx_ai_knowledge_import_processing (status, processing_since);
