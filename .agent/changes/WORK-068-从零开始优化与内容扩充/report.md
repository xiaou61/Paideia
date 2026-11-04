---
artifact: report
work_id: WORK-068
work: 从零开始体验优化与内容扩充
status: in_progress
created: 2026-11-04
updated: 2026-11-04
---

# 报告

## 已完成（TASK-001..007）

### 学习地图

- **选读默认折叠**：原生 `details/summary`，标题行保留 `条数 · 关数 · 已通关`；核心与进阶不折。
- **岗位总览行**：`核心 + 进阶共 N 关 · 已通关 M 关（P%）`，只算两段，选读不稀释。
- **继续上次**：只对"已开始且未通关"的轨道渲染；新用户走推荐卡。
- **轨道页**：章节 chip 跳转（≥2 章才出现）、本线到期复习提示（按 entryId 过滤）、只看未通关开关（与难度筛选叠加）。
- **条目页**：从地图进入时显示 `「线路名」第 N/M 关` 与已通关/未通关；直接开链接不显示"第几关"。

### 内容

- `scripts/check-practice-coverage.mjs`：按线盘点练习块，附带 quiz JSON 校验。
- 补齐 13 篇无练习的关（open-source 4、books-ddia 3、clean-code 2、man-month 2、thinking 2）→ 全库 313 关零纯阅读。
- 新线 `frontend-from-zero`：总览 + 12 关（浏览器渲染链路、HTML 语义、盒模型与层叠、Flex/Grid、响应式、JS 值与函数、DOM 与事件、异步 fetch、数据页面、React 心智、状态与副作用、调试与上线），每关 3 题 quiz。
- `role-map.ts`：前端岗位 `available: true`，核心指向新线；进阶为网络、Git、系统设计。

## 未完成（TASK-008..013）

- `data-analysis`、`web-security`、`testing-from-zero` 三条理论线，以及 Python/Docker/Linux 三条实战线；
- 内容导入线上与前端部署。

这些是内容工作量，不是技术阻塞；按"一条线一条线推"的节奏继续，避免批量产出浅内容。

## 关键决定

- 折叠用原生元素而不是自研组件：键盘与读屏语义免费，且少一个要维护的状态机。
- 到期复习不新增接口：复用 `/learning/reviews`，前端按 entryId 求交集。
- 内容与岗位映射分两步上线：**先导入内容，再发版翻 `available`**，否则前端岗位核心区会是空的。
