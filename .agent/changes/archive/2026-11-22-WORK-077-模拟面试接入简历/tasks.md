---
artifact: tasks
work_id: WORK-077
work: 模拟面试接入简历（选择 / 上传）
status: approved
created: 2026-11-06
updated: 2026-11-06
approved_by: xiaou61
approved_at: 2026-11-06 16:59:41 +0800
approver_role: owner
---

# 实施任务

## 依据

`requirements.md`（WORK-077，managed/compact）。主改 `frontend/apps/app/src/pages/InterviewPage.tsx`。

## 全局约束

- 只动学习者端页面；复用 `@paideia/resume` 的 `createResumeApi` 与 `assistant.sendMessage` 的 `pageContext`；不改后端。
- 颜色只用令牌；不引新依赖。
- 保留既有 `data-testid` 与 `interview-line-interviewer` 只含正文的契约。

## 评审焦点

| 失败模式 | 归属任务 | 验证 |
| --- | --- | --- |
| 简历文本进了候选人气泡 / 落库 | TASK-002 | 开面后气泡只显示"开始面试"；请求体里是 `pageContext` |
| 不选简历却仍发空上下文 | TASK-002 | `pageContext` 为空 |
| 上传失败把开面卡死 | TASK-003 | 解析失败有提示、仍可开面 |
| 隐私文案缺失 | TASK-001 | 界面可见"仅在你选择后用于本次面试" |

## 任务

### TASK-001 | done | 起始卡「简历（可选）」选择区
- 对应：REQ-001、REQ-004、AC-001、AC-002
- 改动：`useQuery(['resumes'])` 拉 `resumeApi.list()`；渲染可选列表（默认简历预选、含"不使用简历"）；隐私文案。
- 验证：typecheck。

### TASK-002 | done | 开面投递简历上下文
- 对应：REQ-003、AC-004、AC-005
- 改动：`start()` 按选择取简历文本（`get(id)` 的 data 格式化，或上传的 `plainText`），作为 `sendMessage` 第 5 参 `pageContext`；其余轮次不带。
- 验证：本地 mock 下检查首帧请求体 `pageContext`；气泡仍只显示"开始面试"。

### TASK-003 | done | 本页上传解析
- 对应：REQ-002、AC-003
- 改动：隐藏 `input[type=file]` + 上传按钮；`resumeApi.parse(file)`，成功存 `plainText`+文件名并选中，失败显示错误。
- 验证：mock parse 成功/失败两条路径。

### TASK-004 | done | 积分提示 + 开面二次确认
- 对应：REQ-005、AC-006
- 改动：起始卡标注积分计费；点「开始面试」弹 `Dialog` 列明"扣积分 / 结束有报告 / 中途别离开"，确认按钮 `interview-start-confirm` 才 `start()`。
- 验证：mock 截图 + `identity.spec` 同步点确认。

### TASK-005 | done | 结束面试二次确认
- 对应：REQ-006、AC-007
- 改动：对话头加「结束面试」按钮 → 确认弹窗 → 确认后复位到未开始；`useBlocker` 拦站内跳转 + `beforeunload` 提示关闭/刷新。
- 验证：mock 点击结束 → 弹窗 → 回到起始态。

## 完成条件

- AC-001…AC-007 有证据；typecheck / ui 测试通过。
