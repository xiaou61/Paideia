---
artifact: design
work_id: WORK-060
work: 数据集工坊与公开私有治理
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 11:10:58 +0800
approver_role: CTO
---

# 数据集工坊与公开私有治理设计

## 系统上下文

```
SQL 工作台（/practice/sql）
  ├─ 数据集选择器 ── GET /sandbox/status（形状不变）
  ├─ 「浏览数据集」── GET /sql-datasets/catalog ── CatalogService ── AccountDirectory（用户名）
  │                    └─ 作者切换 ── POST /sql-datasets/{key}/visibility
  ├─ 数据集面板（新建 / 改现有）
  │    ├─ 保存 ── POST /sql-datasets {name, sql, visibility}
  │    └─ 改现有：表清单 ── ask() ── 助手 ── sql_read_dataset ── 原 SQL
  └─ 助手对话 ── 「存为数据集」── applyDatasetSql ── 面板
```

写路径只有一条：`POST /api/v1/sql-datasets` → `SqlDatasetUploadService` 的大小检查 → 白名单 → 沙箱干跑 + 抓结构 → `insert`。
目录与切换只读/改可见性，不碰内容。

## 组件与职责

| 组件 | 职责 | 改动 |
| --- | --- | --- |
| `SqlDatasetUploadService` | 校验并保存；`visibility` 决定 source/status | 改签名与插入值 |
| `SqlDatasetMapper` | `demoteToPrivate`；`listVisible` 已有 | 加一条 UPDATE |
| `SqlDatasetController` | 上传、切换可见性、目录 | 加两个端点，上传加字段 |
| `SqlDatasetCatalogService` | 组装内置 + 我的 + 公开；解析上传者名字 | 新建 |
| `DatasetOwnerNames` | sandbox 的只读用户名端口 | 新建 |
| `AccountDirectory` + impl | account 的只读用户名目录 | 新建 |
| `DatasetOwnerNamesConfiguration` | app 装配根桥接 | 新建 |
| `SqlDatasetTools.ReadDatasetSql` | 只读返回数据集完整 SQL | 新建工具 |
| `AgentToolBridge` | `ASSISTANT` 可见 `planner` 组 | 加一项 |
| `SqlPracticePage` | 选择器 + 浏览入口 + 数据集面板 + 预览 + 可见性 | 改 |
| `DatasetCatalogDialog` | 目录弹窗 | 新建 |
| `assistant/context.tsx` | `applyDatasetSql` 待消费通道 | 加一个字段 |
| `conversation-panel.tsx` | SQL 工作台的「存为数据集」 | 加按钮 |
| `dataset-schema.ts` | `parseCreateTableNames`（仅预览） | 加纯函数 |
| `SqlDatasetsPage`（管理端） | 上传者列 | 加一列 |

## 接口与数据

### 上传

`POST /api/v1/sql-datasets`

```json
{ "name": "我的订单库", "sql": "CREATE TABLE ...; INSERT ...;", "visibility": "private" }
```

- `visibility`：`private`（默认，缺省/空按 private）或 `public`；其它值 400。
- 成功返回 `{ "key": "<uuid>" }`。
- `private` → `source='private'`, `status='draft'`；`public` → `source='community'`, `status='published'`。
- 仍然过同一套白名单 + 干跑 + 配额。

### 切换可见性

`POST /api/v1/sql-datasets/{key}/visibility`

```json
{ "public": true }
```

判定顺序：

1. 内置 key（`datasets.isBuiltin(key)`）→ 400「内置数据集不能修改可见性」；
2. `findByKey` 为空、或 `owner_user_id` 不是当前用户 → 404「没有这个数据集」（同一句，不给枚举差异）；
3. `status='taken_down'` → 400「已被管理员下架，不能修改可见性」；
4. `public=true`：
   - `source='private'` → `promoteToCommunity`（source=community, status=published）；
   - `source='community'` 且已 published → 幂等成功；
5. `public=false`：
   - `source='community'` → `demoteToPrivate`（source=private, status=draft）；
   - `source='private'` → 幂等成功。

### 目录

`GET /api/v1/sql-datasets/catalog`

```json
[
  { "key": "orders-public", "name": "orders-public", "visibility": "public",
    "source": "builtin", "ownerUserId": null, "ownerName": "平台内置",
    "tableCount": 3, "rowCount": 0, "mine": false },
  { "key": "uuid", "name": "我的库", "visibility": "private",
    "source": "private", "ownerUserId": 7, "ownerName": "lzf",
    "tableCount": 2, "rowCount": 12, "mine": true }
]
```

- 内置：`datasets.publicKeys()` + `schema` 快照数表；`rowCount` 固定 0（界面显示「—」）。
- 私人/社区：`mapper.listVisible(userId)`，已排除别人的私人集与未发布/已下架的社区集。
- 上传者：`DatasetOwnerNames.usernames(ownerIds)` 批量解析；`owner_user_id` 为 NULL → 「已注销用户」；解析不到 → 「用户 <id>」（只在这种情况下回退到 id，避免空字符串）。
- 排序：内置 → 我的私人 → 公开社区；同组按名字。

### 管理端

`GET /api/v1/sandbox/admin/sql-datasets` 的 `CommunityView` 增加 `ownerName`；解析方式同目录。前端表格加「上传者」列。

### 工具

`sql_read_dataset(key)`：

- `toolset="sql"`，`readOnly=true`，`scope=READ`；
- 隐藏内置集直接返回「没有找到」（与目录口径一致）；
- 返回 `datasets.sql(userId,key)` 的原文，超过 20,000 字符截断并注明「后面还有 N 字符」；
- 描述明确：**只在用户要求修改数据集时调用**；返回含数据行。

`planner_build_dataset` 已在 `planner` 组，给 `ASSISTANT` 的 `toolsetsOf` 加 `"planner"` 即可；工具本身不变。

## 页面流程

### 保存一个新数据集

1. 用户填主题 → 点「让 AI 设计」→ `ask()` 打开助手并预填；
2. 助手问清后调 `planner_build_dataset`，回答里出现 ```sql 块；
3. 用户点回答下「存为数据集」→ `applyDatasetSql(sql)`；
4. 页面打开数据集面板、填入 SQL、切到「新建」模式、显示「预计 N 张表：…」；
5. 用户填名字、选公开/私有 → 点「确认保存」→ `POST /sql-datasets`；
6. 成功后刷新 `sandbox-status` 与目录，选中新 key。

### 基于现有数据集加数据

1. 用户选一个数据集 → 打开面板 → 「基于当前数据集改」；
2. 面板显示当前数据集的表清单（来自已加载的 schema）；
3. 用户填「每张表加 N 行」，点某张表的「+N 行」→ `ask('当前数据集是 <key>。请先用 sql_read_dataset 读取完整 SQL，再给 <table> 追加 N 行，其他表不变，最后给出完整 SQL。')`；
4. 助手读 SQL、产出完整新 SQL → 用户点「存为数据集」→ 面板填入；
5. 面板提示「会另存为新数据集，原数据集不变」；用户确认名字与可见性 → 保存。

### 浏览与切换可见性

1. 选择器旁「浏览数据集」→ 弹窗请求 `/sql-datasets/catalog`；
2. 分「公开」「我的」两组；每行显示名字、可见性徽章、表数/行数、上传者；
3. 「使用」→ 关闭弹窗并选中该 key；
4. 我的数据集有「设为公开 / 设为私有」按钮；切私有前二次确认，成功后刷新目录与 `sandbox-status`。

## 持久化与迁移

**无迁移。** 复用 `sql_datasets.source` / `status`：

| 用户可见状态 | source | status |
| --- | --- | --- |
| 私有 | private | draft |
| 公开 | community | published |
| 管理员下架 | community | taken_down |

内置集仍不落表。

## 安全与权限

- 目录只返回 `key/name/visibility/source/ownerUserId/ownerName/tableCount/rowCount/mine`；**不返回 schema、SQL、数据行、邮箱、角色**。
- 切换端点按令牌主体判 `owner_user_id`；非作者与不存在同一句 404。
- 内置集不能切换；`taken_down` 不能由作者改回公开或私有（管理员动作优先，作者可删除）。
- `sql_read_dataset` 自己再判一次可见性（不假设模型只传有权的 key）；隐藏内置集拒绝。
- AI 读取的 SQL 会进入模型上下文，只在用户主动点「加」时发生；普通提问不带整份 SQL。

## 失败处理与恢复

| 失败 | 处理 |
| --- | --- |
| 目录请求失败 | 弹窗显示可重试错误；选择器仍可用（旧 `/sandbox/status` 不受影响） |
| 切换可见性失败 | 弹窗内显示错误，不乐观更新 |
| 保存失败（白名单/干跑/配额） | 面板保留 SQL 与名字，显示后端原文；不丢用户输入 |
| 助手不可用 | 「直接生成并保存」降级仍在；目录与手动保存不依赖助手 |
| 预览表数为 0（模型没给 CREATE TABLE） | 显示「没识别到建表语句，保存时以沙箱校验为准」，不阻止保存 |
| 上传者解析失败 | 显示「用户 <id>」；不因一个名字让整个目录 500 |

## 可观测性

- 复用现有 `ai_runs`：`planner_build_dataset` / `sql_read_dataset` 的调用会出现在工具步骤里。
- 目录与切换不新增指标；切换失败走现有 BizException 日志。
- 不在日志里打印 SQL 内容或用户名列表。

## 实施顺序

1. 后端：上传 visibility + 切换 + mapper（TASK-001）；
2. 后端：用户名目录 + 目录服务/端点 + 管理端 ownerName（TASK-002）；
3. 后端：AI 工具接线（TASK-003）；
4. 前端：client + 目录弹窗 + 选择器入口（TASK-004）；
5. 前端：数据集面板两模式 + 预览 + 可见性（TASK-005）；
6. 前端：助手「存为数据集」通道（TASK-006）；
7. 验证（TASK-007）。

## 已知边界

- 目录一期最多 200 条，不分页。
- 预览只数 `CREATE TABLE` 字面量；注释里的同名语句会被算进去，真实结构以干跑为准。
- 作者把公开集切回私有不删除内容；正在用它的人会立刻看不到。
- 管理端仍不能编辑内容，只能下架/恢复。
