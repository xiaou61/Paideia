---
artifact: proposal
work_id: WORK-012
work: 流式 Markdown 渲染（终止·增量·降级）
status: approved
workflow: full
depends_on: [requirements]
related_to: [WORK-005, WORK-006, WORK-011]
created: 2026-10-16
updated: 2026-10-16
approved_by: xiaou61
approved_at: 2026-10-16 13:37:41 +0800
approver_role: CTO
---

# 提案 · 流式 Markdown 渲染（终止·增量·降级）

## 一句话

助手回答**改用 `streamdown`（内含 `remend`）按 markdown 渲染**；我们自己的活只剩四件——**按需加载它、三处共用一套、用真实语料把"稳定"变成可验收、以及给它兜底降级**。

## 备选方案

### A. 自研四层（切分 / 补全 / 增量 / 降级）

**做法**：自己写块级切分状态机、按优先级跑的补全 handler 组、块级 memo、降级。
**优点**：零依赖、完全可控，出问题自己能改。
**缺点**：**用户明确说未来要"平台特有元素 / 公式 / 架构图"，这三样等于要再发明一遍**人家做好的东西（自定义标签预处理、标签内字面转义、KaTeX、Mermaid 集成），而且中文边界那类坑自己踩很痛（streamdown 有专门的 CJK 插件）。**不满足可扩展这一条。**

### B. `streamdown` + `remend`（建议）

**做法**：换掉 `react-markdown` + `remark-gfm`，改用 `streamdown` 渲染助手回答。
**优点**：
- 它是 **react-markdown 的 drop-in 替代**——不是净增依赖，是**换掉一个**，同时白得终止（remend）、块切分、块级 memo、处理器缓存、非阻塞更新那一整套；
- **未来那三样全是它的现成插件点**：`allowedTags` + `components` + `fallbackComponent`（自定义元素）、`@streamdown/math`（公式）、`@streamdown/mermaid`（架构图）；
- **与我们同源**：它就是 shadcn/ui + Tailwind 做的，要的 `--background/--muted/--border/--primary/--radius` 正是 WORK-002 已定的那套令牌；
- 自带 **`@streamdown/cjk`**（中文边界）；Apache-2.0；5.7k star / Vercel 官方。
**缺点/代价**：见下。

### C. 只引 `remend`（补全），渲染自己接

**优点**：依赖最小。
**缺点**：`remend` 只解决"补全"这一层；**块切分、块级 memo、处理器缓存、非阻塞更新仍要自己写**——那是自研里最费的四件。而且自定义元素/公式/图还是得自己来。

### D. opencode 式降级（不补全，尾巴当纯文本）

**优点**：零依赖、最小代码。
**缺点**：**它自己也因此出 bug**（官方 issue 与论坛都有"流式时渲染不对"的反馈）。**只保留作兜底**，不作主路径。

## 决策

**选 B。** 理由：用户明确要求"**稳定 + 可扩展**"，而 A 在"可扩展"这一条上结构性不成立；C 只拿走四件里的一件；D 是降级策略不是渲染方案。B 同时满足两条，且与既有技术栈同源、迁移成本低（drop-in）。

## 代价（已告知并接受）

- **体积：实测 +343KB**（学习者端 JS 合计 852KB → 1204KB）。**社区报的 +13MB 是"装了 mermaid + KaTeX + Shiki 全套"**，那些已是可选插件包；本项只装基础包。**代价可接受，但懒加载必须做**（tasks T-03），否则主包被白拖。
- **耦合**：它内部出 bug 时我们不能直接改（等上游或 fork/patch）。**缓冲只有两条**：钉版本 + 我们自己的语料回归。
- **演进快**（696 commits）：升级命名版本前**必须先跑语料**。
- **补全永远是概率性的**：真实语料上若某类语法做不到"逐前缀等价 0 例不一致"，**那一类必须明确降级为纯文本并登记**，不放宽断言。
