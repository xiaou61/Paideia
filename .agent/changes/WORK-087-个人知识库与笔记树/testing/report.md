---
artifact: test-report
work_id: WORK-087
work: 个人知识库与笔记树
status: passed
evidence: required
mode: strict
workflow: full
created: 2026-11-24
updated: 2026-11-24
---

# 个人知识库与笔记树 — 验证报告

## 环境与方法

- 后端：`scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=... test`，远程测试库 `paideia_e2e`（`backend/config/remote-db.local.env`，不入库）。本机 Redis 6379 已启动（否则 `/actuator/health` 503 会让 `AuthorizationIsolationTest.corsAllowsDesktopOriginOnHealth` 假红）。
- 前端：`pnpm --filter @paideia/app typecheck`、`build`；e2e `E2E_CHANNEL=chrome scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/notes-kb.spec.ts e2e/notes.spec.ts`（Playwright 起真实后端 + vite）。

## 后端结果

| 用例集 | 结果 | 备注 |
| --- | --- | --- |
| 迁移守卫三件（VersionFormat/Checksum/SeedOverlap） | 全绿 | `V20261124120000__note_books_tree_links.sql`、`V20261124130000__note_shares_target_nullable.sql` 校验和已登记 |
| ApiSurfaceTest / ApiContractTest | 10 条全绿 | 导入接口由 octet-stream/text 改 multipart 后，畸形体探测符合契约 |
| AuthorizationIsolationTest | 9 条绿 | |
| NoteTreeIntegrationTest | 3 条绿 | 建库/建树/移动环/删库级联 |
| NoteSearchIntegrationTest | 全绿 | 中文多字命中、单字 LIKE 回退 |
| NoteLinkIntegrationTest | 全绿 | 出链/入链/悬空/自引用 |
| NoteImportExportIntegrationTest | 全绿 | 单篇导出、整库 zip 往返、zip slip 400 |
| NoteBookShareIntegrationTest | 3 条绿 | 整库分享、单篇回归、到期 404 |
| NoteIntegrationTest | 4 条绿 | 旧扁平接口回归（20000 字上限不变） |
| AccountDeletionCascadeIntegrationTest | 4 条绿 | 含 `note_books`/`note_links` |
| MCP：McpToolSnapshotTest(3)/McpStartupSmokeTest(5)/NoteToolsTest(6)/AgentToolBridgeIntegrationTest(9) | 全绿 | note 工具 4→13、全量 65→74 |

一次选定的 12 个类合计 **47 条**：修导入接口 Content-Type 后 `ApiContractTest` 与 `NoteImportExportIntegrationTest` 16 条复跑全绿，其余同批全绿。

## 前端结果

- `typecheck` 绿；`build` 绿（懒加载守卫通过）。
- e2e **9 条全绿（1.5 分钟）**：
  - `notes-kb.spec.ts`：①建库→建文档→编辑保存→大纲→检索；②整库分享链接在**全新匿名上下文**打开，书名与文档树可见。
  - `notes.spec.ts`：原 6 条扁平笔记用例迁到 `/notes/all` 后全部通过（新建/预览/挂靠条目/分享撤回/未保存拦截/AI 提炼）。

## 架构与静态检查

- `node scripts/gen-arch-diagram.mjs --check`：模块/包/表(77)/端点(233) 四图一致。
- `node scripts/check-diagrams.mjs`：14 个 mermaid 块全部可解析。
- `project-lifecycle.py validate`：通过。

## 偏差与说明

- 迁移**保留** `note_shares.note_id` 并新增 `target_type/target_id`：`NoteReportMapper.list` 仍按 `note_id` join，删列会破坏举报列表。
- `note_shares.note_id` 改为可空（整库分享无单篇 id）：`V20261124130000__note_shares_target_nullable.sql`。
- 导入接口从原始字节改为 `multipart/form-data`：`ApiContractTest#malformedBodyIsAClientError` 要求「非 multipart 的带体端点对畸形体返回 400」，原始字节端点会先拿 404/415；改 multipart 后契约探测按允许的 415 处理，功能不变。
- `NoteApi` 不暴露 links（MCP 暂无消费方）；双链只在 Web 层可读。

## 结论

AC-001..014 均有自动化证据，未发现未解决告警。可进入验收。
