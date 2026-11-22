---
artifact: design
work_id: WORK-068
work: 从零开始体验优化与内容扩充
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 15:30:18 +0800
approver_role: owner
---

# 设计

## 前端

### 折叠（REQ-001）

用原生 `<details open={open} onToggle={...}>`，不用自定义折叠组件：

- 键盘 Tab/Enter、读屏展开/收起语义都是浏览器给的；
- 只需一个 `useState` 承接 toggle，`summary` 里保留计数。

`summary` 去掉默认三角（`list-none` + `::-webkit-details-marker`），否则标题行左边会多一个不受控的图标。

### 数据来源（REQ-003/006/008）

- 岗位总览复用 `/learn` 已有的 `tracks` 数据，在前端按 `core + advanced` 求和，不新增接口；
- 到期复习用 `GET /learning/reviews`，按当前轨道 `entryId` 集合过滤；地图页本来就有全量章节数据；
- 条目页定位用 `GET /learn/tracks/{slug}`（地图页已取，走同一 queryKey 缓存），从 `chapters` 里找 `entryId` 得 `seq`。

### 筛选（REQ-007）

`levelFilter` 与 `hideDone` 是两个独立谓词，渲染时叠加；顺序不变，只减少。

## 内容

- 新线放 `content/reading/frontend-from-zero/`，frontmatter 走既有字段（category/order/chapter/level/minutes/status）；
- 总览声明 `line_name/line_kind/line_sort/line_summary`，并提供 `learning-path` 块；
- 每关一个 `quiz` 块（3 题），题目考理解而非背诵；
- 导入用 `scripts/import-course.mjs --dir content/reading/frontend-from-zero`，幂等。

## 岗位映射

`role-map.ts` 是编辑判断，符合"代码常量而非表"的既有决定：

```ts
frontend: { core: ['frontend-from-zero'], advanced: ['network-basics', 'git', 'system-design'] }
```

`available: true` 只有在内容导入后才能翻——否则核心区是空的。顺序：先导入，再发版。

## 守卫

`check-practice-coverage.mjs` 同时承担两件事：

1. 盘点（人看）；
2. quiz JSON 合法性（机器看，失败退出码 1）。

不接 CI，因为本仓库没有 CI；发布前手动跑一次。
