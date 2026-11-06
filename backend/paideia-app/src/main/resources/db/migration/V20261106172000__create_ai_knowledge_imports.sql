-- 文档导入任务（WORK-076）。
--
-- 管理员上传 PDF 后登记一条任务，由定时 relay 异步处理：读附件 → 抽文本 → 蒸馏 → 落条目草稿。
-- 异步而不是在请求里同步跑，是因为多文件/文件夹 + 模型调用可达分钟级，且失败要能重试。
-- 只存任务状态与结果引用；不存文档正文（正文在 knowledge_entries）。
CREATE TABLE ai_knowledge_imports (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  file_id            CHAR(36)        NOT NULL,
  relative_path      VARCHAR(512)    NULL,
  target_category_id BIGINT UNSIGNED NULL,
  status             VARCHAR(16)     NOT NULL DEFAULT 'pending', -- pending|processing|succeeded|failed
  entry_id           BIGINT UNSIGNED NULL,
  error              VARCHAR(1000)   NULL,
  operator_id        BIGINT UNSIGNED NOT NULL,
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ai_knowledge_imports_status (status),
  KEY idx_ai_knowledge_imports_file (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
