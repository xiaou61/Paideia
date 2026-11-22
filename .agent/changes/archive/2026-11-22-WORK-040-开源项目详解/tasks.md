---
artifact: tasks
work_id: WORK-040
work: 开源项目详解
status: approved
mode: managed
created: 2026-10-27
updated: 2026-10-27
approved_by: xiaou61
approved_at: 2026-10-27 18:18:21 +0800
approver_role: CTO
---

# 开源项目详解任务

## 前置检查

- 需求三项待确认已定案（Q-001 项目清单、Q-002 分类结构、Q-003 不配实战线）。
- 导入需要管理端账号与 `PAIDEIA_BASE_URL`；线上导入前先用 `--dry-run` 看一遍。
- 本机没有 Go 工具链与 Docker，Sprig 片段不运行；沙箱相关不在本项范围。

## 任务

### TASK-001 | done | 导入脚本支持 --no-track

- 对应：`REQ-003`、`AC-001`
- 依赖：无
- 新建/修改：`scripts/import-course.mjs`
- 接口：`--no-track`；该模式下 `POST /categories` 的 `lineKind` 为 `null`
- 测试：`--dry-run --no-track` 输出「无 line_kind」，普通模式输出「course」
- 验证：两种 dry-run 输出对照（见报告）

### TASK-002 | done | 简讲四条 + 总览

- 对应：`REQ-002`、`REQ-005`、`AC-002`、`AC-007`
- 依赖：无
- 新建/修改：`content/reading/open-source/00-总览.md`、`01-sqlite.md`、`02-vite.md`、`03-sqlglot.md`、`04-tailwind.md`
- 接口：无
- 测试：人工核对每条六段结构与「出处」三项（许可证/仓库/版本）
- 验证：AC-007 逐篇检查

### TASK-003 | done | 精讲四关 + 总览

- 对应：`REQ-004`、`AC-003`、`AC-004`
- 依赖：无
- 新建/修改：`content/reading/sprig-deepdive/00-总览.md` + 四关
- 接口：无
- 测试：每关 `quiz` 的 id 形如 `sprig-0N-quiz`
- 验证：AC-003 关卡数 4、AC-004 通关判定

### TASK-004 | done | 两条线互链

- 对应：`REQ-006`、`AC-005`
- 依赖：TASK-002、TASK-003
- 新建/修改：两篇总览里的链接
- 测试：链接指向 `/learn/sprig-deepdive` 与知识库分类
- 验证：AC-005 点击可达

### TASK-005 | done | 导入到测试库并验证幂等

- 对应：`AC-001`～`AC-004`、`AC-006`
- 依赖：TASK-001..004
- 新建/修改：无
- 测试：`mvn` 无关；用导入脚本 + 接口断言
- 验证：二次导入 `created 0 / updated 0`

### TASK-006 | done | 线上导入与生产验证

- 对应：全部 `AC-*`
- 依赖：TASK-005
- 新建/修改：无
- 测试：生产接口 + 线上页面
- 验证：见 `testing/report.md`

## 风险与验证对照

| 风险 | 任务 | 哨兵 |
| --- | --- | --- |
| 简讲被建成轨道 | TASK-001 | AC-001 断言 `/learn/tracks` 无 open-source |
| 精讲关卡缺带 id 的 quiz | TASK-003 | AC-004 上报进度验证 passed |
| 二次导入重复建条目 | TASK-005 | AC-006 幂等断言 |
| 简讲缺出处 | TASK-002 | AC-007 逐篇三项齐全 |
| 引入新路由 | 全部 | AC-008 `git diff --stat` 无 `router.tsx` |
