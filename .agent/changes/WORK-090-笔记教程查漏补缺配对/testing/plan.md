---
artifact: testing-plan
work_id: WORK-090
work: 笔记教程查漏补缺配对
status: draft
created: 2026-11-24
updated: 2026-11-24
---

# 测试计划（WORK-090）

| 检查项 | 命令/方式 | 期望 |
| --- | --- | --- |
| 迁移格式/校验和/种子隔离 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest test` | 全绿 |
| 后端配对与校验 | `-Dtest=NoteTreeIntegrationTest,NoteIntegrationTest,NoteToolsTest` | 全绿 |
| 接口契约 | `-Dtest=ApiContractTest` | 全绿 |
| 前端类型/构建 | `pnpm --filter @paideia/app typecheck` / `build` | 成功 |
| 前端 e2e | `scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/notes-kb.spec.ts e2e/notes.spec.ts` | 全绿 |
