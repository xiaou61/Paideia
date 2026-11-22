---
artifact: test-report
work_id: WORK-023
work: 逐行执行与可视化调试
status: passed
evidence: required
workflow: full
created: 2026-10-19
updated: 2026-10-19
verified_commit: 1b7c663
accepted_by: xiaou61
accepted_at: 2026-10-29 13:18:54 +0800
---

# 逐行执行与可视化调试验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 后端 | 本机 JDK 25（隔离库）+ 线上 codenest-online（生产库 V21，Docker 29.1.3） |
| 沙箱 | **线上真实容器**（`python:3.12-alpine`，无网络、只读根、非 root、256m、0.5 CPU） |
| 前端 | 学习者端从当前工作区重打并部署 |
| 本机限制 | **本机没有 Docker**，所以录制只能在线上验——那也正是它唯一能被验到位的地方 |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 能录且行号与真实执行一致 | 线上 `POST /api/v1/sandbox/trace`（`total=0; for i in range(3): total+=i; print(total)`） | 人工 | passed | **9 步**，行号序列 `1,2,3,2,3,2,3,2,4`，与手工执行一致 |
| AC-002 变量真实 | 同上 | 人工 | passed | 逐步 `total: 0→0→0→1→1→3→3`、`i: 0→0→1→1→2→2`，最终 `total=3`；程序输出 `3\n` |
| AC-003 步数上限触顶保留已录部分 | 线上（`for i in range(10000): x=i`） | 人工 | passed | **steps=300**，`reason=step_limit`，返回了已录的 300 步而不是报错 |
| AC-004 异常能定位到行 | 线上（`a=1; b=a/0`） | 人工 | passed | 3 步，最后一步 `event=exception`、`line=2`、`__error__=ZeroDivisionError` |
| AC-005 语法错误可读 | 线上（`def f(:`） | 人工 | passed | 1 步、`reason=error`，不是 500 |
| AC-006 播放器步进/播放/跳首尾/键盘 | `npx vitest run src/markdown/renderers/trace-player.test.tsx` | 0 | passed | **6 条**：步数显示、变量随步变、变化标记、回到首步无标记、←→ 键盘、空状态 |
| AC-007 变量变化标记不靠颜色单一表达 | 同上 | 0 | passed | 标记文案是「↑ 变了」，且同时出现在 `trace-changed-<name>` 上 |
| AC-008 空状态不渲染播放控件 | 同上 | 0 | passed | `steps: []` 时只有 `trace-empty`，没有 `trace-player` |
| AC-009 触顶说明"只录到这里" | 同上 | 0 | passed | `reason=step_limit` 时文案含「只录到这里」 |
| AC-010 Java 明确拒绝而不是静默失败 | 线上（`language=java`） | 人工 | passed | **HTTP 400** +「暂时只支持 Python 的逐步录制」，前端据此退回一次性运行 |
| AC-011 与 `/run` 共用配额与审计 | 代码核对 + 线上 `usedToday` 递增 | 人工 | passed | 端点走 `quota.requireQuota` / `quota.record` / `RunSlot`，与 `/run` 同一条路径 |
| AC-012 录制不参与通过判定 | 代码核对 | 人工 | passed | `/trace` 不返回任何"对/错"，也不写 `learning_progress` |
| AC-013 既有功能不回归 | 线上学习者端 + 管理端 e2e | 0 | passed | 学习者端 **61 passed / 21 skipped / 0 failed**；管理端 **22 passed** |
| AC-014 全量后端不回归 | `scripts/test-db.sh mvn -B test` | 0 | passed | **500 执行 / 0 失败 / 5 跳过** |
| AC-015 前端单测与类型检查 | `pnpm -r test` / `pnpm -r typecheck` | 0 | passed | **256 条**全绿（ui 154 含新增 6 条）；typecheck 0 error |

## 安全边界（本项最需要说清的一条）

设计里的安全约束**都在代码里落实了**，不是只写在文档里：

- 用户代码在**独立命名空间**里 `exec`（`user_globals` 里只有 `__name__`/`__file__`/`__builtins__`），
  拿不到录制器的 `steps`/`_emit`/文件句柄；
- 录制器脚本是**类路径常量**（`sandbox/trace.py`），不从请求或磁盘读；
- 容器参数与 `/run` **完全相同**：`--network=none`、`--read-only`、非 root、`--cap-drop=ALL`、
  256m、0.5 CPU、pids-limit；
- 四道硬上限（300 步 / 20 变量 / 60 字符 / 256KB）都在录制器内部执行，触顶即停。

**但必须说明**：`sys.settrace` 的 frame 对象**没有做逃逸面专门验证**（比如用户代码能否通过
`sys._getframe` 摸到调用栈）。当前判断是容器本身就是边界、录制器不是边界；
这一条是设计论断，**没有对抗性测试**。

## 实施中修掉的真实缺陷

**settrace 回调签名错误**：`sys.settrace(fn)` 的回调签名是 `(frame, event, arg)`，
第一版只写了一个参数，第一次跑就 `TypeError: _local_tracer() takes 1 positional argument
but 3 were given`。本机直接跑 `trace.py` 抓到的——如果只靠线上部署才发现，
会浪费一整轮发版。

## 未验证与已知缺口

1. **不支持 stdin**：录制器没有接用户的 stdin（`/run` 支持）。带输入的程序录制时
   会拿不到输入而报 EOF。
2. **变量只显示浅层 repr**：容器只报类型与长度（`list(len=3)`），看不到元素内容。
   这是设计取舍（递归会炸），但"看一个列表里是什么"是常见需求。
3. **没有结构化快照动画**（REQ-010）：播放器只显示变量与输出，
   栈/队列/链表的补间动画一期没做。
4. **没有「这一步为什么这样」的 AI 联动**（REQ-011）：那是可选增量，本期未接。
5. **前端 e2e 未覆盖录制链路**：线上 e2e 不跑逐步执行（token 成本 + 沙箱耗时），
   播放器只由单测覆盖。
