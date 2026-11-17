---
title: 拆解 Superpowers（上）：把工程纪律写成技能
category: vibe-coding-from-zero
order: 6
chapter: Skills 与工程纪律
minutes: 28
level: intermediate
status: published
---

## 这一关在解决什么

superpowers 不是一个工具，也不是一个更强的模型。它是**把"资深工程师的好习惯"拆成一组能自动触发的 skill 的方法论**——目的不是让 AI 更聪明，是让它在关键节点**慢下来、按步骤、留下证据**。

> 版本：superpowers **v7.0.2**，commit `22f0b463`，核对于 2026-11-03。作者 Jesse Vincent 与 Prime Radiant，MIT 许可。仓库：[github.com/obra/superpowers](https://github.com/obra/superpowers)。下文技能名与流程均以该版本 README 为准，后续版本可能调整。

## 讲解

### 它对抗的到底是什么

大多数 AI 编程工具的默认行为是：**一上来就写代码，写完就说"完成了"**。快，但坑在后面——需求没对齐、没测试、改不动、也没人说得清它到底动了什么。

superpowers 的思路是相反的：在写代码之前先拦住你，问清楚要什么；写的时候强制先写测试；做完之前强制验证。**它约束的是流程，不是模型能力。**

### 它把哪些纪律写成了技能

按 README 的分类，这些技能大致是这些（名字保留原文，方便你去仓库对照）：

| 阶段 | 技能 | 它把什么纪律固化下来 |
| --- | --- | --- |
| 想清楚 | `brainstorming` | 动手前先问清意图、为什么，把理解复述给你纠错 |
| 隔离环境 | `using-git-worktrees` | 新分支 / 独立工作区，先确认测试基线是绿的 |
| 做计划 | `writing-plans` | 把活拆成 2–5 分钟的小任务，每个都写清文件路径、完整代码、怎么验 |
| 执行 | `subagent-driven-development` / `executing-plans` | 一个任务一个子智能体逐个做并复查，或在当前会话里逐个做 |
| 测试先行 | `test-driven-development` | 强制 RED→GREEN→REFACTOR；先写失败测试，在测试之前写的代码会被删掉 |
| 调试 | `systematic-debugging` | 四阶段找根因，不靠猜 |
| 复查 | `requesting-code-review` / `receiving-code-review` | 对照计划审、按严重度报；怎么回应反馈 |
| 收尾 | `finishing-a-development-branch` | 验证测试、给出合并/PR/保留/丢弃选项、清理 |
| 元技能 | `writing-skills` / `using-superpowers` | 怎么写新 skill、怎么让整套系统在会话开始时接管 |

一眼能看出：**这不是"功能清单"，是"工程流程清单"。** 每一格都是某个资深工程师本来就会做、但 AI 默认不做的事。

### 四条哲学

superpowers 的 README 把它的价值观压成四句，值得抄下来：

1. **测试驱动（TDD）**：测试先行，永远。
2. **系统化优于即兴**：靠流程，不靠猜。
3. **复杂度最小**：简单是首要目标（YAGNI）。
4. **证据优于声明**：说"做完了"之前先验证。

这四条，其实就是后面第 11 关"质量与验证"的骨架。

### 关键设计：靠"描述"自动触发

superpowers 的 skill 不是让你手动一个个敲命令，而是靠每个 skill 的 `description` 在合适时机**自动被读**。它还有一个 `using-superpowers` 的引导技能，在会话一开始就被注入，提醒 AI "先看看有没有相关技能"。

它的 README 原话大意是：技能会**自动触发**，你不用做特别的事。这既是它好用的地方，也是它容易"误触发"的地方（所以你也会看到它专门有一个 `diagnosing-superpowers` 来排查会话跑歪）。

## 常见误区

### 误区：装了 superpowers，AI 就变强了

**你以为**：装个插件，模型能力就上去了。
**其实**：它不改变模型能力，只改变流程。模型该犯的错还会犯，只是被"先测试、后复查、完成前验证"这些环节挡掉一部分。
**怎么验证**：拿一个模型本身不会做的复杂算法题给它，看装了之后会不会做——不会，说明它约束的是流程不是智力。

### 误区：这是 Claude Code 专属

**你以为**：只有某一个工具能用。
**其实**：按 README，它适配了很多 harness（Claude Code、Codex、Cursor、Gemini CLI、OpenCode 等一长串）。理念是通用的：skill 是文件，谁支持读文件谁就能用。
**怎么验证**：看 README 的安装章节，数一下它列了多少个客户端——很多。

### 误区：技能越多越全能

**你以为**：整套装齐就最强。
**其实**：技能之间会互相触发、互相打架。先用你真正需要的几个（比如 TDD、系统化调试、写计划），其余按需开。
**怎么验证**：只留 TDD 一个技能跑一周，再全开跑一周，比较哪次更顺手。

## 带走一句

**superpowers 的真正卖点是"把工程流程拆成能自动触发的说明书"**——下一关我们把这套流程从头到尾跑一遍，看它怎么从"我想做个东西"一路走到"合并分支"。
