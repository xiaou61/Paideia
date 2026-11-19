-- 诊断报告全文索引（WORK-085，REQ-007 知识闭环）。
-- report_md 是 MEDIUMTEXT，加 ngram 全文索引，供 /knowledge/search 用 MATCH ... AGAINST 检索历史结论。
-- 中文没有空格，必须 WITH PARSER ngram；与知识库/记忆表的做法一致。
ALTER TABLE ops_diagnoses
    ADD FULLTEXT KEY ft_ops_diagnoses_report (report_md) WITH PARSER ngram;
