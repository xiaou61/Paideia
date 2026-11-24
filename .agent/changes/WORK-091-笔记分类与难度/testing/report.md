---
id: WORK-091
status: passed
evidence: required
---

# 测试报告

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001/AC-002 后端非集成切片 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` | 0 | passed | 83 tests, 0 failures（含 NoteToolsTest category/level 读回） |
| AC-001/AC-002 迁移守卫+笔记集成 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,NoteTreeIntegrationTest,NoteIntegrationTest,ApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | 26 tests, 0 failures |
| AC-003 前端类型检查 | `pnpm --filter @paideia/app typecheck` | 0 | passed | tsc 无错误 |
| AC-003 前端生产构建 | `pnpm --filter @paideia/app build` | 0 | passed | built in 3.40s，懒加载守卫通过 |
| AC-004 笔记 e2e | `E2E_CHANNEL=chrome PAIDEIA_BACKEND_PORT=8082 PAIDEIA_APP_PORT=5184 ./scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/notes-kb.spec.ts e2e/notes.spec.ts` | 0 | passed | 11 passed (1.6m) |
