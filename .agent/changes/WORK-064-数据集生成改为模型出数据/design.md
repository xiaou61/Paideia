---
artifact: design
work_id: WORK-064
work: 数据集生成改为模型出数据
status: approved
created: 2026-11-03
updated: 2026-11-03
approved_by: xiaou61
approved_at: 2026-11-03 15:00:00 +0800
approver_role: owner
---

# 设计

## 新契约

```json
{
  "tables": [
    {
      "name": "customers",
      "columns": ["id:INT", "name:VARCHAR(50)", "signed_up:DATE"],
      "rows": [
        {"id": 1, "name": "alice", "signed_up": "2025-01-12"},
        {"id": 2, "name": null, "signed_up": "2025-02-03"}
      ]
    }
  ]
}
```

- 每一行必须**含全部列**（NULL 写成 `null`，键不能省）；多余的键报错（抓拼写）。
- 数值：`BigDecimal` 解析；INT 系拒绝小数；DECIMAL 原样输出。
- 字符串：单引号转义为 `''`；换行/反斜杠拒绝（shortcut 注释写在代码里）。
- 日期：`yyyy-MM-dd` / `yyyy-MM-dd HH:mm:ss`，用 `LocalDate/LocalDateTime` 校验真实存在。

## 关键取舍

- **为什么不给工具加外键推断**：`manager_id`、`parent_id` 这类猜不准；而"留三个坑"是编辑意图，
  算法不会自动留。让模型想数据，工具只保证"拼出来的 SQL 一定合法"。
- **为什么上限从 200 降到 50**：模型要逐行输出，200 行 × 3 表是一次几万 token 的工具调用。
- **为什么拒绝反斜杠**：MySQL 默认把 `\` 当转义符、`NO_BACKSLASH_ESCAPES` 下又不是；练习数据不需要它。

## 触碰的文件

| 文件 | 改动 |
| --- | --- |
| `backend/.../dataset/SqlDatasetBuildTools.java` | 新入参、校验、字面量渲染 |
| `backend/.../dataset/SqlDatasetBuildToolsTest.java` | 按新契约重写 |
| `backend/.../conversation/PlannerPrompt.java` | 第 4 条：数据由模型逐行想 |
| `frontend/packages/assistant/src/page-context.ts` | 场景说明同步 |
| `frontend/apps/app/src/pages/SqlPracticePage.tsx` | 向导提示与行数选项（10/30/50） |
