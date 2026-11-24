-- WORK-091：笔记文档增加可选的分类标签与难度评级。
-- 只加结构，不插业务数据；两列均允许为空，老数据与旧调用行为不变。
ALTER TABLE notes
    ADD COLUMN category VARCHAR(64) NULL COMMENT '分类标签（自由文本，≤64）' AFTER pair_key,
    ADD COLUMN level VARCHAR(16) NULL COMMENT '难度评级：入门/进阶/高级' AFTER category;
