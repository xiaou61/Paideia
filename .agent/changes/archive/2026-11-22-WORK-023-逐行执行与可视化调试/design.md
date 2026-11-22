---
artifact: design
work_id: WORK-023
work: 逐行执行与可视化调试
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-014, WORK-009, WORK-021]
created: 2026-10-19
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-19 17:55:08 +0800
approver_role: CTO
---

# 设计 · 逐行执行与可视化调试

## 系统上下文

```text
学习者 ← 时间轴播放器 ← trace 事件数组 ← POST /api/v1/sandbox/trace
                                            │
                                    SandboxController（配额 / 并发槽 / 审计）
                                            │
                                     DockerCodeRunner（同一批容器参数）
                                            │
                          python:3.12-alpine 里的 trace 入口脚本
                                └─ sys.settrace → 事件 JSON（stdout 末尾）
```

## 端点

`POST /api/v1/sandbox/trace`

```json
{ "language": "python", "code": "...", "stdin": "" }
```

响应：

```json
{
  "language": "python",
  "supported": true,
  "steps": [
    { "index": 0, "kind": "line", "line": 2,
      "locals": { "total": "0" }, "output": "", "frame": "<module>" },
    { "index": 1, "kind": "line", "line": 3,
      "locals": { "total": "0", "i": "0" }, "output": "", "frame": "<module>" }
  ],
  "stdout": "3\n",
  "truncatedReason": null,
  "durationMs": 812,
  "timedOut": false
}
```

**为什么不分页、不做流式**：一次录制的体量上限就是 256KB，播放是纯本地状态。流式会把"回退"变成一件难事（要缓存全部事件）。

## 录制器

放在 `paideia-sandbox` 的资源里，作为**容器内运行的常量脚本** `trace_python.py`，与 `DockerCommand` 的 `PYTHON_SCRIPT` 并列。执行方式：容器内 `python /trace.py`，用户代码从 stdin 读入（沿用现有的"先读长度再读源码"分帧协议）。

### 事件采集

```python
def tracer(frame, event, arg):
    if len(steps) >= MAX_STEPS: 停录
    if event == "line" and 是用户代码(frame):
        steps.append({...locals 浅层 repr..., output: 自上次以来的增量})
    return tracer
```

三条过滤：

1. **只录用户文件**（`frame.f_code.co_filename == "<user>"`）：录制器自身与标准库的帧必须跳过，否则一次 `range(3)` 就录出几十步。
2. **跳过 dunder**：`__builtins__` 有 157 个键，与教学无关，直接不展示。
3. **只记 line / return / exception**，不记 call（call 与 line 大量重复）。

### 值怎么序列化

```python
def short(value):
    if 标量: repr 截断到 60 字符
    if list/tuple/set/dict: f"{类型}(len={n})"
    else: f"<{类型}>"
```

**为什么不递归**：`a = []; a.append(a)` 会无限递归；`range(10**9)` 会把内存吃光。浅层 repr 回答的是"这里是什么、大概多大"，足够教学。

### 输出分片

把 `sys.stdout` 换成一个收集器；每步记录"自上次记录以来新增的文本"。前端按顺序拼起来就等于完整 stdout。

### 上限与截断

| 项 | 上限 | 触顶时 |
| --- | --- | --- |
| 步数 | 300 | 停录，`truncatedReason = "steps"` |
| 每步局部变量 | 20 | 取前 20 个，加 `"…"` 标记 |
| 单值长度 | 60 字符 | 截断加 `…` |
| 总序列化字节 | 256KB | 停录，`truncatedReason = "bytes"` |

**超时不清空**：容器超时被杀之前，录制器每步都把快照追加到一个临时文件；超时时 runner 尽力读回已写入的部分。这是"超时也给你看前半段"的实现方式。

### 安全

- 用户代码在 `exec(compile(src, "<user>", "exec"), {"__name__": "__main__"})` 里跑；
- 录制器自己的变量名不带 `__`，且不放进用户命名空间；
- **明确：这不是安全边界**。真正的边界是容器（无网络、只读根、非 root、cap 全丢、256m、0.5 CPU、10s）。录制器只保证"不主动把内部状态暴露给用户代码"，不承诺抵御恶意逃逸。

## 前端

### 事件契约

放 `@paideia/ui` 的 `renderers/schema.ts` 旁边（`trace-schema.ts`），纯解析函数与现有 `parseCodeTour` 同构：非法输入降级成"播放器不可用 + 原因"，不抛。

### 播放器

`TracePlayer` 组件，复用现有的 `BlockFrame` / `Pill` / 变量面板样式：

| 区域 | 内容 |
| --- | --- |
| 时间轴 | `第 N / M 步`、进度条（可拖）、首/末按钮 |
| 代码区 | 当前行高亮，行号与真实源文件一致（录制器给的行号直接是用户文件行号） |
| 变量区 | 名字 → 值；**变化标记**：与上一步比较，变化的项加「变」徽标 + 数值加粗 |
| 输出区 | 截至当前步的 stdout |
| 栈区 | 多帧时显示；单帧不显示 |

**播放是纯本地**：全部步骤一次性拿到，前进/后退只切 `index`。播放用 `setInterval`，速度 0.5×/1×/2×；`prefers-reduced-motion` 下不自动播放，只保留手动步进。

### 入口

只在 `code-challenge` 的 `run` 题型里出现（与现有「运行」并排）。点击前的流程与 `run` 完全一样：先探 `/sandbox/status`，不 ready 就不出按钮。

## 与既有能力的关系

| 既有 | 本项 |
| --- | --- |
| `run` 题型（结果判定） | 保留不动；录制是并列的第二个按钮 |
| `code-tour`（作者手写走读） | 保留；播放器复用它的 `state` 渲染形状，但**不替换**它 |
| 学习记录 | 录制不产生得分，不改 `learning_progress` |
| 助手预填 | 播放器加「这一步为什么这样」，预填当前步的变量与行号 |

## 关键取舍

| 取舍 | 理由 |
| --- | --- |
| 录制回放，不做真单步 | 真单步要可暂停执行 + 跨进程状态，成本高一个数量级 |
| Python 用 settrace 而不是 monitoring | monitoring 的 callback 只给 `(code, line)`，拿不到 frame 与 locals（本轮实测） |
| 值只存浅层 repr | 真对象无法跨进程序列化；递归会炸 |
| Java 不做行级 | JDI 要双 JVM + 调试端口；一期价值低于成本 |
| 不分页、不流式 | 体量有上限；回退需要全量事件在手 |
| 结构化动画靠快照而不是推导 | 从 repr 推不出链表/树形状 |

## 已知缺口

1. Java 没有行级追踪，只有输出分片。
2. 变量只显示浅层 repr：展开一个 dict 的内部需要下一步能力（或点击时再问一次服务器，本项不做）。
3. 录制超时后只能看到被杀之前的步；最后一步可能不完整。
4. settrace 会让执行明显变慢，长循环必然触顶（这是设计，不是缺陷）。
5. 若将来要"改一个变量再继续跑"，那是不同的功能（可暂停执行 + 状态注入），本项明确不做。
