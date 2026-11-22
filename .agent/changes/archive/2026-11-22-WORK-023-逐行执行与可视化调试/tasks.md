---
artifact: tasks
work_id: WORK-023
work: 逐行执行与可视化调试
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-014, WORK-009, WORK-021]
created: 2026-10-19
updated: 2026-10-19
approved_by: xiaou61
approved_at: 2026-10-19 17:55:08 +0800
approver_role: CTO
---

# 任务 · 逐行执行与可视化调试

**排序原则**：先把录制器做成**能独立跑起来、能在本机复现**的东西（不经 HTTP、不经前端），再接端点，再写播放器。录制器是整条链唯一有真实风险的部分，它不成立后面全白做。

**全局约束**：不新增外部服务；复用现有配额与审计；不引入 AGPL 代码；Playwright/单测只对改动那一端跑。

## 任务

### TASK-001 | done | 容器内录制器（Python）

新增资源文件 `paideia-sandbox/.../run/trace_python.py`：读 stdin 的分帧协议、`exec` 用户代码、`sys.settrace` 收集 line/return/exception、浅层 repr、输出分片、四种上限。

**验收**：`python trace_python.py < payload` 对一个固定样例输出合法 JSON；步数、变量值、输出三项与手工执行一致（单测直接调这个脚本）。

### TASK-002 | done | 录制器的边界与恶意输入

单测覆盖：`while True: pass`（超时前必须自己截断）、10 万次循环（步数触顶且带原因）、自引用列表（不递归、不崩）、超长字符串（截断）、`__builtins__` 不进 locals。

**验收**：六条各有一断言；全部在 3 秒内返回。

### TASK-003 | done | `/sandbox/trace` 端点

`SandboxController` 加端点；复用配额、并发槽、审计（审计行加 `kind=trace`）；沙箱不可用返回 503 并说明；Java 请求返回 `supported=false` 而不是报错。

**验收**：集成测试——正常录制、沙箱关闭时 503、Java 返回 supported=false、超配额 429、超步数带 truncatedReason。

### TASK-004 | done | 去抖与体积闸

响应序列化后超过 256KB 时截断事件数组并设 `truncatedReason="bytes"`；空代码、非字符串 code 返回 400。

**验收**：单测造一个超大事件数组，确认响应小于上限且原因正确。

### TASK-005 | done | 事件契约与解析（前端）

`@paideia/ui` 新增 `trace-schema.ts`：`parseTrace()` 校验并归一化；非法输入返回 `{ok:false, reason}`。

**验收**：纯函数单测——合法样例、缺字段、steps 为空、line 为负、locals 非对象。

### TASK-006 | done | `TracePlayer` 播放器

时间轴 + 当前行高亮 + 变量面板（含「变」标记）+ 输出面板 + 栈面板；键盘 ←/→；播放/暂停/速度；`prefers-reduced-motion` 下不自动播。

**验收**：组件测试——点下一步切换数据且不发请求、变化标记正确、首末按钮边界、reduced-motion 下不自动播放。

### TASK-007 | done | 接进 `run` 题型

`code-challenge` 的 `run` 分支加「逐步执行」按钮：能力探测同 `run`；录制失败时给出原因并保留一次性运行。

**验收**：组件测试——沙箱不可用时两个按钮都不出；录制返回 supported=false 时只有「运行」。

### TASK-008 | done | 与助手联动

播放器加「这一步为什么这样」：预填含课号、行号、当前变量；**不发请求**。

**验收**：单测断言预填文本含行号与变量名；无网络请求。

### TASK-009 | done | 结构化快照与补间（可选切片）

事件可带 `structure`；前端按相邻快照差异做形态插值（节点位置/连线）。缺省时不占位。

**验收**：一条链表快照序列能播放，动画在 reduced-motion 下直接切帧。

### TASK-010 | done | 文档与已知缺口

`dev-workflow.md` 增「逐步执行录制」一节：录制器在哪个文件、四个上限、为什么用 settrace、Java 不做的理由、五条缺口。

**验收**：照文档跑一遍命令，结果一致。

## 评审焦点

| 失败模式 | 归属 | 测试名 |
| --- | --- | --- |
| 录制器自己也被录进去（步数暴涨） | TASK-001 | `onlyUserFrameIsRecorded` |
| 自引用结构让录制器递归爆栈 | TASK-002 | `selfReferentialListIsShallow` |
| 超时后什么都没返回 | TASK-002/003 | `timeoutStillReturnsPartialSteps` |
| 响应体积失控 | TASK-004 | `responseIsTruncatedUnderLimit` |
| 播放器每步都发请求 | TASK-006 | `steppingMakesNoRequest` |
| 变量变化只靠颜色表达 | TASK-006 | `changeHasTextMarker` |
| 录制偷偷改了学习记录 | TASK-007 | `traceDoesNotTouchProgress` |
| Java 被误当成支持行级 | TASK-003 | `javaReportsUnsupported` |

## 未包含

真单步（可暂停执行）、Java JDI、浏览器内解释器、时间旅行修改变量、录制分享链接、自动从 repr 推导内存图。
