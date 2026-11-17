---
artifact: test-plan
work_id: WORK-081
work: 借 Astryx 经验的设计系统演进
status: ready
created: 2026-11-17
updated: 2026-11-17
---

# 借 Astryx 经验的设计系统演进 — 测试计划

## 测试范围
- 文档层（AC-001/002/003）：三条借鉴点的映射表、采纳/暂缓/不做建议、最小实施切片清单。
- 代码层（design #1–#18）：`@paideia/ui` 组件清单生成、ui-kit 索引与模板展览、`@paideia/patterns`
  三模板及其测试、`@source` 覆盖、admin 试点页 `AiRunsListPage` 改用 `ListPage`。

## 环境与前置条件
- 本机 Node 22 + pnpm 12.10.1，`cd frontend && pnpm install --prefer-offline --ignore-scripts`。
- 代码层单测/typecheck/build 无需后端与数据库。
- admin 端到端（Playwright）需后端(8080/8081) + Redis(6379) + 测试库，且须走 `scripts/test-db.sh`
  取得独占库；本机 2026-11-17 三者均未启动，见报告「失败与未验证项」。

## 验收矩阵
| AC | 证据方式 | 命令 / 位置 | 预期 |
| --- | --- | --- | --- |
| AC-001 | 人工审阅 | `.agent/references/astryx-2026-11.md` 映射表节 | 三条借鉴点各有 Paideia 落点、成本与收益 |
| AC-002 | 人工审阅 | 同文件「采纳/暂缓/不做」节 | 每条有建议与理由 |
| AC-003 | 人工审阅 | 同文件「实施顺序」节 | 给出 REQ-001→002→003 的最小切片 |
| design #1–#4 | 自动 | `pnpm ui:manifest` / `pnpm ui:manifest:check` | 生成清单；check 退出 0 |
| design #5 | 自动 | `pnpm --filter @paideia/ui-kit typecheck` | 0 error |
| design #6–#11 | 自动 | `pnpm --filter @paideia/patterns typecheck` + `test` | typecheck 0 error；测试全绿 |
| design #12–#13 | 自动 | `pnpm --filter @paideia/ui exec vitest run src/styles/source-coverage.test.ts` | 2 通过（patterns 已登记） |
| design #14–#15 | 自动 | `pnpm --filter @paideia/ui-kit build` | 退出 0 |
| design #16–#17 | 自动+人工 | `pnpm --filter @paideia/admin typecheck` + `build`；e2e 待环境 | typecheck/build 0 error；e2e 未执行 |
| design #18 | 人工审阅 | `frontend/docs/ui-api-principles.md` | 三原则 + 暂缓项表述完整 |

## 自动化检查
- `cd frontend && pnpm ui:manifest && pnpm ui:manifest:check`
- `pnpm --filter @paideia/ui typecheck` / `... ui-kit typecheck` / `... patterns typecheck` / `... admin typecheck`
- `pnpm --filter @paideia/patterns test`
- `pnpm --filter @paideia/ui exec vitest run src/styles/source-coverage.test.ts`
- `pnpm --filter @paideia/admin build` / `pnpm --filter @paideia/ui-kit build`

## 人工检查
- 打开 ui-kit「组件索引」表与「页面模板」一节，确认三态与三模板可见。
- 打开 admin「AI 调用 → 明细」页，确认筛选、点行展开步骤表、分页行为与原实现一致。

## 回归范围
- 共享包 `@paideia/ui` 的 `index.ts` 出口未变（仅新增一行 `COMPONENT_META` 再导出）；`globals.css` 仅新增一条 `@source`。
- admin 试点页取其原有取数与 `Row` 渲染，理论上行为不变；改动了渲染外壳，需 e2e 兜底。

## 已知缺口
- admin e2e 未在本机执行（缺后端/Redis/测试库），仅以 typecheck+build 作为编译期证据。
