---
artifact: design
work_id: WORK-065
work: 数据集 AI 生成改为弹窗向导
status: approved
created: 2026-11-03
updated: 2026-11-03
approved_by: xiaou61
approved_at: 2026-11-03 16:20:00 +0800
approver_role: owner
---

# 设计

## 流程

```
「造一个自己的数据集」弹窗
  ├─ 自己设计：点着建表 / 写 SQL（原有）
  └─ 让 AI 生成 → 弹窗内四组选项（GuideCard）
        └─ 生成 → POST /sql-datasets/draft（结构化，只回 {name, sql}）
             └─ SQL 填进「写 SQL」、名字填好、切到写 SQL 页
                  └─ 用户改 → 确认保存（白名单 + 沙箱干跑 + 结构快照）
```

## 接口

```http
POST /api/v1/sql-datasets/draft   { "topic": "…四组选择拼成的一段…" }
→ { "name": "AI 生成的数据集", "sql": "CREATE TABLE …" }
```

- `SqlAiService.draftDataset(topic)` 与 `generateDataset` 共用生成器；后者改为调用前者再 `upload`。
- 生成器是结构化输出（`{name, sql}`），没有闲聊正文。

## 关键取舍

- **为什么不继续用聊天**：聊天提示词的人格是"解释"，而这条线的产出是 SQL。换结构化端点后模型没有机会写教程。
- **为什么 SQL 框就是预览**：省一个页面；用户能直接改，保存时会真跑一遍。
- **表名不加用户前缀**：每个数据集运行时导入自己的临时库（`practice` 或池内随机库），跨数据集不会同名冲突；UUID key 已足够区分。

## 触碰的文件

| 文件 | 改动 |
| --- | --- |
| `SqlAiService` / `SqlDatasetController` | 新增 `draftDataset` 与 `/draft` |
| `knowledge/client.ts` | `draftSqlDataset(topic)` |
| `@paideia/assistant` | 导出 GuideCard；删除 `guide/openGuide/clearGuide` 与会话面板里的向导渲染 |
| `SqlPracticePage` | 弹窗内向导、生成回填、去掉助手跳转 |
| e2e | 重写「让 AI 生成」用例 |
