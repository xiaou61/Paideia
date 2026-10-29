---
artifact: test-report
work_id: WORK-029
work: AI训练营
status: passed
evidence: required
run_at: 2026-10-21 15:40 +0800
target: 本地独占测试库（scripts/test-db.sh）+ 本地 Playwright
accepted_by: xiaou61
accepted_at: 2026-10-29 13:19:36 +0800
---

# AI训练营验收报告

## 结论

**18 条验收标准全部通过**，并且**已经用真实模型（deepseek-flash）跑通完整链路**——不是桩。

真实调用一跑就暴露了 **三个桩测试永远看不见的缺陷**（前两个在 `5637312`、第三个在 `8dbaf47` 修掉）：

1. **`max_tokens` 固定 4096 会把 12 道题截断。** 实测那 4096 里 **3226 是思考 token**，正文只够写到第 3 道题，`finish_reason=length`，整份 JSON 不是合法的——对外表现为「模型输出不符合题目格式」，看起来像模型不听话，其实是我们的上限太小。改成按题数算（思考预留 4096 + 每题 450）。
3. **8192 的硬顶对 18 道题仍不够。** 7 天营实测 completion 用到 7210 token（思考 4463），思考量抖动时 8192 会被吃满再次截断。硬顶提到 16384（探针确认 12280 能正常 `finish_reason=stop`）。
2. **请求超时用默认 30 秒不够。** 12 道题实测 24–30 秒：诊断卷 29.8 秒**压线通过**，复测 30.1 秒直接失败，报「上游模型服务暂时不可用」。这种"看运气"的边界不能留，改成 `60 + 题数 × 10` 秒、夹到 300。

线上验证还观察到一个**正常但值得记下**的现象：同一份简历两次开营，一次 12 题 0 丢弃、一次 12 题丢 10。丢弃原因全是 `DUPLICATE_STEM`——模型那一次确实给了 10 道近似重复的题。**这是设计要的行为**（坏题丢弃并计数、营照常创建），但它说明"模型出题稳定性"是有波动的：题库靠一次调用时，重复率控制得住，质量仍有随机性。这也正是"分档 + 校验 + 丢弃计数"存在的理由。

另有一处**不是本项引入、但卡住了本项**的问题：线上 OkHttp 的 `readTimeout` 是默认 10 秒，12 道题的生成一律在 10 秒处超时。该缺陷 WORK-027 已诊断并修复（`f8936b9`），本项部署时它还没进 master。**教训是同一条纪律的第三次复现**：非流式 + 大输出的调用，读取超时必须显式放大。

后端 **249 条测试执行 / 0 失败 / 4 跳过**（跳过的是既有的 `FileStorageContractTest` 用例），前端 camp 包 **7 条单测**，学习者端 e2e **camp.spec.ts 3 条全绿**。

**实施中订正了工件里的两处矛盾**，都在这里说明，因为它们改变了行为：

1. **REQ-016 第一条与 AC-007 冲突。** REQ-016 写「答对率低于 80%」判弱，AC-007 却要求「4 题错 1 题**不**判弱」——而 3/4 = 0.75 恰好低于 0.8。按字面实现会正好踩中评审焦点里点名的「一道坏题把整主题判弱」。**落值为**：判弱看错题数（错 ≥ 2；或题量 ≤ 3 时错 ≥ 1；或难档全错），80% 只作强项展示口径。
2. **AC-010 要求天天有项，而弱项少时排不满。** 一个弱项、两个载体只撑出 2 项。**落值为**：用**前一天已排过的真实条目**补一条复习项；若前面的项全是缺口项（平台没内容），就再排一次同一缺口项并写明"可以让助手讲一讲"，**不编造标题与链接**。

## 验收逐条

| AC | 结果 | 证据 |
| --- | --- | --- |
| AC-001 开营到出卷 | **通过（含真实模型）** | `CampIntegrationTest.createsCampWithItems`：12 道题落库、`shortOfTarget=false`、流水出现一行 `kind=CAMP`；`stemsCarryNoPii` 断言题干不含姓名/电话/邮箱；`insufficientCreditsNeverCallUpstream` 余额 0 → 402 且上游调用计数 0。模型为桩，见「未覆盖」 |
| AC-002 画像为空被拒 | **通过** | `emptyResumeIsRejected`：400、`camp_programs` 0 行、余额不变、上游 0 次 |
| AC-003 交卷判分 | **通过** | `scoresLocally`：全对 → `weak` 为空、两主题 correct 各 6；错题用例覆盖计数 |
| AC-004 不能提前看答案 | **通过** | `itemsNeverExposeAnswerKey`：响应体不含 `answerIndex`/`explanation`，字段集合恰好 `{id,seq,topic,difficulty,stem,options}`；**交卷后同一断言仍成立**；`api-shape.json` 快照里也没有这两个键 |
| AC-005 不能改判 | **通过** | `forgedCorrectFlagIsIgnored`：请求体塞 `correct:true` 与 `score:100` 且全选错答案 → `correct=1` 的行数为 0；二次提交 409 且作答行数不变 |
| AC-006 坏题被丢且不崩 | **通过** | `ItemRulesTest.everyCheckHasATest` 12 种坏输入各自命中对应 `Reason`；`CampServiceTest.repairsOnce` 两轮坏题 → 抛 `NO_VALID_ITEM` 且不建营 |
| AC-007 弱项判据 | **通过（口径已订正）** | `ScoreAndCompareTest.weaknessRulesFourCases` + `singleWrongInFourIsNotWeak`：4 题错 1 → 不弱；错 2 → 弱；难档全错 → 弱；2 题错 1 → 弱 |
| AC-008 计划是纯函数 | **通过** | `PlanBuilderTest.planIsDeterministic`（两次逐项相等）、`planIsOrderIndependent`（打乱 5 次结果不变）、`firstDayStartsWithWeakest`（词典序与优先级相反的夹具） |
| AC-009 计划锚定真实内容 | **通过** | `planDegradesWhenEntryUnpublished`：造概念 + 已发布条目 → 出计划 → 把条目改 `draft` → 重新 GET，响应里不再出现该 entryId，该项变 `gap` |
| AC-010 预算与空白日 | **通过** | `budgetIsRespected`（3 天 ≤ 90、7 天 ≤ 60）、`noEmptyDay`（1 个弱项 3 天营天天有项）、`overflowGoesToUnscheduled`（20 个弱项 + 3 天 → `unscheduled` 非空且总数守恒）；集成侧 `planRequiresDiagnostic` 验未交卷 400 |
| AC-011 复测可比 | **通过** | `retestMatchesDistributionAndAvoidsRepeats`：复测与诊断的主题×题量逐项相等、两组指纹无交集；代码里还有一道 `diagnosticFingerprints` 过滤兜底（反向验证见下） |
| AC-012 并排对比 | **通过** | `retestResultCompares` 断言响应含 `verdict:IMPROVED` 与 `previous`；`ScoreAndCompareTest.comparisonVerdicts` 覆盖进步/持平/退步三种；`mismatchedCountsAreInconclusive` 验题量不同 → `INCONCLUSIVE` 且带原因 |
| AC-013 掌握度变化 | **通过** | `CampMasteryIntegrationTest` 3 条：无营状态两次同分、有营后变高、同一概念**全对 > 全错**、连续两次逐位相同。反向验证：把第四路证据固定传 null → `全对 0.0000 vs 全错 0.0000`，两条用例红 |
| AC-014 归属与删除 | **通过** | `otherUsersCampIs404AndDeleteIsComplete`：B 访问 A 的营/题目/结果/计划四类端点全 404；删营后五张表逐表 0 行 |
| AC-015 注销级联 | **通过** | `AccountDeletionCascadeIntegrationTest` 逐表种一行后注销，五张表断言 0 行 |
| AC-016 无公开读路径 | **通过** | 11 个端点全部 `@ApiAccess(AUTHENTICATED)`；`ApiContractTest` 9 条全绿；`ApiWalkthroughTest` 走查到全部 11 条 |
| AC-017 窄屏 | **通过** | e2e `390px 下作答页与结果页无横向滚动`（`scrollWidth - clientWidth ≤ 1`）；对比表在 `md` 以下单列堆叠 |
| AC-018 失败不留脏数据 | **通过** | `emptyUpstreamLeavesNothingBehind`：上游抛空返回 → 500、`camp_programs` 0 行、余额回到原值（无用量 → 全额退） |

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 开营到出卷（模型为桩） | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=CampIntegrationTest test` | 0 | 通过 | `createsCampWithItems`：12 道题落库、`shortOfTarget=false`、流水一行 `kind=CAMP`；`stemsCarryNoPii` 断言题干不含姓名/电话/邮箱；`insufficientCreditsNeverCallUpstream` 余额 0 → 402 且上游计数 0 |
| AC-002 画像为空被拒 | 同上 | 0 | 通过 | `emptyResumeIsRejected`：400、`camp_programs` 0 行、余额不变、上游 0 次 |
| AC-003 交卷判分 | 同上 | 0 | 通过 | `scoresLocally`：全对 → `weak` 为空、两主题各 6 题全对；错题计数由同一用例与 `TopicScore` 单测覆盖 |
| AC-004 不能提前看答案 | 同上 + `ApiWalkthroughTest` | 0 | 通过 | `itemsNeverExposeAnswerKey`：字段集合恰好 `{id,seq,topic,difficulty,stem,options}`，交卷前**与交卷后**都不含 `answerIndex`/`explanation`；`api-shape.json` 快照里也没有这两个键 |
| AC-005 不能改判 | 同上 | 0 | 通过 | `forgedCorrectFlagIsIgnored`：塞 `correct:true` 与 `score:100` 且全选错 → `correct=1` 行数为 0；二次提交 409 且作答行数不变 |
| AC-006 坏题被丢且不崩 | `... -Dtest='ItemRulesTest,CampServiceTest' -pl paideia-ai` | 0 | 通过 | `everyCheckHasATest` 12 种坏输入各自命中对应 `Reason`；`bothRoundsBadRefunds` 两轮都坏 → `NO_VALID_ITEM` 且不建营 |
| AC-007 弱项判据（口径已订正） | `... -Dtest=ScoreAndCompareTest -pl paideia-ai` | 0 | 通过 | `weaknessRulesFourCases` + `singleWrongInFourIsNotWeak`：4 题错 1 → 不弱；错 2 → 弱；难档全错 → 弱；2 题错 1 → 弱 |
| AC-008 计划是纯函数 | `... -Dtest=PlanBuilderTest -pl paideia-ai` | 0 | 通过 | `planIsDeterministic` 两次逐项相等；`planIsOrderIndependent` 打乱 5 次结果不变；`firstDayStartsWithWeakest` 用词典序与优先级相反的夹具 |
| AC-009 计划锚定真实内容 | `... -Dtest='CampIntegrationTest,CampMasteryIntegrationTest'` | 0 | 通过 | `planDegradesWhenEntryUnpublished`：造概念 + 已发布条目 → 出计划（**先断言响应里出现了该 entryId**）→ 改 `draft` → 重新 GET 后不再出现该 id 且标题变「这项内容已下架」。反向验证：把 `weakTopics` 的两个 long 参数写反 → 这条红 |
| AC-010 预算与空白日 | `... -Dtest='PlanBuilderTest,CampIntegrationTest'` | 0 | 通过 | `budgetIsRespected`（3 天 ≤ 90 / 7 天 ≤ 60）、`noEmptyDay`（1 个弱项 3 天营天天有项）、`overflowGoesToUnscheduled`（20 个弱项 3 天营：总数守恒、每天 ≤ 18 项）；`planRequiresDiagnostic` 未交卷 400 |
| AC-011 复测可比 | `... -Dtest=CampIntegrationTest` | 0 | 通过 | `retestMatchesDistributionAndAvoidsRepeats`：复测与诊断的主题×题量逐项相等、两组题干指纹无交集 |
| AC-012 并排对比 | `... -Dtest='CampIntegrationTest,ScoreAndCompareTest'` | 0 | 通过 | `retestResultCompares` 断言响应含 `verdict:IMPROVED` 与 `previous`；`comparisonVerdicts` 覆盖进步/持平/退步；`mismatchedCountsAreInconclusive` 题量不同 → `INCONCLUSIVE` 且带原因 |
| AC-013 掌握度变化 | `... -Dtest=CampMasteryIntegrationTest` | 0 | 通过 | 4 条全绿：无营状态两次同分；有营（全对）后分数变高；同一概念**全对 > 全错**（反向验证把它们改成 0.0000 vs 0.0000）；连续两次 GET 逐位相同 |
| AC-014 归属与删除 | `... -Dtest=CampIntegrationTest` | 0 | 通过 | `otherUsersCampIs404AndDeleteIsComplete`：B 访问 A 的营/题目/结果/计划四类端点全 404；删营后五张表逐表 0 行 |
| AC-015 注销级联 | `... -Dtest=AccountDeletionCascadeIntegrationTest` | 0 | 通过 | 五张表各真种一行后注销，逐表断言 0 行（反向验证：去掉 `camp_tasks` 会红） |
| AC-016 无公开读路径 | `... -Dtest='ApiContractTest,ApiWalkthroughTest'` | 0 | 通过 | 11 个端点全部 `@ApiAccess(AUTHENTICATED)`；契约 9 条 + 走查 1 条全绿，11 个营端点全部被走到 |
| AC-017 窄屏 | `PAIDEIA_BACKEND_PORT=8099 PAIDEIA_APP_PORT=5199 scripts/test-db.sh pnpm exec playwright test camp.spec.ts` | 0 | 通过 | `390px 下作答页与结果页无横向滚动`（`scrollWidth - clientWidth ≤ 1`）；对比表在 `md` 以下单列堆叠 |
| AC-018 失败不留脏数据 | `... -Dtest=CampIntegrationTest` | 0 | 通过 | `emptyUpstreamLeavesNothingBehind`：上游空返回 → 500、`camp_programs` 0 行、余额回到原值（无用量 → 全额退） |
| **线上真实端到端**（生产实例 `codenest-online:8081`） | 部署后打线上 e2e-learner：开营 → 交卷 → 计划 → 复测 → 对比 → 删营 | 0 | 通过 | 开营 59.6 秒出 11 题；交卷 4.9 秒；计划 6.0 秒（三天 3/2/1 项）；复测 31.2 秒出 11 题与诊断 **0 道重复**；对比 `IMPROVED`；本轮实扣 45 分（两笔 `CAMP`）；测完已删营与测试简历 |
| **真实模型 7 天营**（非桩） | 同上一行，但 `days=7`（18 道题） | 0 | 通过 | 18 题全生成 0 丢弃（40.0 秒）、6 主题各 3 题、难度 easy/medium/hard 各 1 |
| **真实模型全链路**（非桩） | 本地起 jar（`paideia_live_*` 独占库）→ 真调 `api.deepseek.com` 的 `deepseek-flash`：开营 → 交卷 → 出计划 → 复测 → 交复测 | 0 | 通过 | 12 题全生成 0 丢弃（29.8 秒）；判分 3 主题全对 / 3 主题全错，弱项与强项都准确；计划三天各有项、每天 15 分钟、勾选生效；复测 12 题与诊断 **0 道重复**（24.0 秒）；并排对比 `SAME`；流水两笔 `CAMP`（-19 / -15 分，in/out token 齐全）。题目质量抽查：线程池扩容顺序、`ThreadLocal` 内存泄漏、`acks=all`+幂等、联合索引最左前缀、覆盖索引、缓存穿透、`@Transactional` 自调用失效、Redlock 争议——都是真面试题 |
| 后端全量回归 | `scripts/test-db.sh mvn -B -T 1C test` | 0 | 通过 | Tests run: 249, Failures: 0, Errors: 0, Skipped: 4（既有文件存储用例） |
| 迁移校验和登记 | `... -Dtest=MigrationChecksumTest` | 0 | 通过 | 只新增一行：`V20261021135406__create_camp_tables.sql 405229757` |
| camp 包单测 | `pnpm --filter @paideia/camp test` | 0 | 通过 | Tests 7 passed |
| 前端类型 | `pnpm --filter @paideia/app typecheck` | 0 | 通过 | 无输出 |

## 反向验证（每条都真跑过"去掉就红"）

| 去掉什么 | 期望 | 实测 |
| --- | --- | --- |
| `SkillDigest` 只收词表命中（改成把原文也当 term） | PII 用例红 | **红**：`promptBlockCarriesNoPii` + `emptyResumeIsEmpty` 同时红 |
| `ItemValidator` 的解析锚定 | 坏题用例红 | **红**：`everyCheckHasATest` 在 `EXPLANATION_NOT_ANCHORED` 那一行红 |
| 分档余数给中档（改成给难档） | `split(2)` 断言红 | **红** |
| `PlanBuilder` 按 `priority` 排序（改成按 topic 字典序） | 「首日最弱」红 | **红**：`firstDayStartsWithWeakest` |
| `CampComparer` 的 `inconclusive` 分支 | 题量不同用例红 | **红**：`mismatchedCountsAreInconclusive` |
| 注销级联去掉 `camp_tasks` | 逐表断言红 | **红**：`[camp_tasks 里还留着这个人的数据]` |
| e2e 去掉「答完才能交卷」的 `disabled` 判断 | 提前交卷用例红 | **红**：`expect(locator).toBeDisabled() failed` |

## 未覆盖（如实记录）

1. ~~真实模型出题的质量未验~~ **已验（2026-10-21）**：真调 `deepseek-flash` 三次开营（含 7 天营 18 题）+ 一次复测。12 道题的 3 天营与 18 道题的 7 天营都跑通，题目质量抽查见证据表。
2. **TASK-018 只验到「do 任务指向带 quiz 块的真条目」。** `CampMasteryIntegrationTest.planTaskLinksToExistingProgressPath` 断言计划里出现 `kind=do` 且 `entryId` 命中夹具；点开条目、界面上做 quiz、`ProgressPort` 写 `learning_progress` 那一段是 WORK-020 的既有链路（本项未新增上报路径），但没有在浏览器里手动串一次。
3. **概念表为空的部署没有单独走一遍。** 集成测试里的概念表确实为空（`knowledge.concepts()` 返回空表），所以计划全部产出 `gap` 任务这条路径是**被覆盖**的；但"管理员完全没建概念时界面上的提示文案"没有人看过。
4. **`publishedEntryIds` 是逐条查询（N+1）。** 一份计划十几项，量级够小；已在代码里用 `shortcut:` 注释标明升级条件。

## 本轮改动清单

- `backend/paideia-app/src/main/resources/db/migration/V20261021135406__create_camp_tables.sql`（五张表）
- `backend/paideia-ai/.../internal/camp/`（11 个类：画像/分档/指纹/校验/聚合/弱项/排期/时长/对比/出题/服务）
- `backend/paideia-ai/.../internal/web/CampController.java`（11 个端点）
- `backend/paideia-ai/.../internal/provider/SingleShotModelCaller.java`（原 `ResumeModelCaller` 改名：消费者已不止简历）
- `backend/paideia-knowledge/.../MasteryEvidence.java`（新端口）、`KnowledgeApi` 增 4 个只读方法、`EntryMapper` 增 2 条查询、`ConceptGraphService` 接第四路证据
- `frontend/packages/camp/`（新包：类型/客户端/三个组件 + 7 条单测）
- `frontend/apps/app/src/pages/Camp{List,Detail}Page.tsx`、路由、三处入口
- `backend/paideia-app/src/test/java/io/github/xiaou61/CampMasteryIntegrationTest.java`（AC-013 的端到端）
- `frontend/apps/app/e2e/camp.spec.ts`
