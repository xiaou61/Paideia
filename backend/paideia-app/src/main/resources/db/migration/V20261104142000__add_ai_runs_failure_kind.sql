-- AI 失败的处置语义（WORK-069）。
--
-- 只加列：取值由 io.github.xiaou61.ai.FailureKind 限定为 retryable / bad_output /
-- needs_human，NULL 只用于 OK / CANCELLED 这类非失败终态。
--
-- 不加索引、不加 CHECK：当前行数小，全表扫可接受；等真的有量再补
-- (failure_kind, started_at)。历史行不回填，管理端把 NULL 显示为「未记录」。
ALTER TABLE ai_runs
  ADD COLUMN failure_kind VARCHAR(16) NULL AFTER error_type;
