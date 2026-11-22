---
artifact: design
work_id: WORK-063
work: SQL 数据集 AI 引导式生成
status: approved
created: 2026-11-03
updated: 2026-11-03
approved_by: xiaou61
approved_at: 2026-11-03 12:05:00 +0800
approver_role: owner
---

# 设计

## 流程

```
点「让 AI 设计」
  └─ 关弹窗 → openGuide({title, prompt, groups}) → 面板打开
       └─ 对话区出现向导卡（三组，默认各选第一项）
            └─ 点「生成」→ 拼一条消息 → send(text) 自动发送 → 卡片消失
                 └─ 模型调 planner_build_dataset → 回答含 ```sql```
                      ├─ 「插入编辑器」→ appendSql → 追加到现有内容之后
                      ├─ 「继续编辑」→ applySql → 替换全文 + 编辑器聚焦
                      └─ 「存为数据集」→ 数据集弹窗预填（原路不变）
```

## 接口（`@paideia/assistant` 的 context）

```ts
interface AssistantGuide {
  id: number
  title: string
  /** 拼进消息的前置说明（"我已经选好了…"）。 */
  prompt: string
  groups: { key: string; label: string; options: string[] }[]
}
openGuide(guide)   // 设置并打开面板；不发消息
clearGuide(id)
appendSql(text) / clearPendingAppendSql(id)   // 与既有 applySql 同形
```

- 向导卡**不进会话历史**：它是"发送前的表单"，关掉面板即丢弃。
- `send()` 增加可选入参 `send(text?)`：向导卡直接传文本；输入框仍走默认路径。仅此一处调用点。

## 关键取舍

- **为什么不让模型输出选项块**：自定义块现在只在文档模式启用（模型输出不执行组件）；为助手开口子要多一轮
  等待、还要写格式漂移降级，而选项集合本来就是页面固定的三组。
- **为什么"插入"是追加**：编辑器里可能已有用户在写的语句，替换会毁掉草稿；追加是 SQL 的常态用法。
- **为什么"继续编辑"要聚焦**：替换之后下一步一定是改它，不聚焦就还要手点一次。
- **为什么不改 `ask()`**：其他入口靠"预填"避免替用户花钱；只有向导卡是用户点了「生成」才发送。

## 触碰的文件

| 文件 | 改动 |
| --- | --- |
| `frontend/packages/assistant/src/context.tsx` | guide / openGuide / appendSql |
| `frontend/packages/assistant/src/guide-card.tsx`（新） | 向导卡 + `composeGuideMessage` |
| `frontend/packages/assistant/src/conversation-panel.tsx` | 渲染卡片、`send(text?)`、两个动作按钮 |
| `frontend/packages/sql-workbench/src/sql-editor.tsx` | `focusSignal` |
| `frontend/apps/app/src/pages/SqlPracticePage.tsx` | 接线、追加 effect、聚焦信号 |
| `frontend/apps/app/e2e/sql-practice.spec.ts` | 改「让 AI 设计」用例 |

## 测试

- 单测：`composeGuideMessage`（三组齐全 / 缺项）与卡片交互（选 chips → 提交回调拿到拼好的文本）。
- e2e：点入口 → 卡片可见、输入框为空 → 选 chips + 生成 → 出现 user 消息；「插入 / 继续编辑」对编辑器的作用。
