---
artifact: research
work_id: WORK-018
work: 简历识别与优化
created: 2026-10-18
---

# 调研：简历识别与优化

调研时间 2026-10-18，三个仓库均按当日 HEAD 浅克隆后逐文件阅读。

## 参考仓库

| 仓库 | 许可证 | 规模 | HEAD | 语言 |
| --- | --- | --- | --- | --- |
| `career-ops-hq/career-ops` | MIT | 75,390★ / 14.1k fork / 3,102 commits | `090f787`（2026-10-17） | JavaScript |
| `srbhr/Resume-Matcher` | Apache-2.0 | 28,743★ / 4.9k fork | `ff5b370`（2026-10-17） | Python |
| `AmruthPillai/Reactive-Resume` | MIT | — | `14fb49b`（2026-10-18，v6.0.4） | TypeScript |

## career-ops 的流程

单条 JD 的完整链路（`modes/auto-pipeline.md` + `modes/oferta.md` + `modes/pdf.md`）：

1. **取 JD**：URL 走 Playwright / WebFetch / WebSearch 三级回退；纯文本直接用。LinkedIn 有一次尝试预算。
2. **存活闸门**：确认岗位还开着，避免对幽灵岗位做整轮评估。
3. **黑名单闸门**：命中用户自己的"不投名单"就停下并复述理由，等用户确认。
4. **岗位原型判定**（`modes/_shared.md`）：6 类——LLMOps / Agentic / 技术 PM / 解决方案架构 / 前部署 / 转型。都不匹配就明说，北向对齐打 1 分，不硬套。
5. **A–H 八块评估**：
   - A 岗位摘要（含地域、工作许可、PcD 配额检查）
   - B **需求 → 证据映射表**：一行一条 JD 要求，列为 `Requirement | Importance | Match | JD signal | Evidence/gap`；最多 12 行；`Importance` 只用五个档位（critical/high/meaningful/preferred/low_signal），每档必须带证据层级（stated/structural/inferred）；**inferred 永远不能升到 critical/high**——防止模型靠市场猜测制造"别投了"的结论
   - C 级别与策略（"不撒谎地卖出资深"、"被降级怎么办"）
   - D 薪资与需求（公司类型 → 薪资可信度分级；无公开数字时只写两行）
   - E 定制计划（Top 5 简历改动 + Top 5 LinkedIn 改动）
   - F 面试计划（6–10 个 STAR+R 故事，R = Reflection）
   - G 招聘帖真实性（三档：高可信 / 谨慎 / 可疑）
   - H 申请表草稿（仅 ≥4.5 分时）
6. **打分**：五个维度（匹配 / 北向对齐 / 薪资 / 文化 / 红旗）合成一个 1–5 分，`3.5 以下建议不投`，`4.0` 是"该不该投"的分界；另有一个 `confidence` 描述证据强度，**不改变分数**。
7. **出报告** `reports/{NNN}-{company}-{date}.md`。
8. **定制简历**（`modes/pdf.md`）——见下节。
9. **投递跟踪**：TSV 合并进 `data/applications.md`，绝不直接改表。

## career-ops 的简历定制流程（重点）

`modes/pdf.md` 的 23 步里有四道**零模型判断**的闸门，这是最值得抄的部分：

| 步骤 | 脚本 | 做什么 |
| --- | --- | --- |
| 4 | `jd-skill-gap.mjs` | 把 JD 显式要求分成三桶：`existing`（技能区写明）/ `supportedByResume`（正文出现过）/ `gap`（全文没有）。**必须有低置信度告警**：零结果不等于没有缺口，可能是"没找到要求章节"或"提取不出候选" |
| 17a | `cv-title-check.mjs` | 定制稿里每个经历的职位名必须与源简历**逐字一致**（只允许大小写/空白归一），专门拦"同一个岗位被写成更高级的头衔" |
| 17b | `verify-cv-structure.mjs` | 反方向：源简历的内容是否被**静默丢失**（公司描述被压缩成城市、经历顺序被打乱） |
| 19 | `verify-cv-facts.mjs` | **硬闸门**：新出现的每个数字/百分比/时间长度必须能在源文件里找到；另外检查"越权归因"（把团队成果说成个人）、"范围膨胀"、结构化字段漂移 |

其他值得记的取舍：

- **标题一致性只警告不拦**，但必须把每个不匹配项展示给用户，由用户决定保留还是改回。
- **重排要点、改写摘要、注入 JD 用词都是允许的**；只有职位名与时间不许动。
- **14 条 NEVER**：编造经历与数字、改动源文件、代替候选人投递、生成 PDF 前不读 JD、企业腔等。
- **分享一个数字**：作者自己 740 次评估 / 68 次投递 / 12 次面试 / 1 个 offer——说明"评估"这个环节本身不产生结果。

## Resume-Matcher 的流程

`apps/backend/app/services/`：

1. **解析**（`parser.py`）：PDF/DOCX → 文本 → LLM 转成固定 JSON（`personalInfo / summary / workExperience / education / personalProjects / skills`）。有严格的资源预算：单文件 16 MB 展开上限、PDF 最多 100 页、转换超时 120 秒。
2. **关键词**（`improver.py`）：另一次 LLM 调用，输出 `required_skills / preferred_skills / keywords / key_responsibilities`。对 JD 做注入清洗（`_INJECTION_PATTERNS` 匹配"ignore previous instructions"等）。
3. **定制**（`improve_resume`）：主简历 + JD → 定制稿。
4. **多轮 refine**（`refiner.py`）：① 关键词注入 ② 去 AI 味（`AI_PHRASE_BLACKLIST` / `AI_PHRASE_REPLACEMENTS`）③ 与主简历对齐校验（`AlignmentReport` / `AlignmentViolation`，逐条报告"这处改动在原文里没有依据"）。
5. **要点选择**（`bullet_selector.py` + `bullet_scoring.py`）：LLM 只给每条要点打 0–100 分，**取哪些、丢哪些完全由确定性代码决定**；关键词兜底打分是"命中一个 JD 词 +20，上限 100"。
6. **页数适配**（`page_fit.py`）：真的渲染一遍数页数，超了就裁低分要点，最多重渲染 6 次。
7. **ATS 分**（`ats.py`，**纯代码**）：关键词 40% + 技能覆盖 20% + 标题 15% + 分节完整 15% + 日期一致 10%；无法评估的分项把权重按比例分给其余项。
8. **ATS 版面检查**（`ats_parse/checks.py`）：用 pdfminer 读生成后的 PDF，检查文字层、乱码字形、多栏、表格、页眉页脚、图片、联系信息、分节标题、日期。
9. **导出**：模板 + 服务端渲染。

它对"诚实"的处理：`CRITICAL_TRUTHFULNESS_RULES` + 关键词注入提示词里反复写"只改措辞，不加技能、不加认证、不加指标"。

## Reactive Resume

纯前端简历编辑器：JSON Resume schema 为唯一数据源、多套模板、浏览器内导出。没有解析、没有 JD、没有优化。唯一可取的是"结构化数据是唯一真源"这一点。

## 国内同类产品（超级简历等）

功能面：解析导入、ATS 兼容性校准、JD 关键词比对与匹配度、一键优化（补关键词、调重点）、20 项检查与一键修复、5000+ 模板、导出 PDF、**由一份基础简历派生多个岗位版本**。

## 共同结论

三家加上国内产品，骨架一致：

```
解析成结构 → JD 关键词逐条比对 → 只改写已有事实 → 出稿前确定性校验
```

差异只在"校验做了多少"和"成品怎么出"。career-ops 校验最厚（四道闸门），Resume-Matcher 校验最工程化（资源预算 + 确定性选点 + 纯代码打分），Reactive Resume 不做校验（它不是优化工具）。

**本项目的取舍**：取 career-ops 的四道闸门里与内容相关的三道（三桶、事实、标题/时间），不取结构校验（我们的结构化数据本身就有序，不存在"顺序被打乱"）；取 Resume-Matcher 的"主简历与定制稿分离"和"确定性校验"，不取多轮 refine（每轮都是钱）与页数裁切（要服务端渲染）；不取任何一家的打分体系（无法校准，且会诱导用户把精力放在分数上）。
