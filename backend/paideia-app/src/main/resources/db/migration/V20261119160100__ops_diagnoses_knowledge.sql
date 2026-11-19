-- 诊断用到了哪些历史复盘（WORK-086）。留给"报告里说'参考了 #12'到底是哪一份"这类追问：
-- 只存 id 列表（逗号分隔、≤255 字、超出截断），不复制复盘正文——
-- 复盘会被编辑，复制的正文一定比原件旧。
ALTER TABLE ops_diagnoses
    ADD COLUMN knowledge_ids VARCHAR(255) NULL COMMENT '本次注入的历史复盘 id，逗号分隔' AFTER report_md;
