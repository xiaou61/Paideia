---
artifact: tasks
work_id: WORK-075
work: 模拟面试页体验重做
status: approved
created: 2026-11-06
updated: 2026-11-06
approved_by: xiaou61
approved_at: 2026-11-06 16:21:29 +0800
approver_role: owner
---

# 实施任务

## 依据

`requirements.md`（WORK-075，managed/compact）。单文件为主：`frontend/apps/app/src/pages/InterviewPage.tsx`。

## 全局约束

- 只动学习者端页面与其内部子组件，不改后端 / `AssistantApi` / SSE 协议。
- 颜色只取 `@paideia/ui` 令牌类；不引新依赖。
- 保留全部既有 `data-testid`，语义不变（尤其 `interview-line-interviewer` 只包正文）。

## 评审焦点

| 失败模式 | 归属任务 | 测试 / 验证 |
| --- | --- | --- |
| 思考过程又被渲染出来 | TASK-002 | 人工 + `getByText('思考过程')` 取不到 |
| 加载指示器把文字混进面试官气泡，导致失败态 textContent 非空 | TASK-002 | `identity.spec.ts` 面试用例 |
| 硬编码颜色混入 | TASK-003 | `pnpm --filter @paideia/ui test`（tokens.test） |
| 移动端横向滚动 | TASK-003 | 390 宽度目测 / e2e layout 无回归 |
| 评分卡漏渲染 strengths | TASK-004 | 生成点评后可见优点区 |

## 任务

### TASK-001 | done | 起始态引导卡
- 对应：REQ-006、AC-001
- 改动：`InterviewPage.tsx` 的 `lines.length === 0` 分支换成双栏引导卡（左：图标 + 标题 + intro + 目标岗位 + 开始按钮；右：怎么进行三步），隐私 Alert 保留"你的笔记默认不参与"。
- 验证：类型检查通过；未开始页面可见 `interview-start`。

### TASK-002 | done | 对话区重做（气泡/头像/加载指示器）
- 对应：REQ-001、REQ-002、REQ-003、AC-002、AC-003、AC-005
- 改动：
  - `Line` 去掉 `thinking`；SSE 的 `thinking` 事件忽略（不再累积、不再渲染）。
  - 面试官气泡（左，头像 + "面试官"），候选人气泡（右，头像 + "我"）；`data-testid` 只包正文。
  - 面试官正文为空且 `busy` 时，气泡内显示 `Spinner`，"面试官正在思考…"放在**testid 元素之外**。
  - 发送区（textarea + 发送按钮）固定在对话卡底部；Enter 发送、Shift+Enter 换行。
- 验证：本地 `identity.spec.ts` 面试用例通过。

### TASK-003 | done | 布局与右侧栏
- 对应：REQ-004、AC-002
- 改动：`AppShell wide`；主区 `max-w-5xl`；桌面 `lg:grid-cols-[minmax(0,1fr)_18rem]`，移动单列。右栏放历次面试（沿用 details 结构，加进度条）与使用提示。
- 验证：typecheck + tokens/contrast 测试；390 宽无横向滚动。

### TASK-004 | done | 评分卡可视化
- 对应：REQ-005、AC-004
- 改动：`EvaluationCard` 改为总分 + 维度进度条 + 优点/改进两栏；保留 `interview-overall` / `interview-dimension` / `interview-improvement` testid。
- 验证：生成点评后四项可见（人工或 e2e）。

## 完成条件

- AC-001…AC-006 均有证据（命令输出 / e2e 结果）。
- `pnpm --filter @paideia/app typecheck`、`pnpm --filter @paideia/ui test` 通过。
