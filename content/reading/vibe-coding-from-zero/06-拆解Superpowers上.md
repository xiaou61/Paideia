---
title: 拆解 Superpowers（上）：把工程纪律写成技能
category: vibe-coding-from-zero
order: 6
chapter: Skills 与工程纪律
minutes: 18
level: intermediate
status: published
---

## 会话一开，钩子先塞进来 5,632 个字符

我原来以为 skill 是被「匹配」上的：模型读你这句话，扫一遍各技能的 `description`，觉得对得上，再把那份 `SKILL.md` 读进来。装上 superpowers 之后，我把它会话启动的那条钩子单独跑了一遍，发现入口根本不是这么进来的。

```text
$root = "$env:USERPROFILE\.claude\plugins\cache\claude-plugins-official\superpowers\5.1.0"
$env:CLAUDE_PLUGIN_ROOT = $root
$ctx = (& "C:\Program Files\Git\bin\bash.exe" "$root\hooks\session-start" | ConvertFrom-Json).hookSpecificOutput.additionalContext
$ctx.Length
```

```text
5632
```

（`bash.exe` 是 Git for Windows 的；插件自己的 `hooks/run-hook.cmd` 也是先在这两个位置找 bash，找不到就静默退出。）

这段 5,632 个字符开头是这样，`\n` 是原文里的转义、不是我加的：

```text
<EXTREMELY_IMPORTANT>\nYou have superpowers.\n\n**Below is the full content of your
'superpowers:using-superpowers' skill - your introduction to using skills. For all other
skills, use the 'Skill' tool:**\n\n---\nname: using-superpowers\n...
```

它就是 `skills/using-superpowers/SKILL.md` 的整篇正文。（上面为了放得下按宽度折了行，原文是连续的一行 JSON 字符串，内容没改。）顺着调用链读下去只有三步：

- `hooks/hooks.json:3-13`：只在 `SessionStart` 上挂了一个钩子，matcher 是 `startup|clear|compact`，执行 `hooks/run-hook.cmd session-start`；
- `hooks/session-start:18`：`using_superpowers_content=$(cat "${PLUGIN_ROOT}/skills/using-superpowers/SKILL.md")`——就是 cat；
- 同文件 `:35` 把它包进 `<EXTREMELY_IMPORTANT>`，`:51` 作为 `hookSpecificOutput.additionalContext` 打出来。

所以「自动触发」在入口这一层是**开机自启**，跟描述匹配没关系。matcher 里那个 `compact` 值得单独看一眼：上下文压缩之后它再注一遍，同一段 5,632 个字符，压一次付一次。

另外 13 个技能不在里面，实测：

```text
$ctx.Contains('test-driven-development')   # False
$ctx.Contains('systematic-debugging')      # False
```

它们的正文要模型主动调 `Skill` 工具才进来——`skills/using-superpowers/SKILL.md:30-36` 分别写了 Claude Code、Copilot CLI、Gemini CLI 各用哪个工具取。这部分与上一关量的口径一致：常驻的是名字加一句描述，正文按需加载。

## 那 13 个什么时候进来：从「卡在哪」反查

我拿自己练手项目里一件小事走一遍：给一个列表页加「导出 CSV」。它会在几个地方卡住，每个卡点对应一个技能，而且每个技能都要求交出一件能摆出来看的东西。

| 卡在哪 | 对应技能 | 它要你交出来的产物 |
| --- | --- | --- |
| 你说「加个导出」，模型直接改代码，导出的格式和你脑子里那个不一样 | `brainstorming` | 一份你签过字的设计；没签字不进下一步 |
| 计划只在脑子里，做完才发现漏了中文表头 | `writing-plans` | `docs/superpowers/plans/YYYY-MM-DD-<名字>.md`；每一步只做一件事、2–5 分钟（`skills/writing-plans/SKILL.md:18`、`:38-43`） |
| 代码先写完、测试后补，跑不通就把断言改了 | `test-driven-development` | 一条**先红过**的测试输出。它有前提，下一节说 |
| 导出对不上，改了三个地方还是对不上 | `systematic-debugging` | 四个阶段走完：Root Cause Investigation → Pattern Analysis → Hypothesis and Testing → Implementation（`SKILL.md:50`、`:122`、`:145`、`:170`） |
| 说「好了」，其实只跑了一个用例 | `verification-before-completion` | 本轮跑的命令全文、退出码、失败计数（`:18-20`、`:26-38`） |
| 分支开了三个，不知道哪个该合 | `finishing-a-development-branch` | 只在 `.worktrees/` 里清，别处不动（`RELEASE-NOTES.md:18`） |

表里六行，本机实际 14 个，剩下八个是执行、评审和环境类：`executing-plans`、`subagent-driven-development`、`dispatching-parallel-agents`、`requesting-code-review`、`receiving-code-review`、`using-git-worktrees`、`writing-skills`、`using-superpowers`。

「产物」这一列不是修辞。`verification-before-completion` 的原文（`:22`）是：如果你没有**在这条回复里**跑过验证命令，就不能声称它通过。它不接受「我检查过了」，只接受这一次的输出。

## 版本对不上的三处

这篇原文写的是「v7.0.2，commit `22f0b463`，核对于 2026-11-03」。这台机器上我找不到能对上这个版本的东西。能对上的是：

```text
git -C $root log -1 --format="%h %ci %s"
```

```text
f2cbfbe 2026-05-04 15:05:01 -0700 Release v5.1.0 (#1468)
```

`~/.claude/plugins/installed_plugins.json` 里这一条的 `version` 是 `5.1.0`、`gitCommitSha` 是同一个 `f2cbfbe...`、`installedAt` 是 2026-06-04；`.claude-plugin/plugin.json:4` 也是 `5.1.0`。下面按 5.1.0 说，和旧教程不一致的地方逐条标出。

1. **三个斜杠命令没有了。** `/brainstorm`、`/execute-plan`、`/write-plan` 在 5.1.0 被删（`RELEASE-NOTES.md:7`），现在要直接调 `superpowers:brainstorming` 这类技能名。你在旧帖子里看到的这三条命令敲下去没反应，不是装坏了。
2. **`using-git-worktrees` 不再自动建工作区。** `RELEASE-NOTES.md:16` 写明它现在先问用户（之前子智能体那条链路不打招呼就建），宿主自己带 worktree 工具时优先用宿主的（`:17`）。所以「装了它就自动给你拉隔离分支」方向是反的：默认先问一句。
3. **`superpowers:code-reviewer` 这个具名 agent 删了**（`RELEASE-NOTES.md:8`），改成 `general-purpose` 加一份提示模板，模板在 `skills/requesting-code-review/code-reviewer.md`。手里还留着 `Task (superpowers:code-reviewer)` 的链条会断。

## 铁律有例外，而且它不负责执行

`README.md:19` 说技能会自动触发、你不用做特别的事，`:170` 说这是强制工作流、不是建议。读起来像引擎，实际是一段文本加一堆写得很硬的祈使句。`skills/using-superpowers/SKILL.md:11`：

```text
If you think there is even a 1% chance a skill might apply to what you are doing,
you ABSOLUTELY MUST invoke the skill.
```

`:46` 是规矩本身：任何回复或动作之前，先调用相关技能。这些话是写给模型读的。读完谁执行第几步、跳过第几步，没有东西拦得住。**拦得住的是产物**——先红过的测试、`docs/superpowers/plans/` 下那份文件、本轮的命令输出。少一样，你就知道某一步被跳过了。这也解释了 `README.md:200-203` 那四条哲学里，为什么只有第四条「证据优于声明」能落成检查项，前三条只是价值观。

具体到 TDD，那句流传最广的「测试之前写的代码会被删掉」有前提。`skills/test-driven-development/SKILL.md:18-22` 把新功能、修 bug、重构、改行为列成 Always；`:24-27` 紧接着列三类例外：一次性原型、生成的代码、配置文件，处理办法是「问你的搭档」。`:37-43` 才是删掉重来，还补了四条——不留作参考、不在写测试时拿它改、不看它、删就是删。

准确的说法是：**Always 那四类里，先写的代码要删掉重来；原型、生成代码、配置文件先问。** 把前半句当全称，就会有人拿一次性探路脚本去撞铁律，然后觉得这套东西不可理喻。

## 跑一遍

数一下本机这份到底有几个技能：

```text
(Get-ChildItem "$root\skills" -Directory).Count
```

```text
14
```

```text
Get-ChildItem "$root\skills" -Directory | Select-Object -ExpandProperty Name
```

```text
brainstorming
dispatching-parallel-agents
executing-plans
finishing-a-development-branch
receiving-code-review
requesting-code-review
subagent-driven-development
systematic-debugging
test-driven-development
using-git-worktrees
using-superpowers
verification-before-completion
writing-plans
writing-skills
```

原文说它「专门有一个 `diagnosing-superpowers` 来排查会话跑歪」。这 14 个里没这个名字，`skills/` 下也搜不到 `diagnosing`。会话跑歪之后怎么兜，5.1.0 靠的是 `using-superpowers` 自己那张红旗表（`:78-95`，十二句自我说服，每句配一句 Reality，比如「这不就是个简单问题」对「问题也是任务，先查技能」）。

## 动手

先量你自己这台，别照抄我的数：

```text
$root = (Get-Content "$env:USERPROFILE\.claude\plugins\installed_plugins.json" -Raw |
  ConvertFrom-Json).plugins.'superpowers@claude-plugins-official'[0].installPath
Get-ChildItem "$root\hooks" -Filter "hooks*.json" | Select-String -Pattern "SessionStart"
```

读 `installPath` 而不是写死路径，因为插件升级后目录名跟着版本号变。

然后两件事：

1. 把找到的那条 hook 命令单独跑一遍，量输出长度。如果它占掉你上下文预算的可观一块，`compact` 那个 matcher 意味着每次压缩都要再付一次——这时候要判断的是「每个会话都付这笔值不值」，而不是「它写得好不好」。
2. 读一个你信得过的技能里的铁律段，找它自己的例外条款。找不到例外的，多半是把条件写成了保证，那种句子在真实工作里迟早撞上「这次为什么不行」。

## 检查一下

```quiz
{"id":"vc0-06-quiz","title":"注入的、触发的、不执行的","questions":[
 {"question":"会话一开始，using-superpowers 的正文是怎么进到上下文里的？","options":["模型读到它的 description 之后自己决定去读","宿主在会话开始时把 14 个技能全部加载进来","SessionStart 钩子把 SKILL.md 整段 cat 出来，作为 additionalContext 注入","你在第一条消息里手动 @ 了它"],"answer":2,"explain":"hooks/hooks.json:3-13 只挂了 SessionStart，matcher 是 startup|clear|compact；hooks/session-start:18 是 cat，:35 包进 EXTREMELY_IMPORTANT，:51 输出 additionalContext。本机实测这段 5632 个字符，另外 13 个技能正文不在里面。"},
 {"question":"那其余 13 个技能的正文，什么时候进上下文？","options":["模型看到常驻的 name 加 description 后调用 Skill 工具取正文；不调用就不进来","钩子每轮重新注入一次全部技能","安装时一次性写进系统提示，之后一直在","由 git 钩子在提交时注入"],"answer":0,"explain":"常驻的只有名字和一句描述，正文要 Skill（Gemini 是 activate_skill）才加载，using-superpowers/SKILL.md:30-36 分别写了各宿主用哪个工具。所以自动触发的上限是模型选择调用，不是宿主替它塞。"},
 {"question":"「测试之前写的代码会被删掉」这句话在 5.1.0 里的准确范围是：","options":["任何情况都删，包括一次性探路脚本","只有直接改生产代码那四类才删；原型、生成代码、配置文件要先问","只在 CI 里跑的时候删","这是 README 的宣传语，SKILL.md 里没有这句"],"answer":1,"explain":"test-driven-development/SKILL.md:18-22 把新功能、修 bug、重构、改行为列为 Always，:24-27 列了一次性原型、生成代码、配置文件三类例外并要求先问人，:37-43 才是删掉重来那四条补充。"}
]}
```

## 还差的

- 5,632 是这台机器 2026-11-19 的值，钩子输出随插件版本变。我这台没有 `~/.config/superpowers/skills`；那个遗留目录存在时，`hooks/session-start:12-15` 会再叠一段警告，长度就不是这个数了。
- 触发成功率没测。我只证明了入口技能由钩子注入、其余 13 个不在注入里；没有拿一批任务统计「该调 Skill 时调了没有」——那要固定标记加样本。
- 没在真实交付上跑完整链路，所以「这套流程减少多少返工」我给不出数字。文里那个 CSV 导出是编出来的小场景，不是谁的真实项目。
- 其余宿主（Codex CLI、Codex App、Factory Droid、Gemini CLI、OpenCode、Cursor、GitHub Copilot CLI）只按 `README.md:35-152` 的安装说明核对过，本机只装了 Claude Code 这一份，别的没实装。
- 我读的是插件缓存里的 git checkout（detached HEAD，`f2cbfbe`），不是上游仓库的最新 main。5.1.0 之后出没出新版本、新版本又删改了什么，我不知道。
