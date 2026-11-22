---
artifact: design
work_id: WORK-047
work: 岗位地图与 AI 推荐
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-010, WORK-024, WORK-038]
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 11:24:13 +0800
approver_role: CTO
---

# 设计 · 岗位地图与 AI 推荐

## 系统上下文

```
GET /api/v1/learning/lines        （已有：轨道 + 进度，一次全量）
GET /api/v1/account/profile       （已有：target_role）
        │
        ▼
role-map.ts    岗位枚举 + 22 条线的映射        ← 代码常量
recommend.ts   打分 + 排序 + 规则理由           ← 纯函数，可单测
        │
        ├──► LearnPage：岗位选择器 / 三段地图 / 推荐卡
        │
        └──► POST /api/v1/learning/advice      （新：只把理由润色）
                    │
                    ▼
             既有 AI 客户端 + 积分预留结算
```

**没有新表，没有新迁移**。唯一的新接口是 `POST /api/v1/learning/advice`。

## 岗位与映射

```ts
export type RoleKey = 'backend' | 'ai-algo' | 'data' | 'frontend'

export type Tier = 'core' | 'advanced' | 'optional'

/** 一条线在某个岗位里的定位。没有列出的线一律按 optional 兜底。 */
export interface RoleTrack { slug: string; tier: Tier; rank: number }
```

映射表（22 条线全覆盖，兜底见下）：

| 岗位 | 核心 | 进阶 | 选读（不列即为选读） |
| --- | --- | --- | --- |
| `backend` 后端开发 | `java-from-zero`、`spring`、`redis`、`mysql-from-zero`、`network-basics` | `concurrency`、`jvm`、`system-design`、`os` | 其余 13 条 |
| `ai-algo` 算法与 AI | `algorithms`、`python-from-zero`、`ai` | `patterns`、`agents`、`system-design` | 其余 14 条 |
| `data` 数据开发 | `sql-practice`、`mysql-from-zero` | `redis`、`system-design`、`algorithms` | 其余 14 条 |
| `frontend` 前端开发 | （空） | （空） | 空；页面显示"内容建设中" |

**兜底规则（必须被单测钉住）**：任何 slug 不在该岗位的显式列表里，一律 `tier: 'optional'`、`rank: 999`。
新增轨道忘了标岗位时，它不会从所有岗位里消失，只是排在选读的最后。

**为什么不用三张表**：映射是编辑判断，没有数据可调参；与身份配置同一条理由。

## 打分

```ts
export interface TrackProgress {
  slug: string
  name: string
  passedCount: number
  stageCount: number
  lastTouchedAt?: string | null   // 有进度记录时用来做"最近动过"的衰减
}

export interface Score {
  slug: string
  score: number
  tier: Tier
  parts: { role: number; remaining: number; fresh: number; recent: number }
}
```

```text
tierWeight  = core 60 | advanced 40 | optional 20
role        = tierWeight
remaining   = (1 - passed/stage) × 40           // 全未学 +40，全学完 +0
fresh       = passed === 0 && stage > 0 ? 10 : 0 // "开始"这一步最难
recent      = 最近 48h 内动过 ? -15 : 0
score       = role + remaining + fresh + recent
```

规则：

1. **全部通过（`passed >= stage`）的线直接排除**，不参与推荐。
2. 同理**没有关卡（`stage === 0`）**的线排除——推了也点不进去。
3. 按 `score` 降序，同分按 `rank` 升序，再同分按 `slug` 字典序（保证稳定）。
4. 取前 3；不足 3 条就有几条给几条。
5. **至少保一条核心**（若核心段仍有未完成项）：先按分数取，若前 3 里没有 core，用分数最高的 core 替换掉分数最低的非 core 项。
   理由：纯按分数排会把"进阶且完成度高"的线推到核心未开始的线之前，而后者才是这个岗位的入口。

`lastTouchedAt` 由调用方注入（默认 `null`），测试里传固定值；函数本身不读系统时间。

## 规则理由（AI 不可用时的兜底）

| 情形 | 理由模板 |
| --- | --- |
| 未开始 + 核心 | `后端岗的入口，你还没开始。{stage} 关，一次一关就够。` |
| 未开始 + 进阶/选读 | `这条你还没碰过。{stage} 关。` |
| 进行中 | `{passed}/{stage} 关已过，下一关是「{nextTitle}」。` |
| 核心且前置已满足 | 在上一句后面追加 `前置要求你已经具备。` |

模板里出现的数字**全部来自入参**，不写死。

## `/learn` 页面结构

顺序（自上而下）：

1. `PageHeader`：标题「从零开始」，副标题「选一个岗位，按它的顺序走」。
2. **岗位选择器**：4 个 pill，`role="group"` + `aria-pressed`；右侧一句小字「岗位只影响推荐顺序，不影响能看什么」。
3. **推荐卡**（`data-testid="learn-advice"`）：
   - 三条，每行：序号 / 线名 / 一句理由 / 进度 / 箭头。
   - 右上角「换一批」：清掉当日缓存并重新调用 AI（花第二次积分）。
   - 底部可折叠「推荐依据」：展开显示每条的四个分项。
4. **「继续上次」**（既有组件，保留）。
5. **三段**：核心 / 进阶 / 选读，各自一个 `TrackSection`。

三段各自的说明文案（REQ-009）：

- 核心：`这个岗位没人能跳过的东西。先把这几条走完，再谈其他。`
- 进阶：`有核心打底之后才看得懂的部分。顺序没有硬性要求。`
- 选读：`加分项，不是门槛。面试前翻一翻，或者遇到具体问题再回来看。`

**空状态**：`frontend` 岗位选中时，推荐卡与三段都不渲染，改为一块说明：

> 前端岗位的内容还在建设中。当前先按「后端开发」给你推荐——上面切换岗位即可。

这不是文案补丁，是**内容缺口**（REQ-004、Q-004）。

## AI 建议接口

`POST /api/v1/learning/advice`

```json
{
  "role": "backend",
  "roleLabel": "后端开发",
  "picks": [
    { "slug": "jvm", "name": "JVM 排查", "tier": "advanced", "passed": 0, "total": 6 }
  ],
  "startedCount": 2,
  "totalCount": 22
}
```

响应：

```json
{ "code": 0, "data": { "reasons": [{ "slug": "jvm", "reason": "……" }] } }
```

约束：

- `picks` 最多 3 条，`reason` 每条限 60 字；服务端校验并截断，不信任客户端。
- 服务端**先算一遍校验和**：`slug` 必须存在于轨道表，否则丢弃该条（防止客户端拿端点当通用聊天接口）。
- 提示词固定，只有这几个字段可注入；**不拼自由文本**。
- 走既有积分：预留 → 调用 → 结算；余额不足时返回 402，客户端按降级处理。
- **任何失败都返回 200 + `reasons: []`**，不向前端抛错误——理由缺失是产品允许状态，不是异常。只有参数非法（slug 不存在、条数超限）才返回 400。

## 缓存

前端 `localStorage` 键：`paideia:advice:<role>:<YYYY-MM-DD>`，值 `{slug, reason}[]`。

- 命中则**不发请求**（AC-006）。
- 「换一批」清掉当天该岗位的键并重新请求。
- 缓存读失败（隐私模式、配额满）按未命中处理，不影响页面。

## 措辞改动（REQ-007）

22 条 `learning_tracks.summary` 现在写的是目录（"八篇：复杂度、数组链表……"）。改成一句话说**学了能干什么**：

- 旧：`八篇：复杂度、数组链表、哈希表、树与堆、图、排序、动态规划、递归回溯。`
- 新：`看完能在选型会上说清「这题该用什么数据结构」，以及为什么。`

改动走 `content/` 的 `line_summary` 字段 + 导入脚本，不手改数据库。**这是内容改动，不是代码改动。**

## 已知限制

- **前端岗位没有内容**。这是真实的缺口，本项只负责让它可见。
- **岗位映射靠人工维护**。新增轨道默认落到"选读"，需要编辑记得补。
- **"最近 48h 动过"依赖 `lastTouchedAt`**，而当前 `learning/lines` 不返回它——S1 先用 `null`（等于不衰减），S2 再补这个字段。
- **推荐不含知识库条目**，只推轨道。条目级推荐需要另一套数据（自评、阅读记录），不在本期。
