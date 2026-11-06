---
artifact: test-report
work_id: WORK-077
work: 模拟面试接入简历（选择 / 上传）
status: passed
evidence: required
created: 2026-11-06
updated: 2026-11-06
---

# 验证报告

## 验证环境

- 日期：2026-11-06；改动仅 `frontend/apps/app/src/pages/InterviewPage.tsx`。
- Playwright 用系统 Chrome，对 `vite preview` 产物演练，`/api/v1/**` 全 mock（含 `resumes`、`resumes/parse`、`conversations`、SSE），不连后端、不花积分。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-005 类型检查 | `pnpm --filter @paideia/app typecheck` | 0 | passed | tsc 无输出 |
| AC-005 令牌/对比度 | `pnpm --filter @paideia/ui exec vitest run src/tokens.test.ts src/theme/contrast.test.ts` | 0 | passed | 2 files / 11 tests 全绿 |
| AC-005 生产构建 | `pnpm --filter @paideia/app build` | 0 | passed | 构建成功、懒加载守卫通过 |
| AC-001 起始页默认预选默认简历 | Playwright 截图 | 0 | passed | `.agent/prototypes/work077-shots/01-start-with-resume.png`（"我的主简历 · 默认"被选中） |
| AC-002 可切换到上传项 | Playwright `setInputFiles` | 0 | passed | `.agent/prototypes/work077-shots/03-uploaded.png`（选中"我的简历.pdf · 本次上传"） |
| AC-003 上传解析显示文件名 | 同上 | 0 | passed | 选项出现"我的简历.pdf" |
| AC-004 上下文进 pageContext、不进气泡 | 抓 messages POST body | 0 | passed | `{"scene":"existing-default","hasPageContext":true,"ctxHasProject":true,"candidateBubble":"开始面试"}` |
| AC-004 上传原文进上下文 | 抓 messages POST body | 0 | passed | `{"scene":"uploaded","ctxHasUploadText":true}` |

## 验收结果

| AC | 结论 | 证据 |
| --- | --- | --- |
| AC-001 | 满足 | 截图 01：列表 + 默认预选 |
| AC-002 | 满足 | 截图 03：切到上传项后原默认取消选中 |
| AC-003 | 满足 | 上传后选项显示文件名；后端 parse 复解析失败会给 `resumeError` 提示（错误分支代码路径） |
| AC-004 | 满足 | 请求体 `pageContext` 含简历文本；候选人气泡严格为"开始面试" |
| AC-005 | 满足 | typecheck / tokens+contrast / build 全绿；既有 testid 与 `interview-line-interviewer` 口径未改 |

## 失败与未验证项

- **上传失败分支未单独跑界面断言**：只走了成功路径；失败时 `resumeError` 显示错误、不阻断开面，属代码路径。
- 上传的简历**不落库**（本期设计如此），只在本次面试用一次；要留存走「简历」页。
- 同 WORK-075：真实 `identity.spec` 未跑（会花积分），其依赖的 testid 未改。

## 剩余风险

- `pageContext` 后端截断 4000 字；前端 `formatResume` 已截到 3800，抽取原文按后端上限截断。
- 简历内容会作为"页面场景"进入提示词，属预期；用户可随时选"不使用简历"。
