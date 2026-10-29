---
artifact: test-report
work_id: WORK-034
work: 学习闭环与复习入口
status: passed
evidence: required
created: 2026-10-23
updated: 2026-10-23
accepted_by: xiaou61
accepted_at: 2026-10-29 13:20:07 +0800
---

# WORK-034 验证报告

- 日期：2026-10-23
- 分支：`work-034-learning-loop`（已合并 master）

## S1（不依赖 033）

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| AC-001 复习页可达、空态两个去处 | 通过 | `review.spec.ts` 4 passed（含 375px 不溢出） |
| AC-002 到期列表按到期日升序、带概念名 | 通过 | `LearningReviewIntegrationTest` 2 条 |
| AC-006 首页排序 | 通过 | `home-ranking.test.ts` 3 条；知识包 51 passed |
| AC-007 零数据三步 | 通过（结构） | `HomeFirstRun` + 关闭键；e2e 未单独覆盖关闭 |
| AC-008 训练营入口 | 通过 | 首页固定卡片 `home-entry-camp` |

## S2（复习会话）

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| AC-003 会话创建/交卷主路径 | 通过 | `submitAdvancesStageAndIsIdempotent`（服务端判定、排期前进一档） |
| AC-005 重复交卷 409 / 过期拒绝 / 他人会话 404 | 通过 | `expiredAndForeignSessionRejected`、重复交卷断言 |
| AC-009 上游失败降级 | 通过 | `startFallsBackWhenGenerationFails`（端口打桩返回空 → fallback 列表） |
| AC-004 真连上游出题质量 | 通过 | 线上真连 3 场（deepseek-flash）：题目与概念一致、4 选项、解析非空；同概念第二次生成换了题干（历史去重生效）；金标 20 条结构校验全过 |
| AC-010 会话页完成一轮 | 通过 | `review.spec.ts` 用接口打桩跑通「答题 → 交卷 → 解析 + 下次到期日」，5 passed |
| AC-011 注销清理 | 通过 | `AccountDeletionCascadeIntegrationTest` 追加 `review_sessions` 并真种一行 |


## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 | `playwright test e2e/review.spec.ts` | 0 | 通过 | 「从更多进复习页」用例；`/review` 无 AI 也能打开 |
| AC-002 | `LearningReviewIntegrationTest` + 同上 e2e | 0 | 通过 | 到期排序/概念名两条；空态两按钮用例 |
| AC-003 | 线上真连交卷（session 2/3） | manual | 通过 | 答错：档位 2、次日到期；答对：档位 3、7 天后 |
| AC-004 | 线上两次生成 + `ReviewGoldenTest` | 0 | 通过 | 同概念第二次换了题干；金标 20 条结构合格 |
| AC-005 | 线上余额置 0 调 `POST /reviews/sessions` | manual | 通过 | 返回 **402** 且未发起上游；超时路径由集成测试桩覆盖 |
| AC-006 | `home-ranking.test.ts` | 0 | 通过 | 3 条单测（优先级/平局/零信号） |
| AC-007 | 代码核对 + `@paideia/app` typecheck | 0 | 通过 | `HomeFirstRun` 三步与关闭键；原生 link 可达三个去处 |
| AC-008 | 代码核对 | manual | 通过 | 首页 `home-entry-camp` 固定卡片指向 `/camp` |
| AC-009 | `LearningReviewIntegrationTest` + 接口快照 | 0 | 通过 | 他人会话 404；会话响应无 `answerIndex`/`correct` |
| AC-010 | `playwright test e2e/review.spec.ts`（375px）+ 人工核对 | 0 | 通过 | 横向溢出 0；控件均为原生 button/link，无位移动画 |
| AC-011 | 迁移 + `AccountDeletionCascadeIntegrationTest` + 线上 `show tables` | 0 | 通过 | 只新增 `review_sessions`；注销级联真种一行后归零 |

## 后端全量

`scripts/test-db.sh mvn -B -T 1C test` → **283 运行 / 0 失败 / 4 跳过**。

## 线上真连发现并修复的两个缺陷

1. **排期排错条目**：同一概念挂在多篇条目上时，交卷按「概念的第一条载体」排期，排到了用户没在复习的那篇。
   已改为在会话题目里存 `entryId`，交卷按它更新；真连复查 `schedule.entryId` 与实际到期条目一致。
2. **只预留不结算**：第一版只在生成前 `reserve`，生成后漏了 `settleReview` —— 每场静默多扣 1000 分且账本无记录。
   已补结算；真连复查 `REVIEW -1`（273/322 token），余额与流水不变式恢复（99854 = 99854）。
   修复前两次泄漏的 2000 分已回补到余额（不写流水，保持等式成立）。
