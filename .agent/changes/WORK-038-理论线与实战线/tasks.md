---
artifact: tasks
work_id: WORK-038
work: 从零开始分层：理论线与实战线
status: approved
created: 2026-10-26
updated: 2026-10-26
approved_by: xiaou61
approved_at: 2026-10-26 11:03:31 +0800
approver_role: CTO
---

# 从零开始分层：实施计划

## 任务状态

每个任务完成时把 `pending` 改成 `done`。`blocked` 只在外部条件不满足时使用，并在任务下写明缺什么。

## S1 · 架子（mode 字段与两栏）

### TASK-001 | pending | 迁移：learning_tracks 增加 mode 与 theory_track_slug

- 文件名 `V20261026120000__add_track_mode.sql`，只加结构与索引，不插数据。
- 验证：空库跑完整迁移链；`SHOW COLUMNS` 看到两列；存量行 `mode='theory'`。

### TASK-002 | pending | 后端：TrackView / TrackMap 带出 mode 与 theoryTrackSlug

- `TrackMapper.findStages` 的 track 查询加两列；`TrackService` 两个投影都带上。
- 不新增端点，不改 JSON 信封形状。
- 验证：`GET /learn/tracks` 每项有 `mode`；`GET /learn/tracks/{slug}` 有 `mode` 与 `theoryTrackSlug`。

### TASK-003 | pending | 导入脚本：读 line_mode / line_theory / verify

- `lineSpecOf()` 读三个键，缺省 theory；`mode=practice` 且无 `verify` 时报错退出。
- `theory_track_slug` 指向不存在的轨道时打警告但不阻断。
- 验证：dry-run 一条 practice 线看是否报错；给正确声明后 dry-run 通过。

### TASK-004 | pending | 前端：/learn 分两栏 + 空状态

- 理论栏与实战栏各自显示条数与已通关数；实战栏为空显示"实战线正在准备中"。
- 不改导航（`/learn` 已在导航里，不新增顶级入口，故不动 `preferredNav`）。
- 验证：e2e 断言两栏标题存在、理论栏有卡片；把实战内容清空时显示空状态。

### TASK-005 | pending | 限流提到 1000

- `application.yml`：`daily-limit` 50 → 1000，`sql-daily-limit` 500 → 1000。
- 线上配置的 `sql-daily-limit: 20` 在部署时同步改成 1000。
- 验证：`/sandbox/status` 返回 `dailyLimit: 1000, sqlDailyLimit: 1000`。

## S2 · 理论可以"完成"

### TASK-006 | pending | 通过判定按 mode 分支

- `LearningProgressRules` 增加按 mode 判定的入口；旧三参签名保留给课程。
- `LearningProgressService.update()` 反查该 entry 所属轨道的 mode；多条轨道取最严。
- 理论：只认小测；实战：只认验收块；课程：两者都要（不变）。
- 验证：单测覆盖四种组合（只有小测过 / 只有动手过 / 两者都过 / 都没过）在各 mode 下的结果。

### TASK-007 | pending | 回归：课程语义与已通过记录不变

- 挑一门课（Python）跑断言语义与改动前一致的用例。
- 已有 `passed` 行在无新上报时保持原状。
- 验证：反向验证——把通过判定改回"理论也要动手"，理论关卡用例应红。

## S3 · SQL 实战

### TASK-008 | pending | 「SQL 数据分析实战」10 题

- 递进：空值筛选 → 聚合 → 多表 → 窗口 → 留存/复购。
- 每题带 `reference`、`alternatives`、`antiPatterns`、`expectHash`（含至少一个隐藏数据集）。
- 验证：`scripts/verify_sql_live.py` 逐题真跑；`check_sql_judge_parity.py` 通过。

### TASK-009 | pending | SQL 实战轨道登记与线上导入

- `line_mode: practice`、`verify: sql`、`line_theory` 指向 mysql-from-zero。
- 验证：线上 `/learn/tracks` 出现该轨道且 `mode=practice`；10 题在线上判题正确/反模式不通过。

## S4 · 读代码实战 + 调试实战

### TASK-010 | pending | 「读懂一个 Spring Boot 请求链路」读代码实战

- 用 `code-tour` 组织，素材取自本仓库真实代码，文末标来源与许可。
- 验证：沙箱不可用时仍可完整作答（断掉 sandbox 依赖后页面无空按钮）。

### TASK-011 | pending | 「Java 异常排查」调试实战

- 用 `run` 题型：初始代码可运行、逻辑有缺陷、期望输出明确。
- 至少 3 题。
- 验证：初始代码能跑出错误结果；修正后输出一致并通过。

## S5 · 项目式实战

### TASK-012 | pending | 沙箱项目模式：多文件提交与资源上限

- 协议增加 `files[]` + `entry`；服务端校验路径、文件数、大小；命令仍由服务端预置。
- 容器 `--network none`；CPU/内存/输出上限。
- 验证：3 个文件能跑；21 个文件被拒并给原因；超时被终止且原因可读。

### TASK-013 | pending | 预置测试判题与失败用例名

- 测试文件与用户文件分离；按测试报告判定；失败返回用例名。
- 验证：故意写错一个方法，断言返回的是该用例名而不是笼统"不通过"。

### TASK-014 | pending | 第一条项目式实战内容

- 纯 JDK、不联网、可用镜像预装库。
- 验证：从 0 做完能全绿；把一行改错会红并点名用例。

## S6 · 理论补四块

### TASK-015 | pending | 并发 / JVM / 数据库原理 / 系统设计 四条理论线

- 每条 5–6 篇，格式与既有阅读线一致（frontmatter、learning-path、quiz、≥2 误区）。
- 写前对照既有 62 篇标题与结论段，重叠处改写成接续。
- 验证：结构校验脚本通过；学习路径步数等于正文篇数。

### TASK-016 | pending | 理论与实战配对声明

- 每条实战线声明 `line_theory`；地图页显示"建议先看 X"。
- 验证：点该链接能到理论轨道。

## 收口

### TASK-017 | pending | 全量验证与部署

- 后端全量、前端单测与类型检查、线上 e2e。
- 部署后核对 `/sandbox/status` 两个 1000、`/learn/tracks` 的 mode 分布、实战线可判题。
- 验证：记录实测数字；反向验证至少一项（理论通关规则）。

## 未覆盖（明确不做）

- 不做"理论完成才解锁实战"的门禁。
- 不做 AI 评分。
- 不做任意第三方依赖安装（项目式只承诺镜像预装）。
- 不改 `kind` 语义。
- 不重写已有 14 条轨道内容。
