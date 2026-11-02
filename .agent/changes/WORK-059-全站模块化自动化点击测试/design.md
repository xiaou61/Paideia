---
artifact: design
work_id: WORK-059
work: 全站模块化自动化点击测试（ego-browser）
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 09:18:30 +0800
approver_role: CTO
---

# 全站模块化自动化点击测试设计

## 系统上下文

```
ego-browser CLI
  └─ nodejs 运行时执行 scripts/ego/run.mjs
       ├─ 读取 .secrets/ego.local.json（忽略文件，含线上地址与两个账号）
       ├─ 打开真实 Chromium，按角色分阶段：先学习者，再切换管理员
       ├─ 按 modules.mjs 顺序加载 scenarios/<模块>.mjs
       ├─ harness 执行点击与断言，失败截图
       └─ 写 .agent/testing/runs/<runid>-ego-<target>.md
```

不经过 Playwright，不调后端接口；所有动作都发生在真实页面上。

## 实施订正（2026-11-02，探针后）

原设计写「p1 学习者、p2 管理员同时登录」不成立：登录态在 localStorage，按 origin 共享，同一 profile 下两个账号会互相覆盖。
实际改为**按角色分阶段**——先跑学习者模块，再切换管理员跑管理端；同一时间只保持一个账号。
切换账号会登出该 ego profile 里当前的账号，因此配置里增加可选 `profileId`，把自动化固定到一个专用 profile。
详见 `.agent/notes/ego-click-probe.md`。

## 组件与职责

| 文件 | 职责 |
| --- | --- |
| `scripts/ego/modules.mjs` | 唯一模块清单：`id`、`name`、`side`（learner/admin）、`routes`、`scenario`、`minCases` |
| `scripts/ego/harness.mjs` | 会话封装：登录、跳转、点击、填表、断言、步骤记录、失败截图、安全前缀检查 |
| `scripts/ego/run.mjs` | CLI：参数解析、登录一次、按模块/全量执行、汇总、写报告、退出码 |
| `scripts/ego/check-map.mjs` | 覆盖闸门：解析两个 `router.tsx`，校验路由归属、场景存在、用例数 |
| `scripts/ego/scenarios/<id>.mjs` | 每个模块一个场景文件，导出 `cases` 数组 |
| `scripts/ego/README.md` | 运行方式、加模块步骤、用例写法、线上安全须知 |
| `.agent/testing/.artifacts/<runid>/` | 失败截图（忽略，不提交） |

## 请求或事件流程

### 一次运行

1. `run.mjs` 解析 `--module <id>` / `--all` / `--read-only` / `--write` / `--base-url <url>`。
2. 读 `.secrets/ego.local.json`；缺文件或缺账号直接退出并说明。
3. 创建/恢复 TaskSpace `paideia-ego-<YYYYMMDD-HHmm>`；`p1` 学习者、`p2` 管理员。
4. 学习者登录：`/login` → 填账号口令 → 等导航离开登录页；管理员同理。
5. 遍历模块 → 用例：
   - 每条用例开始前 `goto(baseUrl + '/')` 或模块指定起点；
   - 执行步骤，记录每步名称与耗时；
   - 失败：截图、记录 URL、最后一步、错误文本，继续下一条。
6. 写入类用例在 `finally` 中执行自己的清理；清理失败单独标记。
7. 汇总写 Markdown 与 JSON；有失败则退出码 1，清理失败退出码 2。

### 单条用例

```
case: { id, title, write, start?, run(session) }
```

`run` 里只写用户动作与断言；不写重试、不写固定等待。`session.step('打开笔记列表', ...)` 负责记录。

## 接口与数据

### harness 公开接口（任务之间必须字面一致）

```js
createSession({ page, baseUrl, role, runId })

session.goto(path)                       // 拼 baseUrl，等 load
session.click(text, label?)              // text="..." 精确匹配
session.clickRole(role, name, label?)    // loc=role:<role>[name='...']
session.fill(selector, value)            // 原生 fill
session.expectText(text, timeoutMs?)     // waitForFunction 页面正文包含
session.expectNoText(text, timeoutMs?)
session.expectUrl(fragment)              // waitForURL 子串
session.step(name, fn)                   // 记录步骤，失败时带步骤名
session.shot(name)                       // 截图到 artifacts，返回绝对路径
session.safeName(suffix)                 // `${prefix}${suffix}`，写入用例统一用它
session.assertSafeTarget(text)           // 删除前确认文本含运行前缀
```

### 场景文件接口

```js
export const meta = { module: 'knowledge', side: 'learner' }

export const cases = [
  {
    id: 'knowledge-main',
    title: '知识库主链路：分类 → 条目 → 正文 → 刷新仍在',
    write: false,
    async run(s) { ... },
  },
]
```

### 报告数据

```json
{
  "runId": "20261102-1530",
  "target": "online",
  "commit": "d6fe4f2",
  "dirty": true,
  "mode": "read-only",
  "startedAt": "...",
  "durationMs": 123456,
  "modules": [
    { "id": "knowledge", "passed": 5, "failed": 1, "skipped": 2, "durationMs": 32100 }
  ],
  "failures": [
    { "module": "knowledge", "case": "knowledge-main", "step": "打开条目正文", "url": "...", "screenshot": "..." }
  ]
}
```

Markdown 报告只写脱敏后的字段：`online` / `local`，不写主机名、IP、端口、账号。

## 持久化与迁移

无数据库迁移。唯一持久化是报告文件与失败截图：

- 报告追加写 `.agent/testing/runs/`，文件名含时间戳，不覆盖旧文件。
- 截图写 `.agent/testing/.artifacts/<runid>/`，加入 `.gitignore`。
- 运行创建的线上业务数据由各写入用例自行删除，不依赖数据库清理脚本。

## 失败处理与恢复

| 失败 | 行为 |
| --- | --- |
| 登录失败 | 整轮立即停止，报告写「登录失败」，不继续产生无效失败 |
| 单条用例失败 | 截图 + 记录，继续下一条 |
| 页面卡死/未提交 | 用 `page.cdp("Page.stopLoading")` 或重载一次；仍失败则记失败 |
| 清理失败 | 该用例标记 `cleanup_failed`，退出码 2，报告醒目列出残留名称 |
| 配置缺失 | 退出码 3，提示 `.secrets/ego.local.json` 的字段名，不打印口令 |
| 选择器不唯一 | 记失败并指出选择器；优先改成 `data-testid` 或更精确的 role |

## 安全与权限

- 凭据只在 `.secrets/ego.local.json`；该文件被 `.gitignore` 忽略。仓库只提交 `.secrets/ego.local.example.json`（空值）。
- 学习者与管理员各一个 Page，登录一次；不共享 Cookie。
- 写入用例必须 `write: true` 且整轮带 `--write`；只读轮次跳过。
- 所有新建对象名称以 `e2e-click-<runid>-` 开头；删除前 `assertSafeTarget` 检查文本含该前缀。
- 管理端禁止：删除用户、调整积分、关闭事件、删除非本次创建的知识条目/分类。这些操作不写进任何场景。
- 报告与截图提交前扫描：不得出现口令、令牌、邮箱、主机名、IP、端口。

## 可观测性

- 每条用例记录：模块、用例、步骤数、耗时、结果。
- 失败记录：最后一步、当前 URL、截图路径、错误消息。
- 运行结束打印一张模块汇总表；同时写 JSON 供后续脚本消费。
- 不做浏览器 console 采集（ego 默认隐藏 console，开启会暴露自动化）；用可见错误文案与白屏断言替代。

## 实施顺序

1. 探针：ego 在线上完成一次登录与一次点击，确认 API 可用（结论写 `.agent/notes/ego-click-probe.md`）。
2. 模块清单 + 校验脚本 + 自测。
3. harness + runner + 报告。
4. 学习者端场景 L1–L4。
5. 学习者端场景 L5–L8。
6. 学习者端场景 L9–L12。
7. 管理端场景 A1–A6。
8. 写入模式 + 清理守卫。
9. 全量只读跑 + 修不稳定用例。
10. README 与台账。

## 测试策略

- **校验脚本自测**：`node --test scripts/ego/check-map.test.mjs`，用临时 router 文件验证「全登记通过 / 漏登记失败 / 重复归属失败」。
- **框架冒烟**：`--module home --read-only` 在线上跑通。
- **场景覆盖**：`check-map` 断言每个模块至少 3 条用例。
- **反向验证**：临时在 `router.tsx` 加一条未登记路由，`check-map` 必须非零退出；验证后删除。
- **写入验证**：`--module notes --write` 创建 → 刷新 → 删除；再跑一次确认无残留。
- **全量验证**：`--all --read-only` 完整跑完，报告入台账。

## 需求追踪

| 需求 | 设计落点 | 验证 |
| --- | --- | --- |
| REQ-001 | `modules.mjs` | `check-map.mjs` |
| REQ-002 | `scenarios/*.mjs` | `check-map` 用例数 + 全量跑 |
| REQ-003 | `run.mjs` 参数 | AC-002 / AC-004 |
| REQ-004 | `check-map.mjs` | 反向验证 |
| REQ-005 | `safeName` / `assertSafeTarget` | AC-005 / AC-008 |
| REQ-006 | `.secrets` + 报告脱敏 | AC-006 |
| REQ-007 | `step` / `shot` / 失败字段 | AC-007 |
| REQ-008 | `write` 标记 + `--write` | AC-002 / AC-005 |
| REQ-009 | 单次登录 + 条件等待 | AC-008 |
| REQ-010 | 报告写入 | AC-010 |
| REQ-011 | 场景只做用户流程 | 评审 |
| REQ-012 | 用例标题与步骤名 | AC-007 |

## 待决定事项

无。
