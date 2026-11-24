---
id: WORK-091
status: draft
---

# 测试计划

## 范围

- 后端：迁移三件守卫、NoteTree/Note 集成测试、NoteToolsTest、ApiContractTest。
- 前端：typecheck、生产构建、笔记 e2e（notes.spec.ts + notes-kb.spec.ts）。

## 命令

- `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`
- `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,NoteTreeIntegrationTest,NoteIntegrationTest,ApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `pnpm --filter @paideia/app typecheck`
- `pnpm --filter @paideia/app build`
- `E2E_CHANNEL=chrome PAIDEIA_BACKEND_PORT=8082 PAIDEIA_APP_PORT=5184 ./scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/notes-kb.spec.ts e2e/notes.spec.ts`
