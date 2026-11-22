---
artifact: proposal
work_id: WORK-018
work: 简历识别与优化
status: approved
workflow: full
depends_on: [requirements]
related_to: [WORK-009, WORK-010, WORK-016, WORK-017]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 16:08:04 +0800
approver_role: CTO
---

# 提案 · 简历识别与优化

## 摘要

把"一份简历改成一个岗位想要的样子"做成产品内的一条闭环：**上传识别 → 核对落库 → 结构化编辑 → 粘贴 JD 分析 → 逐条改写提案（原文/改后并排）→ 用户逐项采纳 → 事实闸门 → 定稿版本 → 浏览器打印**。

关键设计只有三条：

1. **改写不是"交一整稿"，而是"交一份提案清单"**。模型为每处改动分别给出原文片段、改写片段、依据（JD 的哪句）与覆盖的关键词；用户逐条勾选，默认全部不采纳。这是"可视化 + 允许是否修改原文"（用户决定 5）在数据层的落点。
2. **三道确定性闸门由代码执行，不由模型自评**：关键词三桶、事实校验（数字/头衔/时间/重合度/缺口词）、结构完整性。可测、可复现，且不花 token。
3. **原文件不落盘、无免鉴权路径、不新增用户自带 Key 通道**。上传文件只在内存里抽文本；库里只存结构化数据与纯文本；调用一律经 `CredentialService.access` 走积分。

## 推荐方案

### 落点

新增 `paideia-ai` 的 `internal/resume` 子域，**不新开 Maven 模块**。理由与代价见「备选方案 A」。

模块内分层与既有 `internal/interview` 对齐：

| 层 | 内容 |
| --- | --- |
| 端口（包根） | `ResumeParser`（文本 → 结构化 JSON）、`ResumeAnalyzer`（主简历 + JD → 分析）、`ResumeProposer`（主简历 + JD + 分析 → 提案清单） |
| 实现（`internal/provider`） | 三个 `AgentScope*` 实现，与 `AgentScopeEntrySummarizer` 同一套最小面：非流式、不挂工具、`nativeStructuredOutput(false)`、只回 JSON 再本地严格校验 |
| 用例（`internal/resume`） | `ResumeImportService`（抽取 → 解析 → 草稿返回）、`ResumeDocumentService`（编辑/历史/回滚/多份）、`ResumeOptimizationService`（分析 → 提案 → 采纳 → 定稿）、`ResumeGate`（三道确定性校验）、`ResumeTextExtractor`（PDF/DOCX → 文本） |
| Web（`internal/web`） | `ResumeController`、`ResumeOptimizationController` |
| 删除级联 | 在 `AiUserDeletionListener` 的表清单里登记 4 张表 |

### 数据

四张表（详细字段见 `design.md`）：

| 表 | 作用 | 为什么单独存在 |
| --- | --- | --- |
| `resume_documents` | 主简历（结构化 JSON + 抽取出的纯文本 + 标题 + 是否默认） | 用户持续编辑的**唯一真源** |
| `resume_edit_history` | 编辑快照 | "撤销到上一版"需要历史；塞进主表就无法回滚 |
| `resume_versions` | 岗位版本（JD、公司、岗位名、定稿 JSON、时间） | 一岗一版；主简历永不被改 |
| `resume_proposals` | 逐条提案（原文、改后、类型、依据、覆盖关键词、采纳状态） | "逐项采纳"必须是可持久化、可回查的数据，不能是前端状态 |

> 上一版需求写的是两张表。加"在线编辑"后，历史与提案都需要独立存储——把历史塞进主表会变成一条不断变长的 JSON；把提案塞进版本表会让"还没定稿"没有落脚点。

### 接口（全部 `AUTHENTICATED`，无 `PUBLIC_READ`）

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/api/v1/ai/resumes/parse` | 上传文件，返回**未落库**的草稿（含原文引证） |
| `GET`/`POST` | `/api/v1/ai/resumes` | 列出我的主简历 / 从草稿创建 |
| `GET`/`PUT`/`DELETE` | `/api/v1/ai/resumes/{id}` | 读 / 整份保存（自动保存用它）/ 删 |
| `GET` | `/api/v1/ai/resumes/{id}/history` | 快照列表 |
| `POST` | `/api/v1/ai/resumes/{id}/history/{historyId}/restore` | 回滚 |
| `GET` | `/api/v1/ai/resumes/{id}/checkup` | 体检清单（纯代码，不调用模型） |
| `POST` | `/api/v1/ai/resumes/{id}/analyses` | 分析（计费） |
| `POST` | `/api/v1/ai/resumes/{id}/proposals` | 生成提案（计费） |
| `PATCH` | `/api/v1/ai/proposals/{proposalId}` | 采纳/不采纳、编辑改后文本 |
| `POST` | `/api/v1/ai/resumes/{id}/versions` | 定稿（跑闸门） |
| `GET`/`DELETE` | `/api/v1/ai/resumes/{id}/versions`、`/versions/{versionId}` | 版本列表 / 删除 |
| `GET` | `/api/v1/ai/versions/{versionId}` | 读单个版本（打印页与对比页共用） |

全部路径在 `/api/v1/ai/` 下、无 `admin` 段，归属一律取自令牌，拿不到统一 404。

### 三道确定性闸门

| 闸门 | 输入 | 算法要点 |
| --- | --- | --- |
| 关键词三桶 | JD 文本 + 主简历结构化数据 | 模型只负责**列出** JD 的显式要求（带原文引证）；分桶由代码做：归一化（大小写、全半角、空白折叠）后的子串匹配，外加一张**手写的等价表**（`k8s`↔`kubernetes`、`js`↔`javascript`、`postgres`↔`postgresql` 等，控制在 10 组以内）。查找命中技能区 → `现有`；命中其他分区 → `有证据`；全文没有 → `缺口` |
| 事实校验 | 提案的「改后」文本 vs 主简历全文 | ① 数字类 token（含 `%`、`万`、`k`、月份、年份、`年/月` 时长）必须能在主简历原文找到；② 内容词集合的 Jaccard 或覆盖率不低于阈值（阈值在 design 里定，需用夹具校准）；③ 职位名/公司名/时间串与主简历对应条目**逐字相等**（只归一空白）；④ 出现任一 `缺口` 关键词即失败 |
| 结构完整性 | 定稿 vs 主简历 | 每条经历的条目数、每条的要点数不得为 0（除非主简历本身为 0）；不许出现主简历里没有的经历条目 |

闸门失败**不落定稿**，把违规项按提案 id 返回，界面直接定位到那一条。

### 前端

新增 `frontend/packages/resume`（类型 + 两个客户端 + 展示组件），学习者端 `apps/app` 新增三条路由：

- `/resume`（列表 + 空状态上传）
- `/resume/:id`（编辑器：分区导航 + 表单 + 自动保存 + 历史抽屉 + 体检清单）
- `/resume/:id/optimize`（JD 输入 → 分析 → 提案对比 → 定稿）
- `/resume/versions/:versionId/print`（打印视图，`@media print`，无输入控件）

样式全部复用 `@paideia/ui` 令牌；`@source` 需在 `globals.css` 补一行（既有守卫 `source-coverage.test.ts` 会红）。

## 范围

**做**：上传解析（PDF/文本型 DOCX）、核对落库、字段级编辑、自动保存、编辑历史与回滚、多份主简历、体检清单、JD 分析、关键词三桶、逐条提案、可视化对比、逐项采纳、事实闸门、定稿版本、打印视图、归属隔离、注销级联。

**不做**：OCR、JD 链接抓取、投递评估与打分、公司/薪资调研、跟踪器、求职信、面试题、用户自带 Key、模板库、服务端 PDF、富文本编辑、ATS 通过率承诺、PDF 版面分析、分享/导出到第三方、向量检索。

## 备选方案

### A. 落 `paideia-ai` 的 `internal/resume`（建议）

**优点**：与 `internal/interview`、`internal/memory` 同构；复用 `CredentialService`、`CreditService`、`AiRequester`、`GuardedHttpTransport`、`AiUserDeletionListener` 五处既有装配；不新增 pom/依赖管理/Modulith 边界登记。

**代价**：`paideia-ai` 继续变大（现在 60+ 个文件）。若简历长成独立产品面（多人协作、模板市场、批量投递），拆出去要动包路径与依赖图——这是**已知并接受**的迁移成本。

**为什么不新开模块**：新模块要装配 Maven 模块、父 pom 的 `dependencyManagement`、`paideia-app` 的依赖与扫描、Modulith 边界校验四处；而它没有任何跨模块消费者（知识库、笔记、账号都不需要读简历）。"提前拆"在这里只买到一个更整齐的目录树。

### B. 纯前端实现（不上传服务器，浏览器内解析）

**优点**：零 PII 落库，隐私最好；不花积分。

**不选的理由**：浏览器内 PDF 文本抽取要引 pdf.js（约 1 MB）；即使抽出来，结构化仍要模型，而模型调用必须带平台 Key——放到前端等于把 Key 暴露给所有人。若改成"用户自己带 Key"，又与用户决定 6 直接冲突。

### C. 只调两次模型（解析 + 一次性生成整稿）

**优点**：便宜三分之一，实现更短。

**不选的理由**：与用户决定 5 冲突。"逐项可视化 + 逐项选择是否采用"要求模型按条目给出原文/改后/依据；一次生成整稿只能拿到成品文本，要再反推 diff，而 diff 反推无法可靠判断"这处改动来自哪条 JD 要求"。另外提案是**默认不采纳**的，整稿方案没有"未采纳也保留可回查"的落点。

### D. 原文件存进现有附件存储

**优点**：不用自己抽文本，读端点现成。

**不选的理由**：`GET /api/v1/knowledge/files/{id}` 是**全项目唯一免鉴权读接口**（WORK-004 用户裁决），靠 id 不可枚举当凭据。简历是最敏的 PII，把它放进"拿到链接就能读"的通道不可接受（REQ-023）。

### E. 服务端生成 PDF

**优点**：用户拿到的是文件，不依赖浏览器打印对话框。

**不选的理由**：要嵌中文字体（体积与许可）、处理分页与断行、处理条目跨页；而浏览器打印天然有字体与分页，且用户能自己选"另存为 PDF"。成本远高于收益（REQ-021 已把它列为非目标）。

### F. 把简历存成笔记（JSON 塞进 `notes.body`）

**优点**：零新表。

**不选的理由**：笔记表没有"默认简历""历史快照""提案采纳状态"这些结构；塞 JSON 进去后所有查询都要在应用层解析，且已有笔记的列表、分享、举报逻辑会被无关数据污染。

## 仓库影响

| 区域 | 影响 |
| --- | --- |
| `backend/paideia-ai` | 新增 `internal/resume`（服务 + 校验 + 文本抽取）、`internal/provider` 三个实现、包根三个端口、一个 Web 控制器组；`AiUserDeletionListener` 表清单 +4 |
| `backend/paideia-ai/pom.xml` | **只加一个依赖**：PDFBox（`org.apache.pdfbox:pdfbox`，版本写进父 pom 属性）；`.docx` 用 JDK 自带 |
| 迁移 | 一条新迁移，建 4 张表；编号取实施当时实际最大号 +1 |
| 前端 | 新增 `packages/resume`；`apps/app` 四条路由；`packages/ui/src/styles/globals.css` 补 `@source` |
| 测试 | 模块内单测（三桶、闸门、抽取、文本归一化）+ `paideia-app` 集成测试（归属隔离、注销级联、计费、零积分）+ 学习者端 e2e（编辑与采纳两条主链路） |
| 台账 | 新增 12 条路由自动进 `ApiSurfaceTest`；新增 4 张表进注销级联清单 |

## 交付拆分

四个可独立验证的纵向切片（详细任务在 `tasks.md`）：

1. **识别与落库**：文本抽取 + 解析端口 + 草稿确认 + 主简历 CRUD + 注销级联。验收 AC-001、AC-007。
2. **编辑**：自动保存 + 状态 + 历史快照 + 回滚 + 顺序调整 + 体检清单。验收 AC-002。
3. **优**：分析 + 三桶 + 提案 + 对比 + 逐项采纳 + 三道闸门 + 定稿。验收 AC-003、AC-004、AC-005。
4. **出口与横切**：打印视图 + 窄屏 + 计费/零积分/失败语义 + 质量记录。验收 AC-006、AC-008..AC-012。

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| **模型解析质量不稳**（PDF 双栏、表格导致条目错位） | 核对页强制人工确认（REQ-003）；解析必须带原文引证，用户能一眼看出错在哪；AC-012 只记录不设线，不承诺准确率 |
| **改写导致编造** | 三道闸门全部在代码里；提案默认不采纳；定稿前必须过闸门；反向验证（AC-004）作为必做测试 |
| **提示词注入**（简历或 JD 里写"忽略指令"） | 简历与 JD 一律作为被引用的数据段进提示词；解析结果必须落到固定 JSON schema，多出的字段丢弃；模型无法触发任何工具（本项三次调用都不挂工具） |
| **PII 泄露** | 原文件不落盘；无免鉴权读端点；归属 SQL 校验；注销级联；日志不打印简历正文与 JD 正文 |
| **成本失控**（三次调用 + 重新生成） | 每次调用前预扣；余额不足不发请求；「重新生成」按钮写明会再扣一次；流水按用途单独记 `kind` |
| **自动保存冲掉正在输入的字** | 前端保存状态机按"最后写入者胜 + 失败不回填"；AC-002 用必定失败的接口桩验证 |
| **三桶误判**（等价词表覆盖不到） | 等价表刻意保持小并写进 design；未命中的词归 `缺口`（保守方向：宁可漏报"我有"，不可虚报）；分类结果逐条可见，用户能自己判断 |
| **模块膨胀** | 已在备选方案 A 记录天花板与拆分成本；不提前拆，也不假装它不会发生 |

## 验收映射

| 验收 | 由哪一部分交付 |
| --- | --- |
| AC-001 识别→确认→落库；图片 PDF 拒绝 | 切片 1：`ResumeTextExtractor` + `ResumeImportService` |
| AC-002 编辑、自动保存与回滚 | 切片 2：`ResumeDocumentService` + 前端编辑器 |
| AC-003 逐项采纳与对比可见 | 切片 3：`resume_proposals` + 提案页 |
| AC-004 闸门反向验证 | 切片 3：`ResumeGate` 三个校验器 + 反向用例 |
| AC-005 三桶与缺口 | 切片 3：`ResumeKeywordClassifier` |
| AC-006 零积分与单一路径 | 切片 4 + `CredentialService` 不改动 |
| AC-007 归属与注销 | 切片 1：SQL 归属 + 删除监听器登记 |
| AC-008 无公开读路径 | 切片 1 + 4：`@ApiAccess` 全 `AUTHENTICATED` |
| AC-009 打印视图 | 切片 4：打印路由 + 打印 CSS |
| AC-010 窄屏可用 | 切片 4：窄屏堆叠 |
| AC-011 失败不留脏数据 | 切片 1/3：失败路径不落库 |
| AC-012 识别质量记录 | 切片 4：三份合成简历的手工记录 |

## 待决定事项

需求里剩下的两条实现细节，本提案给出结论：

1. **提案草稿态落库（采纳）**：`resume_proposals` 一行一条，`accepted` 是列。创建立即落库（`accepted = false`）。理由：刷新与换设备都要保持（REQ-016）；只在内存里存会让"逐项采纳"变成一个随时会丢的临时状态。
2. **编辑快照粒度（采纳 60 秒 / 50 条）**：同一份主简历 60 秒内的连续保存合并为一条快照；每份保留最近 50 条，超出按时间淘汰。理由：自动保存一次一个快照会让历史表以每分钟十条的速度膨胀；60 秒足以覆盖"打一段话"的节奏。

两条都不改变需求边界，批准本提案即视为采纳；实施时写进 `design.md`。
