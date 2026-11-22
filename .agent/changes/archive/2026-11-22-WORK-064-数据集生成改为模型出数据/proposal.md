---
artifact: proposal
work_id: WORK-064
work: 数据集生成改为模型出数据
status: approved
created: 2026-11-03
updated: 2026-11-03
approved_by: xiaou61
approved_at: 2026-11-03 15:00:00 +0800
approver_role: owner
---

# 数据集生成改为模型出数据

## 背景

`planner_build_dataset` 原来按「列名 + 行号 + 类型」算哈希自己编数据（`SqlDatasetBuildTools.valueOf`）。
它不知道列与列、表与表的关系，用户实测三处硬伤：外键两列对不上（`customer_id` 与 `customers.id`）、
`status` 被填成 `alpha-25`、`signed_up` 出现未来日期；`amount` 与明细也各自独立。
保存路径的沙箱干跑兜不住——生成的 DDL 没有 `FOREIGN KEY`，MySQL 不查引用完整性。

## 用户裁决（2026-11-03）

- 数据由**模型逐行给出**，工具只做校验 + 拼装（不在工具里做命名约定推断）。
- 保留工具的价值：标识符/类型白名单、单引号转义、产出能过 `SqlStatementGuard`。

## 不做什么

- 不在工具里推断外键（`x_id` → 对应表）：命名约定猜不准，且"要不要留练习坑"是编辑意图。
- 不加 `FOREIGN KEY` 约束：练习数据要允许"对不上的行"（LEFT JOIN 的教材）。
- 不改保存链路（仍是白名单 + 沙箱干跑 + 用户点保存）。
