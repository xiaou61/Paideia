---
title: SDD 四步：spec → plan → tasks → implement
category: vibe-coding-from-zero
order: 3
chapter: SDD 规格驱动
minutes: 18
level: beginner
status: published
---

## 60 秒内最多 1 次，跑出间隔 1 秒的两次

上一课把验收标准写成「60 秒内最多发送 1 次」。我按最省事的读法实现：拿当前时间的整分钟数当计数器键。

```python
BASE = datetime(2026, 11, 19, 0, 0, 0, tzinfo=timezone.utc)   # 时钟钉死，脚本可重放

def fixed_bucket(state, now):
    bucket = int(now.timestamp() // 60)   # 第几个整分钟
    if state.get(bucket):
        return False, None
    state[bucket] = 1
    return True, 0
```

喂两个时刻进去，真实输出（Python 3.11.5，完整脚本在 `## 跑一遍`）：

```text
固定分钟桶
  连发两次: t=0 放行 | t=1 拒绝 retry_after=None
  跨分钟边界: t=59 放行 | t=60 放行
```

`t=59` 放行，`t=60` 又放行——两次发送只隔 1 秒。

## 两种读法都满足这句话

「60 秒内最多 1 次」字面不俗，至少有两种读法都说得通：按整分钟切桶（每个自然分钟内 1 次），或者距上次发送满 60 秒才放行（滑动窗口）。第二种只多几行：

```python
def sliding_window(state, now):
    last = state.get("last")
    if last is not None:
        remain = 60 - (now - last).total_seconds()
        if remain > 0:
            return False, math.ceil(remain)   # 顺带告诉调用方还要等几秒
    state["last"] = now
    return True, 0
```

同一个探针，两种实现的差别只在边界上：

```text
滑动窗口
  连发两次: t=0 放行 | t=1 拒绝 retry_after=59
  跨分钟边界: t=59 放行 | t=60 拒绝 retry_after=59
```

| 探针 | 固定分钟桶 | 滑动窗口 |
| --- | --- | --- |
| `t=0` 发、`t=1` 再发 | 拒绝，但拿不到还要等多久 | 拒绝，`retry_after=59` |
| `t=59` 发、`t=60` 再发 | 两次都放行（间隔 1 秒） | 拒绝，`retry_after=59` |

我当时写的测试只有第一行那个场景：`t=0` 成功、`t=1` 拒绝。这条断言在两种实现上都是绿的。它能通过，说明不了验收标准被满足——它恰好绕开了唯一有分歧的地方。

## 缺的那句属于 spec，不属于 plan

把读法定成「滑动窗口」，看着像实现选择：放 plan 里挑个算法而已。但用户能感知的不是算法，是隔 1 秒能不能收到第二条短信。这是行为，属于 spec。验收标准补成这样，边界才被钉住：

```text
AC-1  同一手机号，t=0 发送成功后，t=1 再请求必须拒绝
AC-2  t=59 发送成功后，t=60 再请求必须拒绝
AC-3  被拒绝时返回 retry_after=59，不是空值
AC-4  t=59 发送成功后，t=119 必须放行
```

拿这四条去判两种实现：

```text
固定分钟桶：AC-1=过  AC-2=不过  AC-3=不过  AC-4=不过
滑动窗口：AC-1=过  AC-2=过  AC-3=过  AC-4=过
```

只剩一种实现能全过。验收标准该长的样子就是这样：不描述顺利路径，而是把两种都说得通的行为分开，只留你真正想要的那一种。`AC-1` 那条是原句，两种都能过；真正干活的 `AC-2` 到 `AC-4`，都是补出来的。

同一个毛病在仓库里有更贵的版本。`.agent/changes/WORK-041-GitHub日榜周榜准确性/requirements.md:29` 记着一次生产漂移：trending 页面不可达，整组走 Search 兜底，结果与官方日榜 0/11、周榜 0/14 重叠，30 条里 10 条 `d1<20`、8 条 `d1=0`。规格里写的是「取 GitHub 热榜」，没写「拿不到热榜时该给什么」——兜底就沿用了「热榜」这个名字，名字对了，内容全错。补的验收标准 `:131` 是：「页面不可达时，`sourceName` 为「GitHub 新项目」，且不出现 `d1=0` 或 `age<7 且 forks<100` 的条目。」

`:132` 的 AC-003 更值得抄：它给完用例（`week=2026-10-18`、`days=[0,0,0,0,0,15,267]` 时应得 `2026-10-24=15`、`2026-10-25=267`），还要求「改成周日开头必须红」。把反向验证写进验收标准，等于连「这条标准真的在拦人」也变成可检查的东西。`tasks.md:67` 把同一条纪律落到任务上：「把 `plusDays(i + 1)` 改成 `plusDays(i)`，`weekLabelIsSundayButDaysStartMonday` 必须红。」

## 四步落成四份文件

四步有个流行的解释：「错了只退回上一步」。按 Spec Kit 现行文档，这句只在某一类错上成立：缺口出在实现，就追加任务回去补；出在 spec，就得回源头改。落点不一样，所以先看现象，再决定翻哪一份。

| 你看到的现象 | 先翻哪一份 | 为什么是它 |
| --- | --- | --- |
| 验收标准逐条都绿，用户还是踩到 | spec | 边界没写进验收标准，改代码只是碰巧对 |
| spec 定了间隔 60 秒，实现按整分钟切桶 | plan | 行为已经由 spec 定死，plan 只能在它之内挑实现 |
| plan 写的是滑动窗口，代码是另一种 | tasks | 任务里没要求「改回错的必须红」，就没人真去验 |
| 本地跑对，线上行为不对 | 配置与部署 | 同一个行为在部署侧可能另有一份默认值 |

关于 Spec Kit 本身，有几处我核对过、也纠正了自己先前的理解：

- **它是五步，不是四步。** 现行流程是 Specify → Plan → Tasks → Implement → **Converge**。`/speckit-converge` 比对实现与 spec 的差距，发现缺口会**往 `tasks.md` 追加新任务**，然后要求再 implement，直到报告 Converged。退回的落点是 tasks，不是 spec。
- **`/speckit-analyze` 只读。** 全量路径里它报告 `spec.md`、`plan.md`、`tasks.md` 之间的冲突与缺口，不改任何文件；要你自己回源头改，改完重跑。
- **命令拼法取决于集成方式。** `specify init my-project --integration copilot` 走 Copilot 默认的 skills 模式，命令是连字符式 `/speckit-*`；文档首页给的点号式 `/speckit.specify` 属于另一种集成。初始化可选 43 种（41 个 coding agent 加 generic 与 none）。拼法跟集成绑定，抄另一种的写法在你这套环境里对不上号。
- **`.specify/` 不是产物的家。** 当前在做哪个 feature 记在 `.specify/feature.json`，可用 `SPECIFY_FEATURE_DIRECTORY` 覆盖，文档写明**与 git 分支无关**；四份产物本身是 `spec.md`、`plan.md`、`tasks.md` 加实现代码。

Kiro 是同一思路换文件：`requirements.md`、`design.md`、`tasks.md`，验收标准用 EARS 句式 `WHEN [条件] THE SYSTEM SHALL [行为]`，另有一道 Analyze Requirements 专门查逻辑不一致、歧义与冲突约束。

出处：Spec Kit 文档 https://github.github.io/spec-kit/ 与 https://github.github.io/spec-kit/quickstart.html（页脚 Last updated: November 4, 2026），Kiro 文档 https://kiro.dev/docs/specs/feature-specs/，核对日期 2026-11-19。我没在本机装 Spec Kit，上面这些是按文档抄的，没跑过。

回到限流这件事，三处参数各管一段，换一个值用户看到的行为就换一种：

| 参数 | 换成另一个值，用户看到什么 |
| --- | --- |
| 起算点：整分钟桶 / 上次发送时刻 | 桶：边界上允许间隔 1 秒的两次；上次发送：任意两次间隔都不小于 60 秒 |
| 拒绝时的返回值：布尔 / 剩余秒数 | 布尔：前端只能自己猜倒计时；剩余秒数：能直接显示「还要等 59 秒」 |
| 计数放进程内存 / 放 Redis | 内存：多副本各记一份，实际上限是副本数乘 1 次；Redis：要用 `SET NX PX` 或 Lua 把判断与写合成一次原子操作，本文没做 |

## 跑一遍

存成 `limit.py`，`python limit.py`：

```python
import math
import sys
from datetime import datetime, timedelta, timezone

BASE = datetime(2026, 11, 19, 0, 0, 0, tzinfo=timezone.utc)


def at(sec):
    return BASE + timedelta(seconds=sec)


def fixed_bucket(state, now):
    bucket = int(now.timestamp() // 60)
    if state.get(bucket):
        return False, None
    state[bucket] = 1
    return True, 0


def sliding_window(state, now):
    last = state.get("last")
    if last is not None:
        remain = 60 - (now - last).total_seconds()
        if remain > 0:
            return False, math.ceil(remain)
    state["last"] = now
    return True, 0


def probe(impl, seconds):
    state, cells = {}, []
    for s in seconds:
        ok, retry = impl(state, at(s))
        cells.append(f"t={s} 放行" if ok else f"t={s} 拒绝 retry_after={retry}")
    return " | ".join(cells)


print("python", sys.version.split()[0])
for name, impl in [("固定分钟桶", fixed_bucket), ("滑动窗口", sliding_window)]:
    print(name)
    for case, secs in {"连发两次": [0, 1], "跨分钟边界": [59, 60]}.items():
        print(f"  {case}: {probe(impl, secs)}")


def check_ac(impl):
    """四条补过的验收标准，逐条判"""
    out = {}
    out["AC-1"] = "拒绝" in probe(impl, [0, 1])
    out["AC-2"] = "t=60 拒绝" in probe(impl, [59, 60])
    out["AC-3"] = "retry_after=59" in probe(impl, [59, 60])
    out["AC-4"] = "t=119 放行" in probe(impl, [59, 60, 119])
    return out


print()
for name, impl in [("固定分钟桶", fixed_bucket), ("滑动窗口", sliding_window)]:
    acs = check_ac(impl)
    print(name + "：" + "  ".join(f"{k}={'过' if v else '不过'}" for k, v in acs.items()))
```

真实输出：

```text
python 3.11.5
固定分钟桶
  连发两次: t=0 放行 | t=1 拒绝 retry_after=None
  跨分钟边界: t=59 放行 | t=60 放行
滑动窗口
  连发两次: t=0 放行 | t=1 拒绝 retry_after=59
  跨分钟边界: t=59 放行 | t=60 拒绝 retry_after=59

固定分钟桶：AC-1=过  AC-2=不过  AC-3=不过  AC-4=不过
滑动窗口：AC-1=过  AC-2=过  AC-3=过  AC-4=过
```

## 动手

把 `check_ac` 里的四条判定改成 `assert`，先跑固定桶：它会在 AC-2 停下。再把入参换成滑动窗口，四条一起过。然后补一条我的脚本没验的：被拒绝时不能产生新的发送记录——在 `probe` 里把最终的 `state` 打出来，确认被拒的那次没往里塞东西。

这一步做完你会拿到两样东西：一份能判死两种实现的验收标准，和一条证明它真在拦人的红灯记录。第二样得真跑一次才有，写不出来，也抄不过来。

## 检查一下

```quiz
{"id":"vibe-03-quiz","title":"边界写在哪儿","questions":[
 {"question":"「60 秒内最多发送 1 次」这句话，最容易被漏掉的是哪一部分？","options":["「60 秒」这个时长","「最多 1 次」这个次数","60 秒从哪个时刻开始算","「发送」这个动作本身"],"answer":2,"explain":"固定分钟桶与滑动窗口都满足这句话的字面，分歧只在起算点；起算点恰好落在用户能摸到的边界上。"},
 {"question":"测试里只写「t=0 成功、t=1 拒绝」，为什么不能证明验收标准被满足？","options":["因为它只覆盖顺利路径，两种读法都能过","因为缺少断言库","因为它没有连数据库","因为时间不能被伪造"],"answer":0,"explain":"这条断言在固定分钟桶与滑动窗口上同时为真，能通过它区分不了两种行为。"},
 {"question":"要让边界行为被真正拦住，哪种做法有效？","options":["在 plan 里写明用滑动窗口","implement 时让 AI 自己判断","多写几个单元测试","把边界写进验收标准，并要求改回错实现时测试必须红"],"answer":3,"explain":"验收标准是人和 AI 都能判的那一层；反向验证把「这条标准真的在拦人」也变成可检查的。"}
]}
```

## 还差的

- 多副本部署时每个进程各记一份 `last`，跨实例仍能 1 秒两次。本文一直是单进程模型，这条路没测。
- 时钟。我用固定 `datetime` 重放，真实链路里客户端、网关、服务器三个时间源不一致时边界仍可被绕过，没实跑。
- 只验了发送频率。「错误超过 3 次锁 10 分钟」那条来自上一课的验收标准，本文一行没实现。
- 并发。两个请求同时到达，Python 单线程字典看不出问题；换成 Redis 得靠 `SET NX PX` 或 Lua 才原子，没跑过。
- Spec Kit 与 Kiro 都只是读文档，没在本机装 Spec Kit、没跑过 Kiro 的 spec 流程，命令与文件名可能还有我没看到的变体。
