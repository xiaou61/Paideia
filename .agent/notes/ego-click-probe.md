# ego 点击探针（WORK-059 TASK-001）

2026-11-02，本机 ego-browser v2.2.0（windows）。结论都来自实际执行。

## 能用的

- `ego-browser nodejs < script.mjs` 可以驱动线上真实 Chromium；`page.goto` / `click` / `fill` / `waitForFunction` / `snapshot` / `screenshot` 均可用。
- 登录表单有稳定选择器：`[data-testid="login-identifier"]`、`[data-testid="login-password"]`、`[data-testid="login-submit"]`。
- 登录态锚点：`[data-testid="current-user"]`；账号菜单：`[data-testid="account-menu-trigger"]`；登出：`[data-testid="logout"]`。
- 管理端 6 个模块的只读冒烟 20 条全部通过，单条 0.1–1.2 秒，总耗时约 5 秒。
- 管理端地址前缀是 `/admin/#/`，与学习者端的 `/#/` 不同。

## 踩到的坑

1. **stdin 脚本的相对导入按 ego 安装目录解析**，不是脚本目录。直接 `ego-browser nodejs < run.mjs` 会报
   `Cannot find module 'C:\Program Files\Citro Labs\ego lite\...\harness.mjs'`。
   解法：stdin 里只写 `await import('file:///绝对路径/run.mjs')`，入口文件自己的相对导入就正常了。
2. **`-e` 不支持顶层 await**（报 `await is not defined`）。用 stdin，不要用 `-e`。
3. **ego 用的是用户浏览器 profile，默认已登录 `admin`**；两个 profile（`Profile 1` / `Profile 2`）实测都显示 `admin`。
4. **同一 profile 下两个账号不能共存**：登录态在 localStorage，按 origin 共享。学习者与管理员必须分阶段跑，切换账号会把当前账号登出。
5. 打开知识库条目会记一次浏览（既有行为），所以「只读」不等于零副作用。

## 对设计的影响

- 原设计写「p1 学习者、p2 管理员同时登录」不成立。改为**按角色分阶段**：先学习者，再切换管理员；同一时间只保持一个账号。
- 账号切换会登出该 ego profile 里当前的账号。跑学习者模块前必须确认用哪个 profile，避免影响用户自己的会话。
- 配置里增加可选 `profileId`，把自动化固定到专用 profile。

## 已确认

- 学习者模块固定用 `Profile 2`（2026-11-02 用户裁决）；该 profile 已切换为 `e2e-learner`，默认 `Profile 1` 未动。
- 全量只读 18 个模块 56 条通过、1 条写入用例按预期跳过；`notes` 与 `admin-knowledge` 的写入轮次各跑两次，均无残留。
