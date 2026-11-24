---
artifact: test-report
work_id: WORK-090
work: 笔记教程查漏补缺配对
status: passed
evidence: required
created: 2026-11-24
updated: 2026-11-24
---

# 验证报告

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001/AC-002/AC-004 迁移、配对字段往返、旧文档不变、MCP 透传 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,NoteTreeIntegrationTest,NoteIntegrationTest,ApiContractTest -Dmigration.checksums.update=true -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `Tests run: 26, Failures: 0, Errors: 0`；新迁移 `V20261124153000__note_doc_type_pair.sql` 校验和 `2011671897` 已登记；`pairFieldsRoundTrip` 覆盖 tutorial+gap_fill 树返回 docType/pairKey、目录/非法 doc_type/pairKey 超长均 400；`NoteIntegrationTest` 断言旧文档 `"docType":null`/`"pairKey":null` |
| AC-004 MCP 工具透传与 tree 输出 | `mvn -pl paideia-note -am -Dtest=NoteToolsTest test`（随上条 -am 一并编译执行） | 0 | passed | `createDocPassesDocTypeAndPairKey`、`treeShowsDocTypeAndPairKey` 绿 |
| AC-003 前端同标识成对、徽章、一键补建 | `E2E_CHANNEL=chrome PAIDEIA_BACKEND_PORT=8082 PAIDEIA_APP_PORT=5184 scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/notes-kb.spec.ts` | 0 | passed | 新增用例「教程与查漏补缺：同标识成对，可一键补建」绿；`3 passed (47.8s)` |
| 旧笔记流程回归 | 同上跑 `e2e/notes.spec.ts`（新增用例前一次全量） | 0 | passed | `9 passed (1.5m)`（notes-kb 2 + notes 6 + 分享 1） |
| 前端类型检查 | `pnpm --filter @paideia/app typecheck` | 0 | passed | `tsc -p tsconfig.json` 无输出 |
| 前端生产构建 | `pnpm --filter @paideia/app build` | 0 | passed | `✓ built`，懒加载守卫通过 |
| AC-005 全部相关测试通过 | 上述后端 26 条 + 前端 `notes-kb` 3 条 + `notes.spec.ts` 6 条（合计覆盖迁移守卫、配对集成、NoteTools、契约、e2e） | 0 | passed | 后端 `Tests run: 26, Failures: 0`；e2e `9 passed (1.5m)` 与新增用例后 `3 passed (47.8s)` |
