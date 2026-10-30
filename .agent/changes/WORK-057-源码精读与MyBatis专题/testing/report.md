---
artifact: test-report
work_id: WORK-057
work: 源码精读（第三方源码 + MyBatis 专题）
status: passed
evidence: required
workflow: full
created: 2026-10-30
updated: 2026-10-30
verified_commit: b718921
---

# WORK-057 验证报告

**结论**：源码精读已上线。`/source` 独立入口、「整文件 + 绝对行号 + 开关/分支 + 状态面板 + 跨文件跳转」
的走读块、MyBatis 3.5.19 的 **24 关**内容都在线上；旧的「源码解读 / 读代码实战」7 条、2 分类、2 轨道已删除。
线上 e2e **7/7** 通过（含逐章打开 24 关与跨上下文完成状态），后端单测切片 **769 条 / 0 失败 / 16 跳过**。

**未达**（如实记录）：AC-003 的 GitHub 原文页没能在浏览器里点开（本机与线上机器到 github.com 都超时）；
链接只在 API 层验证了「文件存在于该 commit」。

## 验证环境

| 项 | 值 |
| --- | --- |
| 线上 | `codenest-online`（36.212.5.130:8081），JDK 25 / MySQL 8 |
| 内容 | `mybatis/mybatis-3` @ `mybatis-3.5.19` = commit `ee0d4f48…`，Apache-2.0 |
| 快照 | `/opt/paideia-app/web/source/mybatis-3.5.19/`（69 个 Java 文件 + LICENSE/NOTICE） |
| 本机 | Windows / Git Bash；Playwright 1.64（浏览器 1223 以 junction 映射到 1248 路径） |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 独立入口与项目卡 | e2e `source.spec.ts -g 页头有独立入口` | 0 | 通过 | 导航 `learner-nav-source` → `/#/source`；卡片 `source-project-mybatis-source` 含 "MyBatis" |
| AC-002 整文件 + 行号 + 切步高亮 | e2e `-g 章节页能取到整份源码` | 0 | 通过 | `source-walk-focus-line` 首行从 `A 行 3` 类位置换成下一步的行；组件测试 3 条 |
| AC-003 GitHub 原文链接 | `gh api repos/mybatis/mybatis-3/contents/src/main/java/org/apache/ibatis/binding/MapperProxy.java?ref=ee0d4f48…` | 0 | 未达 | 文件在该 commit 存在（返回 size=5954），链接由 `walkPermalink` 拼 `repoPath+file+#L`；本机 `curl github.com` 返回 `000`，**没能在浏览器里点开核对行号** |
| AC-004 预测揭晓与测验 | 组件测试 `预测：选完显示揭晓` | 0 | 通过 | 选 "会" 后 `source-walk-predict` 出现 "不会，先看缓存分支。"；quiz 由既有 `scoreQuiz` 判定（33 条单测） |
| AC-005 进度一致/跨设备 | e2e `-g 答完小测后「已读完」`（新浏览器上下文 = 另一台设备） | 0 | 通过 | 答完 3 题小测 + 1 道动手题后服务端 `status=passed`；**新上下文重新登录**打开项目页，首章显示「已读完」 |
| AC-006 375px 无横向溢出 | e2e `-g 375px` | 0 | 通过 | `scrollWidth - clientWidth = 0`（断言 ≤1） |
| AC-007 绝对路径/`..` 拒绝 | `parseSourceWalk` 单测（6 条） | 0 | 通过 | `base: /etc`、`source/../../etc`、`file` 不在 files、`when` 未声明控件均返回 `ok:false`；组件取文件前再验 origin |
| AC-008 静态文件只放 GET | `curl -w %{http_code}` | 0 | 通过 | `GET …/MapperProxy.java` → 200（6111 B）；`GET …/LICENSE` → 200（11562 B）；`POST …/LICENSE` → 401 |
| AC-009 导入幂等 | `node scripts/import-course.mjs --dir content/source/mybatis --no-track` | 0 | 通过 | 首次 `created 24`；补练习后 `updated 24`；再跑 `created 0 / updated 0` |
| AC-010 版本/许可/来源 | 每篇正文顶部 + 快照 `SOURCE.md` | 人工 | 通过 | 24 篇块内均有 `repo/ref/commit/license`；快照根有 `LICENSE`、`NOTICE`、`SOURCE.md`（tag + commit + 拷贝日期） |
| AC-011 开关改变步骤 | e2e `-g 切换执行器后步骤真的变了` | 0 | 通过 | 默认无 "BATCH" 步骤；`selectOption('BATCH')` 后出现 1 条；单测 `关掉开关后步骤真的变少` |
| AC-012 分支改高亮/分支信息 | 内容审计 + e2e `-g 切换执行器后步骤真的变了` | 0 | 通过 | 第 14 关 `statementType` 三个分支分别指向 `RoutingStatementHandler:42/46/49`，切换后高亮行区间随之变化；第 3 关的分支改变步骤序列与状态面板 |
| AC-013 跨文件跳转与返回 | 组件测试 `跨文件定位：跳到目标行，返回后回到当前步` | 0 | 通过 | 点 `回到代理` → `source-walk-pin-line` 是 "A 行 3"；点 `返回` → pin 消失，焦点回到 "B 行 5" |
| AC-014 每章互动下限 | 一次性审计脚本（Python 读 24 篇 JSON） | 0 | 通过 | 24/24 章：controls ≥1（总览免）、predicts ≥2（总览 1）、code-challenge ≥1、每步有 state；输出 "chapters below floor: 0" |
| AC-015 旧内容清理 | 管理端 DELETE + `select` 复查 | 0 | 通过 | 7 条正文、分类 16/43、轨道 `source-reading`/`code-reading` 均 0；`git` 分类 8 条完好；`leftover_progress=0` |
| AC-016 24 关全上线 | e2e `-g 24 章逐章都能打开` | 0 | 通过 | 章节链接数 = 24，逐章 `source-walk` 与高亮行可见；`/learning/lines` 不再含旧两条线 |
| AC-017 无残留引用 | `rg "source-reading\|code-reading"` + e2e 标题选择器 | 0 | 通过 | 应用代码只剩注释；首页卡与 `LearnPage` 图标映射已删；`source-reading.spec.ts` 移除首页入口用例，其余块用例保留 |

## 测试与构建汇总

- 前端单测：schema 33 条（本次新增 9 条）/ 组件 5 条 / 内容回归 1 条（覆盖 source-walk、code-challenge、quiz 三类块），全绿；
  UI 全量 **253 passed / 1 failed**，失败是既有的 `tokens.test.ts`（`apps/admin/AnalyticsPage.tsx` 硬编码 amber/sky 颜色，与本次无关）。
- 后端：`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` → **769 运行 / 0 失败 / 0 错误 / 16 跳过**
  （跳过的是 live/上游探针与 `FileStorageContractTest` 的 S3 分支）。
- 构建：`vite build` 通过（45s）；`mvn -pl paideia-security -am -DskipTests compile` 通过。
- 线上 e2e：7 条全过（37.1s），含 24 章逐章打开与「换一个浏览器上下文仍是已读完」。

## 实现订正（验证中发现）

- 用 `--no-track` 导入的线走默认 **COURSE** 规则：动手题与小测都要 ≥80% 才算 `passed`。
  首版内容只有小测，e2e 的「已读完」用例暴露后，给 24 章各补了一道非选择题（排序/填空）。

## 未覆盖与已知限制

- GitHub 原文页未在浏览器里打开（本机直连超时）；只验证了「文件存在于该 commit」。
- 跨设备进度未复验；只确认服务端写入 `tour_step`。
- 分页/复杂度高的源码文件（`Configuration.java` 1900 行）首屏渲染未做性能测量。
- 内容仍是「预演轨迹」：状态面板与分支由作者按真实运行结果编写，不是页面内实时执行。

## 反向验证

- `parseSourceWalk` 的拒绝用例是**正向断言拒绝**（绝对路径、`..`、未声明文件、未声明控件），
  不是"去掉守卫后变红"的破坏性验证；守卫同时在组件取文件前再校验一次 origin。
- 静态白名单：`POST /source/**` 返回 401，说明放行确实只覆盖 GET。
