# 全站自动化点击（ego-browser）

WORK-059 的点击测试：用 ego-browser 驱动真实 Chromium，按模块打开线上页面、点按钮、填表单、断言可见结果。

## 先准备

1. 本机已安装 ego-browser：`ego-browser skill` 能打印使用指南。
2. 复制配置样例，填入线上地址与账号口令：

   ```bash
   cp scripts/ego/ego.local.example.json .secrets/ego.local.json
   ```

   `.secrets/` 已被 `.gitignore` 忽略，口令不会入库。`profileId` 可选；填了就把自动化固定到那个 ego profile。

## 跑

```bash
node scripts/ego/cli.mjs --list
node scripts/ego/cli.mjs --module home --read-only
node scripts/ego/cli.mjs --module home,knowledge --read-only
node scripts/ego/cli.mjs --all --read-only
node scripts/ego/cli.mjs --module notes --write
```

- `--read-only`（默认）：跳过所有 `write: true` 的用例，不创建业务数据。
- `--write`：执行写入用例；每条用例自己创建、自己删除，名称都带 `e2e-click-<runid>-` 前缀。
- `--all`：先跑学习者 12 个模块，再切换管理员跑管理端 6 个模块。**同一时间只保持一个账号**。

报告写到 `.agent/testing/runs/<runid>-ego-<target>.md`；失败截图写到 `.agent/testing/.artifacts/<runid>/`（已忽略，不提交）。

## 结构

| 文件 | 作用 |
| --- | --- |
| `modules.mjs` | 唯一模块清单：模块 → 路由 → 场景文件 → 账号角色 |
| `harness.mjs` | 登录、跳转、点击、填表、断言、步骤记录、失败截图、安全前缀 |
| `run.mjs` | 执行器：按模块/全量跑，失败不中断，最后写报告 |
| `cli.mjs` | 命令入口：解析参数、写 `.run.json`、调 ego |
| `check-map.mjs` | 覆盖闸门：路由必须全部登记、场景必须存在且用例数够 |
| `scenarios/*.mjs` | 每个模块一个场景文件，导出 `cases` |

## 加一个模块 / 一条用例

1. 在 `modules.mjs` 里登记模块与路由（路由必须与 `router.tsx` 字面一致）。
2. 新建 `scenarios/<id>.mjs`：

   ```js
   export const meta = { module: 'my-module', side: 'learner' }

   export const cases = [
     {
       id: 'my-module-main',
       title: '主链路：用户动作 + 可观察结果',
       write: false,
       async run(s) {
         await s.step('打开页面', async () => {
           await s.goto('/#/my-page')
           await s.expectText('页面标题')
         })
       },
     },
   ]
   ```

3. 跑 `node scripts/ego/check-map.mjs` 与 `node scripts/ego/cli.mjs --module <id> --read-only`。

新增用户可见路由却没登记，`check-map` 会非零退出——这是防止“页面上了但测试没跟上”的闸门。

## 写法约定

- 一条用例 = 用户动作 + 可观察结果；标题用中文，失败时能看出停在哪一步。
- 优先 `data-testid`；其次 `loc=role:...` 与 `text="..."`。
- 页面同时渲染桌面/移动两套列表时，用 `clickFirstVisible()`，不要用会匹配隐藏元素的 `click()`。
- 不要写固定 `sleep`；用 `expectText` / `waitFor` / `expectUrl` 等可观察条件。
- 助手面板、账号菜单、弹窗会跨页面保留；runner 每条用例前会按 Esc 并收起助手，场景里不用重复处理。
- 只断言“有内容或明确空态”，不要写死具体条目标题；需要夹具时用 `s.safeName()` 生成唯一名称。

## 线上安全

- 只使用 `e2e-learner` 与 `admin` 两个账号；不新建账号。
- 写入用例必须 `write: true`，且整轮带 `--write`。
- 删除前用 `assertSafeTarget()` 确认目标带运行前缀。
- 管理端禁止：删除用户、调整积分、关闭事件、删除非本次创建的知识条目/分类。
- 报告只写 `online`/`local`，不写主机名、IP、端口、账号、令牌。提交前扫一遍 `git diff --cached`。

## 已知限制

- ego 的 `-e` 不支持顶层 await；`cli.mjs` 用 stdin + 绝对 `file://` 导入入口，不要改回 `-e`。
- 同一 ego profile 下两个账号不能共存：登录态按 origin 共享，切换账号会登出前一个。
- 打开知识库条目会记一次浏览；只读模式也不是零副作用。
- 不采集浏览器 console（ego 默认隐藏，开启会暴露自动化）；用可见错误文案与白屏断言替代。
