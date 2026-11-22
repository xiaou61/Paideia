---
artifact: test-report
work_id: WORK-012
work: 流式 Markdown 渲染（终止·增量·降级）
status: passed
evidence: required
verified_commit: 0df2f9c
verified_at: 2026-10-17
accepted_by: xiaou61
accepted_at: 2026-10-29 13:17:29 +0800
---

# 流式 Markdown 渲染验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 机器 | 本机（Windows / Git Bash），Node 24、pnpm workspace |
| 被验提交 | `3793a31`（实施）；其后只有 `.agent/` 工件变化 |
| 依赖版本 | `streamdown@2.7.0`、React 19、Tailwind 4.3.3、Playwright（chromium） |
| 学习者端 e2e 前置 | 本机 3307 SSH 隧道（通）+ 本机后端 jar；`paideia_test` 库 |
| 管理端 e2e 前置 | 同上；管理端 vite 5174 |
| 构建 | `pnpm --filter @paideia/app build`（vite 8.3.4 / rolldown） |

**锚点之后改了什么（校验器会因此判 stale，这里如实说清）**：`git diff --name-only 3793a31..HEAD -- frontend/ backend/` **为空**——前端与后端源码一字未动。锚点之后只有三类变化：`.agent/` 工件、`.gitignore` 加了一行忽略规则、以及 `scripts/collect-md-corpus.mjs`（**离线采集语料的开发脚本**，不参与产品构建、不被任何测试引用）。所以上面所有证据对**当前源码**依然成立；校验器按机械规则（只认 `.agent/`）报 stale，属预期。

## 验证结果

**通过。** 助手回答现在按 markdown 渲染，且渲染器只剩一套——它从 `@paideia/knowledge` 挪进 `@paideia/ui`，知识库条目、管理端预览、助手回答共用；`react-markdown` / `remark-gfm` 两个依赖随之删除。

比"渲染出来了"更值得记的是**本项把"百分百"变成了可红的断言**：语料是真实上游的增量序列（离线固化进仓库），11 条语料 + 30 条恶劣输入**逐字符**回放，任何前缀的"已完成块"都等于最终渲染，**0 例不一致**；增量式的重渲染次数另有确定性计数（合成 20 段时每次新增最多 2 次），并与"每次从零挂载"的对照组拉开数量级。

顺带**修掉两处静默缺样式**——都是这次接线带进来、且控制台不会有任何提示的：`@source` 指到了不存在的目录（整行形同虚设，而且"看起来生效"，因为我自己的注释里恰好写了那个类名），以及 `streamdown` 在用、我们没定义的 `--color-sidebar` 令牌。两处都补了会失败的检查。

体积不是"变大了"：**主 chunk 1203 kB → 697 kB**（比接线前的 836 kB 还小 139 kB，因为顺手去掉了 react-markdown），渲染器成 505 kB 的按需 chunk。

## 验收矩阵

> 口径：**每个 `AC-*` 都在下面出现**，命令/退出码/结果/证据四列都不空。未能通过的部分**不在表里充数**，一律写进「失败与未验证项」。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 渲染生效 | `pnpm --filter @paideia/app test:e2e`（`markdown-render.spec.ts`） | 0 | passed | 33 passed / 0 failed（2.1 分钟）；断言条目正文里有真 `<table thead th>`×2、`tbody tr`×2 与 `[data-streamdown="code-block"]`，且代码块含语言名与正文 |
| AC-002 未闭合不破形 | `pnpm --filter @paideia/ui test`（`src/markdown/markdown.test.tsx`） | 0 | passed | 3 条：未闭合围栏 → **1 个**代码块；`正文\n\n```java\nclass A {` → 2 块且第一块仍是 `<p>正文。</p>`；未闭合强调不吞后续正文 |
| AC-003 逐前缀等价（0 例不一致） | 同上 | 0 | passed | 11 条真实语料（25–1658 个增量）全部 0 不一致；每条都断言 `checked > 0`（不允许"没有可比较的块"混过去）；反例自检：改一个块必须被判为不一致 |
| AC-004 增量真的省 | 同上 | 0 | passed | 合成 20 段：每次新增的重渲染 ≤2、总量 ≤2×(段数−1)；对照组"每次从零挂载" > 增量式×2；11 条语料：增量式 ≤ 重装甲，多块语料严格更便宜 |
| AC-005 降级可见且不跳 | 同上（`AC-005 降级` 一节，31 条用例） | 0 | passed | 30 条恶劣输入逐字符回放 **0 例不一致**；另两条：补出来的链接点它不调 `window.open`、补出来的图片是占位块而非真 `<img>`。**验收口径与工件原话不同，见「失败与未验证项」第 2 条** |
| AC-006 表格与代码块不闪（形态不反复） | `pnpm --filter @paideia/ui test`（逐前缀等价 + 30 条恶劣输入）。**e2e 级别未达** | 0 | passed | 41 段文本 ×（抽样 / 逐字符）前缀 → **0 例已完成块变形**。**工件要求的是 e2e，本项只做到单测 + 真浏览器定稿态**，见「失败与未验证项」第 1 条 |
| AC-007 安全回归 | 同上 | 0 | passed | 6 条，且**两种模式都查**：`<script>` 不进 DOM 也不执行、`javascript:` 不进 `<a href>` 也不进 `<img src>`、代码块里的 HTML 保持字面量 |
| AC-008 单一实现 | 同上 + `pnpm --filter @paideia/app build` | 0 | passed | 两种模式渲染同一 markdown 得到同一副元素骨架；表格与代码块连属性一致（唯一差别是流式下复制/下载 `disabled`，已显式断言）；`@paideia/knowledge` 已无 `react-markdown`/`remark-gfm` 依赖 |
| 体积：主包不因它变大 | `pnpm --filter @paideia/app build` | 0 | passed | 主 `index` **697.45 kB**（gzip 219 kB）+ 按需 `dist` **504.93 kB**（gzip 154 kB）；接线前主包 836 kB、接线未懒加载时 1203 kB |
| 样式真的生效（真浏览器） | `pnpm --filter @paideia/app test:e2e`（`markdown-render.spec.ts`） | 0 | passed | 行号伪元素 `content` 不是 `none`（该 class 只在它 dist 里）；代码块底色非透明且 ≠ `body` 底色；切深色后两者取值都变 |
| 令牌与扫描的守卫 | `pnpm --filter @paideia/ui test` | 0 | passed | `streamdown-tokens.test.ts`（2 条，逐个断言它用到的颜色令牌可解析）+ `source-coverage.test.ts`（新增 1 条：每个 `@source` 指向的目录必须真实存在） |
| 全仓类型与单测不回归 | `pnpm typecheck` 与 `pnpm test`（frontend 根） | 0 | passed | 两个命令退出码都是 0；单测 ui **93**（markdown 73 / tokens 1 / contrast 10 / theme-context 5 / streamdown-tokens 2 / source-coverage 2）、knowledge 20、assistant 16、auth 13 |
| 管理端草稿预览不回归 | `pnpm --filter @paideia/admin test:e2e` | 0 | passed | 8 passed / 0 failed（47.8 秒），含"录入到发布的链路"（草稿预览用的就是同一个渲染器） |
| 线上产物核对（只读） | `curl http://36.212.5.130:8081/` 与部署后的 CSS / 主 chunk | 0 | passed | CSS **88,493 字节**（与本地一致）且含 `--sdm-c`/`counter(line)`/`bg-sidebar`；主 chunk **697,457 字节**并引用按需 chunk |

## 反向验证（"这条要是坏了，哪个断言会红"）

| 被验行为 | 反着改一处 | 结果 |
| --- | --- | --- |
| `@source` 真的扫到了 streamdown 的类 | 把它指回仓库根的 `node_modules/streamdown/dist/*.js`（**接线时的真实写法，那里什么都没有**） | 构建产物里 `--sdm-c`、`counter(line)`、`contain-intrinsic-size` **全部 0 命中**，CSS 88.49 → **78.79 kB**；而 `bg-sidebar` 仍在（来自我自己的注释）——**这就是当时那次假绿**。修好后四者都出现 |
| `--color-sidebar` 令牌是必要的 | 删掉 `--color-sidebar` / `--color-sidebar-foreground` 两行 | 构建产物里 `bg-sidebar` 与 `border-sidebar` 规则数**都是 0**（Tailwind 只为已定义的主题值生成类，且不报错）；补回后 `.bg-sidebar{background-color:var(--muted)}` |
| AC-003 的比较不是恒真 | 把第二个块的文字改一个字 | 该用例**红**（`'<p>乙段</p>'` ≠ `'<p>乙段改了</p>'`），且这一条已固化成常驻用例 |
| AC-004 的计数确实在动 | 看对照组的数 | 从零挂载的次数随段数平方级增长，必须 > 增量式×2；两条都断言 `> 0`，计数器坏掉时会先红 |
| 行号的类真的来自 streamdown | 建一份不带 `@source` 那一行的产物 | 真浏览器里 `getComputedStyle(span, '::before').content` 会读到 `none`——jsdom 验不了这条，所以它是 e2e、不是单测 |

## 失败与未验证项

1. **AC-006「流式过程中表格/代码块不闪」没有 e2e 覆盖。** 既有学习者端 e2e **故意不调用真实上游**（它验的是"积分不足时不许打到上游""不会偷偷发起生成"），所以没有"真的在流式"的场景可断言。本项补了三条**真浏览器**断言，但打的是**定稿后的**正文：结构（真 `<table>`/真代码块）、计算样式（代码块底色非透明且不同于页面底色、行号伪元素有内容）、深浅两套主题。**"过程中不闪"这一条只有单测口径**（逐前缀等价 + `disabled` 的复制/下载），**登记为未覆盖**，不当作已验。要做它得先在 e2e 里造出"可控的流"（mock 上游或加流式夹具），那本身是一件独立的活。
2. **AC-005 的验收口径与工件原话不同，已登记**：需求写的是"构造一个补全不了的输入，断言走纯文本降级路径"。实际上游的降级机制是"**合并到安全边界 + 占位符**"（未闭合 HTML 合并下一块、奇数 `$$` 合并、脚注整篇一块），**没有"把尾巴当纯文本"这条路**；而我们实测 30 条恶劣输入逐字符 **0 例不一致**，即**没有触发降级**。所以按需求「什么情况下该重审」那条的口径处理：**用搜索代替写一个触发不了的分支**，并如实记下"本机未触发"。**这一条需要用户确认接受**（否则就是"验收标准与实现不符"）。
3. **深浅两套主题的观感未验**：只做到"计算样式确实换了一套取值、且都非透明"，**"好不好看"要用户真人看**——那是他的判据。
4. **上游一处小瑕疵，不修**：补全出未完成的图片时，它把占位块渲染成 `<div>` 而位置在 `<p>` 里，React 在开发模式报 `In HTML, <div> cannot be a descendant of <p>`。触发面是"模型正好吐到 `![](` 的那一帧"，用户看不到坏掉的东西。已记进 `.agent/notes/dev-workflow.md`。

## 剩余风险

- **上游演进**：`streamdown` 696 commits、发版很密。缓冲只有两条：钉版本 + 语料回归。升级命名版本前必须先跑语料与那 30 条恶劣输入。
- **两处按场景不同的策略**（故意，已在测试里钉住）：外链在流式回答里走"确认后打开"（模型生成的链接先问一次）、在长文里直接给 `<a>`（管理员写的）；表格的复制/下载下拉只给聊天场景。
- **另外两件不属于本项但会绊人的事**：① 学习者端 e2e 每条用例必须先 `signIn(page)`（只带 `storageState` 深链会从第二条起掉登录页，第 1 条过、2、3 条落登录页）；② `backend/config/.playwright-cli/page-*.yml` 四个文件没被 `.gitignore` 忽略（playwright-cli 的页面快照，谁 `git add -A` 都会带进公开仓库）。**建议加一条忽略规则**，本项未擅自改 `.gitignore`。

## 2026-10-17 重新核对（结算）

`@paideia/ui` 单测 93/0（含 markdown 四口径、语料与 30 条恶劣输入）；`markdown-render` e2e 3/0（表格与代码块是真结构、streamdown 的类生效、深浅两主题）；学习者端整套 e2e 34/1skip。锚点之后渲染器本身未被改动，后续变化是 WORK-013 的后端与记忆面板、以及本轮新增的测试用例。

## 2026-10-17 积分闸回滚修复后的复核

本轮改动落在 `ConversationService.prepare`：去掉 `@Transactional`，让用户消息先提交、再做积分预留，并把所有可能抛异常的读操作移到 `reserve` 之前（保证「预留之后不再有会抛的动作」）。另外 `AiCreditIntegrationTest` 原本断言「被拒的那一轮不留任何消息」，已改为「留用户消息、不留助手消息」——AC-003 只管上游没有收到请求，不要求丢用户输入。

本项相关证据随后重跑：`@paideia/ui` 93/0；`markdown-render` e2e 3/0（单跑两轮）；学习者端整套 e2e 35/0（两轮）。

锚点改绑到 `e941bcb`。

## 2026-10-17 测试基建改动后的复核

本轮改动是测试基建：测试库改本机、四个前端包换 happy-dom、会话集成测试改用同步执行器。
业务代码未变。重跑结果：后端全量 **257 条 / 0 失败 / 0 error / 4 跳过**（墙钟 1 分 31 秒），
前端单测 **176 条全过**，`typecheck` 干净。

锚点改绑到 `d184e36`（其后只有 `.agent/` 工件变化）。

## 复验记录（2026-10-18，锚点 0df2f9c）

**为什么会有这一段**：原报告的证据是在更早的提交上采的，之后仓库里又落了 WORK-014／015／016 三个工作项
（源码与配置都变过），校验器因此把本报告判为 `stale`。本轮在**当前 HEAD**（`9c99033`）上重跑了与本项
**受影响面**相关的那几组检查，逐条结果如下。**没有改任何功能代码**。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 逐前缀等价与安全 | `pnpm --filter @paideia/ui test` | 0 | passed | **148 passed**（含 markdown 73 条语料/恶劣输入用例，`renderers` 下 blocks/schema 另有 686 条断言） |
| 真浏览器样式 | `pnpm --filter @paideia/app test:e2e` | 0 | passed | 60 passed / 2 skipped / 0 failed |
| 单一实现与体积 | `pnpm typecheck` | 0 | passed | typecheck 0；渲染器仍是 `@paideia/ui` 唯一实现 |

**这段区间里本项的受影响面**：**渲染器被大幅扩展**：`@paideia/ui` 的 `markdown.tsx` 增加 `renderers` 注册表与七个平台特有块（quiz／code-tour／code-map／run-challenge／code-challenge／learning-path／blocks），新增 `schema.ts`（532 行）。这是 WORK-014/015 的接入，**本项定的三条口径与"单一实现"结构未变**。

**结论**：本项建立的"一个组件两种 mode + 逐前缀等价 + 单一实现"结构在扩展七个渲染器之后仍然成立：语料回归 73 条全绿、安全断言未动。**体积数字需要重测**（新增七个渲染器 + streamdown 依赖让包变大），那属 WORK-014/015 的账，本项不重复认领。
