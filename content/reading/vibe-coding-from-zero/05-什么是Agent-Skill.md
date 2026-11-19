---
title: 什么是 Agent Skill
category: vibe-coding-from-zero
order: 5
chapter: Skills 与工程纪律
minutes: 18
level: intermediate
status: published
---

## 6,492 个字符常驻，137,378 个字符按需

我原来以为「按需加载」的意思是：装了 skill，它就不占上下文，要用才进来。数完发现只对一半。

把本机 23 个 skill 各扫一遍，把 frontmatter 里的 `description` 和 `SKILL.md` 正文分开数：

```js
// $env:TEMP\skill-cost.mjs
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const ROOT = join(process.env.USERPROFILE, '.dsh', 'skills')
const SKIP = new Set(['__pycache__', '.mypy_cache', 'node_modules', '.git', '.venv'])
const walk = d => readdirSync(d, { withFileTypes: true }).flatMap(e =>
  SKIP.has(e.name) ? [] : e.isDirectory() ? walk(join(d, e.name)) : [join(d, e.name)])

let desc = 0, body = 0, files = 0, n = 0
for (const name of readdirSync(ROOT)) {
  const all = walk(join(ROOT, name))
  const f = all.find(x => x.toLowerCase().endsWith('skill.md'))
  if (!f) continue
  n++; files += all.length
  const text = readFileSync(f, 'utf8')
  const end = text.indexOf('\n---', 3)
  const m = text.slice(0, end).match(/description:\s*[|>]?\s*([\s\S]*?)(?=\n[a-z-]+:|$)/i)
  desc += (m ? m[1] : '').replace(/\s+/g, ' ').trim().length
  body += text.slice(end).length
}
console.log({ skills: n, descChars: desc, bodyChars: body, files })
```

```text
{ skills: 23, descChars: 6492, bodyChars: 137378, files: 570 }
desc/body = 4.7%
```

6,492 个字符是每次会话都在的——技能目录里那行「名字 + 一句描述」。137,378 个字符的正文不在，要它真的被加载才进上下文。两个数差 21 倍（137378 / 6492）。

所以「按需加载」省掉的是正文，不是技能本身：**只要装上，`description` 就一直占着位置**。这条把成本从「一篇写多长」挪到了「一共装了多少个」——本机 23 条平均 282 字符一条，装到 100 个就是 2.8 万字符上下。这是拿本机平均值线性外推，不是实测。

顺手记一个口径问题：第一次数出来的文件数是 598，把 `__pycache__`、`.mypy_cache` 摘掉才是 570。数技能目录之前，先说清算不算缓存。

## 855 个字符的描述，背后 210 个文件

本机描述最长的一条是 `modern-web-guidance`：

```text
~/.dsh/skills/modern-web-guidance/SKILL.md:3-6（节选，省略号和「30 多个名词」是我加的）
description: |
  Search tool for modern web development best practices. MANDATORY: Execute FIRST for
  all HTML/CSS and clientside JS tasks. Do NOT skip — web APIs evolve rapidly and
  training weights contain obsolete patterns.
  Trigger immediately for: modals/dialogs/popovers, tables, dark mode, anchor positioning,
  container queries, view transitions, scroll-driven animations, ...（30 多个名词）
  DO NOT trigger for: backend (SQL, ORMs, API routes), infra/scripts (CI/CD, Docker,
  Actions, Python/Go, Git), or tooling/trivial (Vite, tsconfig, ESLint, tests, ...)
```

三段各干一件事：一句「我是干什么的」、一张「用户说到这些词就该找我」的清单、一张「别找我」的清单。最后那段尤其值钱——**选择发生在读正文之前**，边界不写进描述，正文写多好都不参与这次选择。

它挂着 210 个 `.md`，按主题分在 `guides/` 下，全是正文点到才读的参考。描述第二长的 `diagram-design`（805 字符）文件更多——226 个，其中 165 个 html、57 个 md、4 个 py。两条都指向同一件事：把「什么时候该找我」写厚，因为那是唯一每次都被读的部分。

再看反方向的 `project-lifecycle`，描述只有 165 字符，由七个用户短语组成：

```text
~/.dsh/skills/project-lifecycle/SKILL.md:3
description: 用户说“写需求”“把需求问透”“拆任务”“验收”“继续上次的活”“归档”“看项目状态”时使用；把需求、决策、任务和验证证据写入项目 `.agent/` 工件……
```

正文很短，线头都指向 `references/`：

```text
~/.dsh/skills/project-lifecycle/SKILL.md:17（原文一行，这里折了一下）
每轮动作、恢复顺序、阶段门槛、签署、关系、规则优先级、规格与决策说明、归档统一维护在
`references/workflow.md`，本页不重复。
```

于是它是三层的：`description`（每次都在）→ `SKILL.md` 正文（触发后读）→ `references/*.md`（正文点到才读）。它 17 个文件里 6 个是 `references/`、1 个是 `examples/`，其余 10 个是脚本和入口。

这三层是这份文件的写法，不是安装要求——安装要求只有一个目录、一个 `SKILL.md`，那是后面《自己写一个 skill》的事。但写法背后有它成立的理由：同一份东西，触发层、主线、细节能分开维护、分开 review。本机 23 条里有 20 条是符号链接：

```text
(Get-ChildItem ~\.dsh\skills | Where-Object LinkType -eq 'SymbolicLink').Count
```

```text
20
```

技能可以是别的仓库里同一份文件链过来的，好处是改一处、两处都生效，代价是行号会跟着源仓库漂——所以上面那些行号，记的是我 2026-11-19 读到的位置。

## 同一条命令，在终端里是工具，写进 skill 才是技能

Paideia 的 `AGENTS.md:36` 有一条（节选）：

```text
- **迁移文件名用 14 位时间戳**：…… 动 schema 前先
  `git log --all --oneline -- <迁移目录>` 看有没有人在改同一张表
```

`git` 是工具：能执行、有输出、会失败。「动表之前先查谁在动同一张表、看到并发就停」是技能：它规定什么时候跑、跑完看什么、看到什么算什么。

两者不能互换。给模型再多的工具，它也不知道「什么时候该查」；给它一段再对的纪律，没有执行工具它也查不动。把工具比作电钻、把技能比作贴在工具箱里的作业规程，这个分法是对的，但容易漏掉后半句：**skill 文件本身不执行任何东西**，它不调工具、不跑命令，只是一段会被读进上下文的文本。

这也决定了它挡不住什么：模型可以读了第 1 步、跳过第 3 步。它不是执行引擎。它做的是把「该检查什么」放到模型眼前，并且——如果每一步都要求产物或检查项——让漏掉的步骤变成一个能被指出来的空位。写成「注意质量」的那一步，漏了也看不出来。

## 症状 → 先看哪

| 症状 | 先看哪 | 为什么是这里 |
| --- | --- | --- |
| 装了，该用的时候从不被读 | frontmatter 的 `description` | 决定调不调的是这一段；正文没被读进来就不参与。看里面有没有用户会说的那句话，或用户会打的名词 |
| 两个 skill 抢同一件事，总选错 | 两条 `description` 的边界，不是正文 | 选择发生在读正文之前，正文写得再好也不参与这次选择；越界写进描述（`DO NOT trigger for` 就是干这个的） |
| 被读了，但仍然漏步骤 | 正文是「步骤 + 产物」还是「原则」 | 只有能落成产物或检查项的步骤才可能被执行；「注意质量」等于没写 |
| 上下文越用越满、回答变钝 | 常驻的那部分，不是正文 | 本机 23 条描述 6,492 字符常驻；正文不加载就不占 |

我用一条很粗的正则扫过本机 23 条描述：里面有没有出现「用户说」「Use when」「Trigger for」这类触发语。4 条没匹配上。逐条看，`bep-dev-workflow` 写的是「触发词：开始今天的工作、查我的待办……」，只是换了说法；另外三条靠主机名或图类型名词（`diagram-design` 那 805 字符基本是流程图、时序图、ER 图这类名词），也算线索。

所以这条正则只能筛出「值得再看一眼」的，判不了对错。真正的判据还是那一句：**用户会不会把这句话说出口。** 描述里写「帮助提高效率」，没人会这么说；写「拆任务」「上传到我的网盘」，那就是用户的原话。

## 动手

三条命令，Windows PowerShell：

```text
(Get-ChildItem ~\.dsh\skills -Directory).Count
```

```text
23
```

```text
Get-Content ~\.dsh\skills\project-lifecycle\SKILL.md -TotalCount 3
```

```text
---
name: project-lifecycle
description: 用户说“写需求”“把需求问透”……
```

（截断后的样子；原文一行写完这七个短语。单行和 `>` 折叠两种写法本机都有，用哪种都不影响触发。）

```text
Select-String -Path ~\.dsh\skills\project-lifecycle\SKILL.md -Pattern 'references/'
```

```text
17:每轮动作、恢复顺序、阶段门槛、签署、关系、规则优先级、规格与决策说明、归档统一维护在 `references/workflow.md`，本页不重复。
22:- **无证据不算通过**：没有新鲜跑过的命令输出，就不许声称完成；跳过的测试不等于通过；……
27:写 `design.md` 前读 `references/design.md` 的「变更清单」，最常被跳过的两条：……
```

然后打开你自己最常用的那个 skill，只读它的 frontmatter，问一句：这里面有没有一句是我真的会对它说的话？如果整段都是「为你提供专业帮助」这类自述，那它现在还没有触发线索——不是模型不听话，是没人知道什么时候该找它。

## 检查一下

```quiz
{"id":"vc0-05-quiz","title":"常驻的、触发的、执行的三件事","questions":[
 {"question":"「按需加载」在这台机器上具体指什么？","options":["整个 skill 目录都进上下文，调用时再读一遍","正文常驻，只把 description 按需取回","只有 name 和 description 常驻，正文要加载才进上下文","第一次读完就缓存，之后不再读"],"answer":2,"explain":"本机 23 个 skill 的 description 合计 6492 个字符，SKILL.md 正文合计 137378 个字符；常驻的是前者，后者要加载才进上下文。所以「按需」省的是正文，不是技能本身。"},
 {"question":"装了一个 skill，但该用的场合它从没被读。第一步看哪里？","options":["frontmatter 里的 description，看有没有用户真会说的那句话","先把正文步骤写得更详细","换一个更大的模型再试","复制一份、换个名字重新装"],"answer":0,"explain":"调不调由 name 加 description 决定；正文再详细，没被读进来就不参与。正文的详细程度影响的是「读了之后照不照做」，不是「读不读」。"},
 {"question":"关于「写了 skill，模型就一定按步骤做」这句话：","options":["对，skill 是强制执行机制，写了一定照做","对，frontmatter 里的步骤会被主机硬校验","不对，所以 skill 不如写进系统提示","不对，它只是被读进上下文的文本，漏步骤要靠产物和检查项暴露出来"],"answer":3,"explain":"skill 文件不执行任何东西，执行靠模型加工具。它能把该检查的东西放到模型眼前，但挡不住跳步；让每一步都有产物或检查项，漏了才看得出来。"}
]}
```

## 还差的

- 6,492 / 137,378 是这台机器 2026-11-19 的值，不是规格。装得更多，描述总量会跟着涨；「100 个约 2.8 万字符」是线性外推，没有实测。
- 加载机制我按本机的 DSH 核的：提示里只带名字和一句摘要，正文要调 `skill` 工具才进来。Claude Code、Codex 的目录位置与注入方式不同，照抄路径会失败。
- 触发成功率没测。我只查了描述里有没有触发线索，没有拿一批任务去统计「该调用时调用了没有」——那需要固定标记和样本，属于后面写 skill 那篇。
- 表格里的因果是推理，不是对照实验：没做「同一任务、有 skill / 没 skill」的比较，所以「漏步骤少了多少」我给不出数字。
- 多个 skill 同时命中时宿主怎么排序，我没查到：目录交给模型自己挑，宿主侧没有暴露打分逻辑。
