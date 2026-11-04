---
artifact: tasks
work_id: WORK-068
work: 从零开始体验优化与内容扩充
status: in_progress
created: 2026-11-04
updated: 2026-11-04
---

# 任务

## 已完成

- [x] TASK-001 `/learn`：选读折叠、岗位总览、继续卡仅在有进度时出现（REQ-001/003/004）。
- [x] TASK-002 `/learn/:slug`：章节跳转、到期复习提示、只看未通关（REQ-005/006/007）。
- [x] TASK-003 条目页：第 N/M 关与通关状态（REQ-008）。
- [x] TASK-004 `scripts/check-practice-coverage.mjs`：盘点 + quiz JSON 校验（REQ-009）。
- [x] TASK-005 补齐 13 篇无练习的关（open-source 4、books-ddia 3、clean-code 2、man-month 2、thinking 2）；全库 313 关零纯阅读（REQ-009）。
- [x] TASK-006 `frontend-from-zero`：总览 + 12 关，每关 3 题 quiz（REQ-010）。
- [x] TASK-007 `role-map.ts`：前端岗位核心指向新线，`available: true`（REQ-010）。

## 待办

- [x] TASK-008 `data-analysis` 线：11 关（口径、表格模型、读数据、清洗、聚合、连接、时间、可视化、因果、完整分析），每关 1 组 quiz。
- [x] TASK-009 `web-security` 线：8 关（信任边界、注入、XSS、CSRF、越权、密钥、依赖、上线检查）。
- [x] TASK-010 `testing-from-zero` 线：8 关（测试价值、单测、替身、集成、e2e、数据、覆盖率、维护）。
- [x] TASK-011a `python-scripting`：4 关（CSV 汇总、词频、JSON 日志、分组报告），`verify: run`；线上沙箱实跑通过。
- [x] TASK-011b `linux-troubleshooting`：4 关（权限、磁盘、脚本执行、环境变量），`kind: shell` 由服务端真实判题；线上实跑通过。
- [ ] TASK-011c Docker 部署实战：**未写**。判题容器是否有 docker daemon 未验证；若只能做 Dockerfile 文本检查，会退化成背指令而不是部署，等确认环境后再做。
- [x] TASK-012 内容导入线上（dry-run → 正式）并验证 `/learn` 前端岗位。三条理论线 created 12/9/9；两条实战线 created 5/5。
- [x] TASK-013 前端 UI 改动部署（104 秒，health 200）。
