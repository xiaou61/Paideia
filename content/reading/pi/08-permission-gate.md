---
title: 08 · 三十行的 permission gate
category: pi
order: 8
minutes: 18
level: intermediate
status: published
---

> **版本**：pi `v1.5.0`（`f9a2636`，2026-11-09 核对）。路径与行号以该 tag 为准。

## 现在的坑

README 第 90 行写得很清楚：

> Pi does not include a built-in permission system for restricting filesystem, process, network, or credential access. By default, it runs with the permissions of the user and process that launched it.

第一次看到会不舒服：一个会跑 `bash` 的 agent，居然没有权限系统？两种反应都不对——**以为这是漏洞**（它只是把边界交给了操作系统），或者**以为装个扩展就等于沙箱**（差得远）。

这一讲做两件事：写一个能用的 gate，然后说清它**不**能解决什么。

## 一页地图

pi 把"安全"拆成三层，每层的责任方不同：

| 层 | 谁负责 | pi 给什么 |
| --- | --- | --- |
| 策略 | 扩展 | `tool_call` 钩子：能看参数、能拦、能问人 |
| 边界 | 容器 / 虚拟机 | 没有内置；官方给了三种容器化方案 |
| 审计 | 会话记录 | 事件流 + 会话文件，事后可查 |

**扩展只能做第一层。** 它和 pi 同权限运行（README 的警告原文是"load extensions only from sources you trust"），所以一个恶意扩展能直接绕过你的 gate。这一点必须先说清，否则后面都是错觉。

## 最小可跑：30 行 gate

pi 仓库里已经有一个官方示例，`packages/coding-agent/examples/extensions/permission-gate.ts`，核心就这些：

```ts
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent"

export default function (pi: ExtensionAPI) {
  const dangerous = [/\brm\s+(-rf?|--recursive)/i, /\bsudo\b/i, /\b(chmod|chown)\b.*777/i]

  pi.on("tool_call", async (event, ctx) => {
    if (event.toolName !== "bash") return undefined

    const command = event.input.command as string
    if (!dangerous.some((p) => p.test(command))) return undefined

    if (!ctx.hasUI) {
      // 非交互模式（print / json / rpc）没有对话框，默认拒绝
      return { block: true, reason: "Dangerous command blocked (no UI for confirmation)" }
    }

    const choice = await ctx.ui.select(`⚠️ Dangerous command:\n\n  ${command}\n\nAllow?`, ["Yes", "No"])
    if (choice !== "Yes") return { block: true, reason: "Blocked by user" }
  })
}
```

四件事值得逐条看：

1. **`event.input` 是原始参数。** 不是字符串拼接后的命令，是模型给 `bash` 工具的那个对象，所以你能精确判断 `command` 字段。
2. **`return undefined` 表示放行。** 只有返回 `{ block: true, reason }` 才拦；pi 会把 `reason` 作为错误结果回给模型（第 05 讲的 immediate 分支）。
3. **`ctx.hasUI` 是模式开关。** 交互式 TUI 里为真，`print` / `json` 模式为假。没有 UI 时**必须 fail-closed**——这是这类 gate 最容易写错的地方：`await ctx.ui.select(...)` 在无 UI 时不会弹窗，会直接返回 undefined，如果你只判断 `choice !== "Yes"`，结果是"静默拒绝"，行为对但原因错；显式判 `hasUI` 才能给出可读的原因。
4. **拦截不等于终止。** 默认拦一条，模型会看到错误、可能换个命令再来；加 `terminate: true` 才是"这一批到此为止"。

装法就是第 01 讲那套：放到 `~/.pi/agent/extensions/` 或 `pi -e ./permission-gate.ts`。

## 想让策略更狠的三个方向

- **白名单而不是黑名单。** 上面的正则只挡了三种写法，`sh -c "rm -rf /"`、`find . -delete`、`> /dev/sda` 都绕得过去。更实用的做法是反过来：只允许 `git`、`npm`、`ls` 这类，其余一律拦。
- **按路径拦。** 文件类工具的参数里有路径，可以拒绝写入 `.env`、`~/.ssh`、`node_modules`。官方示例里 `protected-paths.ts` 就是这个思路。
- **整体替换内置工具。** 扩展可以用同名 `registerTool` 覆盖 `bash`，在 `execute()` 里做完整审计再决定要不要真的执行（示例 `tool-override.ts`）。这比"拦截"更彻底，但也更容易把 pi 弄坏。

## 和真实现的差异

- **gate 挡不住"已经发生的写"。** 它只拦工具调用，不拦 pi 进程本身的文件读写（会话文件、缓存、日志都照写）。
- **扩展与宿主同权限。** 所以"装一个第三方 gate 扩展"本身就是一个信任决策——README 明确要求只从可信来源加载。
- **非交互模式下没有"问一下"这回事。** RPC 模式有 UI 协议（能弹确认），`print` / `json` 没有。写 gate 时要按模式分支，而不是假设总有人能回答。
- **真正的隔离在容器里。** 官方给了三种：Gondolin 扩展（把内置工具和 `!` 命令路由进本地 Linux 微虚拟机）、纯 Docker、OpenShell 策略沙箱（`packages/coding-agent/docs/containerization.md`）。gate 是"防手滑"，容器是"防穿透"，别互相替代。
- **没有"策略文件"这种内置概念。** pi 不读 `permissions.json`，一切策略都是代码。好处是灵活，坏处是团队协作时策略散落在扩展里。

## 小结

30 行 gate 的骨架是：**只对高危工具、判参数、有 UI 就问、没 UI 就拒、拦了要说明原因**。它解决"手滑"，不解决"越权"——后者要靠容器。

如果重来一次，我会先写一个"只允许白名单命令"的版本，再故意用 `sh -c` 绕它，看看自己的策略有多脆——比读安全文档更有体感。

下一讲换个话题：为什么终端里的界面能做到不闪。pi 的 TUI 用了一个很老但很有效的办法——差分渲染。
