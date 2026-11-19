---
title: 自己写一个 skill
category: vibe-coding-from-zero
order: 8
chapter: Skills 与工程纪律
minutes: 22
level: intermediate
status: published
---

## 三个能打开的页面，导航里一个都没有

先跑一条命令，看现在仓库里的三条路由：

```text
rg -n "path: '/(learn|graph|practice/sql)'" frontend/apps/app/src/router.tsx
```

```text
84:    path: '/learn',
208:    path: '/practice/sql',
226:    path: '/graph',
```

再跑一条，看导航：

```text
rg -n "to: '/(learn|graph|practice/sql)'" frontend/apps/app/src/components/LearnerHeader.tsx
```

```text
58:  { key: 'learn' as const, group: 'learn' as const, to: '/learn', label: '从零开始', testId: 'learner-nav-learn' },
75:  { key: 'graph' as const, group: 'knowledge' as const, to: '/graph', label: '知识图谱', testId: 'learner-nav-graph' },
77:  { key: 'sql-practice' as const, group: 'practice' as const, to: '/practice/sql', label: 'SQL 练习', testId: 'learner-nav-sql-practice' },
```

这是今天的输出；现在 NAV 里三条都有，是事后补的。行号以后会变，命令不变。2026-10-22 那次，这三条路由都在，导航里一条都没有。页面能直达、内容齐全，但用户不会猜 URL，看到的就是「线上内容少了一大半」。仓库的协作说明（`AGENTS.md`）把这次事故记成了一条规则：加了页面就必须加入口，只在首页快捷卡里出现不算。

我原来的判断是「页面能打开」就等于「上线了」。实际是：**入口才是上线**。而「新增路由要检查入口」这件事当时靠人记，漏了一次。

能不能让 agent 每次都做？可以，但要把它写成 skill，而不是再写一句「记得检查」。

## 先判断：这件事值不值得做成 skill

不是所有「我做过一次」的流程都该变成 skill。我用的门槛是四个问题，四个都为「是」才做：

1. 不写下来，下次会不会漏？（非显而易见）
2. 同类任务还会不会再来？（会反复参考）
3. 换个项目、换个会话还成立吗？（跨项目适用）
4. 团队里第二个人照这份说明也能做对吗？（别人也受益）

只满足前两条、且只在一个仓库成立，写进那个仓库的 `AGENTS.md` 或 CI；只做一次，留在 prompt。项目专属的一条路径检查、一次性的数据迁移、CI 已经能机械判定的规则，都不做成个人 skill。

这是我自己的经验阈值，不是 Agent Skills 规范的一部分。规范只管文件长什么样，不管什么值得写。

## 规范：一个目录，一个 SKILL.md

网上常把「description、步骤、检查清单、反例」叫四件套。那是正文写法建议，不是安装规范。安装规范只有一条：**一个目录，里面一个 `SKILL.md`**。`scripts/`、`references/`、`assets/` 都是可选目录，不是必需。

Agent Skills 规范（`agentskills.io/specification`，2026-11-19 读取）对 frontmatter 的约束：

| 字段 | 级别 | 约束 |
| --- | --- | --- |
| `name` | 必需 | 1–64 字符；小写字母、数字、连字符；不能以连字符开头或结尾；不能有连续两个连字符；必须与父目录同名 |
| `description` | 必需 | 1–1024 字符；写清「做什么」和「什么时候用」 |
| `license` | 可选 | 许可证名称，或指向目录里的许可证文件 |
| `compatibility` | 可选 | 最多 500 字符；环境要求，比如需要什么系统包、网络 |
| `metadata` | 可选 | 字符串键值对，给客户端存额外信息 |
| `allowed-tools` | 可选 | 空格分隔的预授权工具列表；规范标为实验性，各家支持不一 |

推荐但不是 frontmatter 字段：正文里的步骤、完成条件、不要用的情况；可选目录 `scripts/`、`references/`、`assets/`。

Claude Code 还有自己的扩展字段（例如控制是否允许模型自动调用），那些不在 Agent Skills 规范里，换客户端不一定认。Codex 也认 `metadata` 这类可选字段，但不要把它当成跨客户端的保证。

正文没有格式限制。规范建议的步骤、示例、边界情况只是建议；真正要守的是 `name` 和 `description` 的约束，以及「目录名与 `name` 一致」。

### 装到哪里：Claude Code 和 Codex 不是同一个目录

| 客户端 | 个人 skill | 仓库 skill |
| --- | --- | --- |
| Claude Code | `~/.claude/skills/<name>/SKILL.md` | `.claude/skills/<name>/SKILL.md` |
| Codex | `~/.agents/skills/<name>/SKILL.md` | `.agents/skills/<name>/SKILL.md` |

Windows 上把 `~` 换成 `C:\Users\<你的用户名>`。路径来源：Claude Code 文档 `code.claude.com/docs/en/skills`；OpenAI 的 Build skills 文档 `learn.chatgpt.com/docs/build-skills`。

这里有一个不确定点要说明：OpenAI 文档当前的本地加载表列的是 `.agents/skills`，但这台机器的 Codex 还会从 `C:\Users\Lenovo\.codex\skills` 读。两个位置我都见过；装完用 Codex 的 `/skills` 列表确认，不要只凭路径猜。Claude Code 的 `.claude/skills` 也不能直接复制到 `.agents/skills` 就指望 Codex 认——两个客户端各读各的。

二选一建一份。同时建两份同名 skill，列表里可能出现两个同名项，改了一份另一份不生效。

在仓库根目录执行（Codex 仓库级）：

```text
New-Item -ItemType Directory -Force ".agents\skills\pre-commit-check" | Out-Null
```

Claude Code 个人级：

```text
New-Item -ItemType Directory -Force "$HOME\.claude\skills\pre-commit-check" | Out-Null
```

把下面整段存成 `SKILL.md`。路径是 `.agents\skills\pre-commit-check\SKILL.md`（Claude Code 换成 `.claude\skills\pre-commit-check\SKILL.md`）。

```markdown
---
name: pre-commit-check
description: 当用户准备提交代码、让你检查暂存区，或问「这次改动能不能提交」时使用。只问 Git 命令用法、提交信息文案或 rebase 原理时不要用。
---

# 提交前检查

按顺序做完。任何一步失败就停下并报告，不要跳到下一步。

## 1. 取全量改动清单

在仓库根目录运行这四条，四条都要看：

- `git status --short`：总览，包含已暂存、未暂存、未跟踪。
- `git diff`：只看未暂存。
- `git diff --cached`：只看已暂存。
- `git ls-files --others --exclude-standard`：只看未跟踪、且没被 `.gitignore` 忽略的文件。

把结果分成三组：已暂存、未暂存、未跟踪。报告里写清每个文件属于哪组。同一个文件可能同时在两组里（`MM`），不要只报一次就漏掉另一组。

`git diff --stat` 只是未暂存的摘要，不能替代上面四条。

## 2. 对照改动范围

逐文件说明它为什么在本次改动里。出现下面任一情况就停下问用户：

- 有和任务无关的文件；
- 有未跟踪文件，但用户没提过；
- 有删除、重命名或权限变更。

## 3. 跑受影响的测试

按仓库说明选择测试命令。只跑改动那一端、受影响的文件。

Paideia 仓库的数据库测试走 `scripts/test-db.sh`，不要直接跑 `mvn test` 或 `pnpm test:e2e`。

贴真实输出。没有输出、或者输出里没有通过/失败统计，视为没跑。

## 4. 查新增页面的入口

如果 `router.tsx` 新增了用户可见路由，逐个确认它在导航栏或账号菜单里有入口。只在首页快捷卡里出现不算。

Paideia 仓库执行：

- `rg -n "path: '" frontend/apps/app/src/router.tsx`
- `rg -n "to: '" frontend/apps/app/src/components/LearnerHeader.tsx`

两边按路径逐条对照，把没有入口的路由列出来。

## 5. 扫描敏感信息

扫三处：`git diff` 的未暂存改动、`git diff --cached` 的已暂存改动、以及 `git ls-files --others --exclude-standard` 列出的未跟踪文件内容。搜密钥、令牌、密码、私钥、数据库连接串、内网地址。不要只扫工作区文件，也不要只扫暂存区。

发现疑似内容就停下，指出文件和行号，不要复述完整值。

## 6. 检查提交信息

先读仓库最近的提交：

- `git log -20 --pretty=format:%s`

按最近的实际格式写。Paideia 仓库当前是 `type: 描述`；有单号时才加，例如 `#123`，工作项写成 `（WORK-123）`。没有单号不要硬编。

可用 type 以仓库最近提交为准。常见的有 `feat`、`fix`、`refactor`、`docs`、`test`、`chore`、`perf`、`content`。

## 完成条件

- [ ] 四类改动都取到：状态总览、未暂存、已暂存、未跟踪
- [ ] 每个改动文件都解释过为什么在本次范围
- [ ] 受影响的测试跑了，贴了真实输出
- [ ] 新增路由都有导航或账号菜单入口
- [ ] 未暂存、已暂存、未跟踪三处都扫过敏感信息
- [ ] 提交信息符合仓库最近的实际格式

## 不要用

- 只是本地试验、没打算提交；
- 用户明确说先别检查；
- 要检查的是整个历史或远端分支，不是本次改动。
```

第 3、4 步带 Paideia 专属命令；换仓库时把测试命令和入口检查换掉，其余步骤照用。如果你只在这一仓库做提交检查，把它放进仓库级目录，不要放个人目录。只有入口检查这一步、没有其余步骤，也别做个人 skill——那是仓库规则。

## 正文：每一步都要有命令、产物、停点

`description` 只决定「会不会被加载」；加载之后照不照做，看正文。正文里最没用的句子是「注意质量」「尽量做好」——模型只能回你一句「已经注意了」，你没法判真假。

把「检查一下」拆成可观察的动作：

| 不可执行 | 可执行 |
| --- | --- |
| 检查改动是否合理 | 运行四条命令，把文件分成已暂存、未暂存、未跟踪 |
| 确保测试通过 | 运行指定测试，贴出通过/失败统计；失败就停 |
| 注意不要泄露密钥 | 扫未暂存 diff、已暂存 diff、未跟踪文件；发现就停下并报文件:行号 |
| 提交信息要规范 | 先读 `git log -20 --pretty=format:%s`，按最近格式写 |

每条步骤至少有三样：**命令或路径**、**期望产物**、**失败时停在哪**。缺一样，agent 就可能用一句「已完成」糊过去。

取改动清单这件事要单独说，因为原稿就在这里漏了：`git diff --stat` 和 `git diff` 都只覆盖未暂存。已暂存的要用 `git diff --cached`，未跟踪的要用 `git ls-files --others --exclude-standard`；`git status --short` 只做总览。少一条，就会出现「检查过了，但没看到那个文件」。扫描时也别只打开工作区里的文件：已暂存但之后又在工作区改过的内容，磁盘上看不到。

反例也要写。不是写「谨慎使用」，而是写「什么输入下不要用」：只问 Git 命令用法、只问提交信息文案、只是本地试验没打算提交——这些都不该触发这个 skill。

## 测试：触发是一件事，执行是另一件事

「拿几个输入看命中率」这句话不够。skill 有两个独立的失败点：

| 测试 | 回答的问题 | 观察点 | 失败先改哪 |
| --- | --- | --- | --- |
| 触发测试 | 该加载时加载了吗？不该加载时没加载吗？ | 调用记录；没有记录就在 `SKILL.md` 正文放一个固定标记，加载后问它标记是什么 | `description` |
| 执行测试 | 加载后真的照做了吗？ | 产物：四条命令的输出、文件分组、有没有指出未跟踪文件、有没有在敏感串处停下 | 正文 |

### 触发测试

准备四类输入：

- 该触发，说法 A：「提交前帮我检查一下」；
- 该触发，说法 B：「这次改动能不能提交」；
- 不该触发，相邻但不同任务：「解释一下 git rebase 原理」；
- 不该触发，只问格式：「提交信息怎么写」。

每个输入重复 N 次，记「命中次数/N」。正例记「加载次数」，反例记「没加载次数」；两者都写成命中次数/N，别把反例的 0/5 看成失败。N 要写出来；我一般先用 5 次筛明显问题，但 5 次不是统计显著，不能拿它报一个精确百分比。

观察点优先用客户端自己的调用记录。拿不到记录，就在 `SKILL.md` 正文第一行放一个固定标记，例如 `TRIGGER-MARKER: pre-commit-check-v1`，然后问 agent「你刚加载的 skill 里，TRIGGER-MARKER 是什么？」。答得出来说明正文进了上下文；答不出来说明没加载。测试时不要让它直接读文件，否则会把「读文件」误当成「加载 skill」。

### 执行测试

触发成功之后，才测执行。准备一个临时仓库，放四类文件：

- 只有未暂存改动；
- 只有已暂存改动；
- 只有未跟踪文件；
- 一个未跟踪的假密钥文件，例如内容写 `token=sk-test-123`。

然后说「提交前检查一下」。合格的输出至少要做到：

- 四类清单都出现，不只说「看过了」；
- 未跟踪文件被点名；
- 假密钥被指出文件和行号，且没有把完整值复述出来；
- 有无关文件时，明确指出它不在本次范围。

每个场景也重复 N 次，记「通过次数/N」。执行失败先改正文：补命令、补停点、补完成条件；不要只改 `description`——描述管加载，管不了它加载后怎么做。

### 四个例子只是冒烟

四个输入只能证明「不是完全不工作」，不能给你一个可信的命中率。要报命中率，就写清每个例子的分母和重复次数；要比较两个版本，就用同一组输入、同样的 N，分别记触发和执行，不要混成一个总分。

## 提交规范是团队的，不是通用的

原稿把 `<type>: #<单号> <描述>` 当成通用格式。这个仓库不是这样：最近的提交是 `content: ...`、`feat(scope): ...`、`fix: ...`，没有单号。照抄 `<type>: #<单号> <描述>` 会被判失败。

可判定的做法是先读再写：

- `git log -20 --pretty=format:%s`

Paideia 的规则是 `type: 描述`；有单号才加，例如 `#123`，本仓库的工作项写成 `（WORK-084）`。可用 type 以最近提交为准，常见的有 `feat`、`fix`、`refactor`、`docs`、`test`、`chore`、`perf`、`content`。

换一个团队，可能是 Conventional Commits 的 `type(scope): description` 加 `Refs: #123`，也可能是 `[JIRA-123]` 开头。别把某一家的格式写死进 skill；把它写成「先读仓库最近 20 条提交，按实际格式写」。

团队共用的检查放仓库级目录（`.agents/skills` 或 `.claude/skills`），跟代码一起 review；个人习惯放用户级目录。skill 文件本身会被提交，别把令牌、内网地址写进去。

## 检查一下

```quiz
{"id":"vc0-08-quiz","title":"能加载，和真的照做","questions":[
 {"question":"一个 skill 的目录叫 pre-commit-check，frontmatter 里的 name 应该是什么？","options":["pre_commit_check","PreCommitCheck","pre-commit-check","可以省略，靠目录名"],"answer":2,"explain":"Agent Skills 规范要求 name 与父目录同名，只允许小写字母、数字和连字符，1–64 字符。"},
 {"question":"「该加载时加载了吗」和「加载后照做了吗」应该怎么测？","options":["分开测：前者看调用记录或固定标记，后者看实际产物和步骤输出","合成一个测试，看最后结果对不对","只测触发，执行靠模型自觉","只测执行，触发不重要"],"answer":0,"explain":"触发与执行是两个失败点：没加载要改 description，加载了没照做要改正文；混在一起测，失败时不知道该改哪。"},
 {"question":"在 Paideia 仓库写提交信息，下面哪种说法对？","options":["必须写成 type: #123 描述，没有单号也要编一个","统一写 update: 描述","只能用中文，不能用 type","type: 描述；有单号才加，type 以仓库最近提交为准"],"answer":3,"explain":"这个仓库近期的提交是 content: ...、feat(scope): ...、fix: ...，没有强制单号；有工作项时才写（WORK-xxx）或 #123，格式跟着仓库走。"}
]}
```

## 还差的

- 我没有在 Claude Code 和 Codex 两个客户端上各跑一遍触发测试；路径和加载行为可能不同，这篇只按文档和本机目录核对。
- 触发测试的固定标记法依赖模型如实回报；有调用日志的客户端应该优先用日志。
- `N=5` 只能筛明显问题，不能给置信区间；要对外报精确命中率，需要更大的样本和写清分母。
- 没有覆盖多个 skill 同时竞争触发的情况；装得越多，`description` 之间的边界越重要。
