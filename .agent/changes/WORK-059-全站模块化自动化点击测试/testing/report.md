---
artifact: test-report
work_id: WORK-059
work: 全站模块化自动化点击测试（ego-browser）
status: passed
evidence: required
created: 2026-11-02
updated: 2026-11-02
verified_commit: a7345db
---

# 全站模块化自动化点击测试验证报告

## 验证环境

- 代码：`a7345db`（`feat(ego): 全站模块化自动化点击框架与 18 个模块场景（WORK-059）`）
- 目标：线上实例（学习者端 `/`、管理端 `/admin/`）
- 驱动：ego-browser v2.2.0（Windows），固定 `Profile 2`
- 账号：`e2e-learner`（学习者）、`admin`（管理员）
- 时间：2026-11-02 09:40–09:59 +0800

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 覆盖闸门自测 | `node --test scripts/ego/check-map.test.mjs` | 0 | 4 passed / 0 failed | 控制台输出 |
| 真实路由对账 | `node scripts/ego/check-map.mjs` | 0 | 学习者 36/36、管理端 21/21、18 模块、57 用例 | 控制台输出 |
| 全量只读 | `node scripts/ego/cli.mjs --all --read-only` | 0 | 56 passed / 0 failed / 2 skipped（两条写入用例） | `.agent/testing/runs/20261102-095639-ego-online.md` |
| 全量含写入 | `node scripts/ego/cli.mjs --all --write` | 0 | 58 passed / 0 failed / 0 skipped | `.agent/testing/runs/20261102-095512-ego-online.md` |
| 笔记写入连续两次 | `node scripts/ego/cli.mjs --module notes --write` ×2 | 0 / 0 | 各 4 passed；第二次不因残留失败 | `20261102-095748-ego-online.md`、`20261102-095800-ego-online.md` |
| 管理端写入连续两次 | `node scripts/ego/cli.mjs --module admin-knowledge --write` ×2 | 0 / 0 | 各 4 passed；第二次不因残留失败 | `20261102-095809-ego-online.md`、`20261102-095818-ego-online.md` |
| 路由闸门反向验证 | 临时 router 加 `path: '/ghost-route'` 后跑 `check-map` | 1 | 输出「learner 路由未登记：/ghost-route」 | 本轮控制台输出 |
| 失败证据 | 故意改错 `home` 断言后跑 `--module home --read-only` | 1 | 报告含模块/用例/步骤/URL/截图 | `20261102-095854-ego-online.md` |
| 恢复后单模块 | `node scripts/ego/cli.mjs --module home --read-only` | 0 | 3 passed | `20261102-095919-ego-online.md` |
| 报告脱敏 | `grep -RniE "https?://\|[0-9]{1,3}(\.[0-9]{1,3}){3}" .agent/testing/runs/*ego-online.md` | 1（无匹配） | 无主机、账号、口令、URL | 本轮控制台输出 |

## 验收结果

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| AC-001 路由全覆盖；未登记路由失败 | 通过 | `check-map` 36/36 + 21/21；反向验证退出 1 |
| AC-002 单模块线上跑通并产出报告 | 通过 | `home`、`knowledge`、`notes` 等单模块运行均 0 退出 |
| AC-003 18 个模块、每模块 ≥3 条用例 | 通过 | `check-map`：18 模块 / 57 用例；最小 3 条 |
| AC-004 全量只读完整跑完 | 通过 | `095639`：56 通过 / 0 失败 / 2 跳过，29 秒 |
| AC-005 写入带前缀并清理 | 通过 | 两个写入模块各连续两次；笔记页与管理端分类页搜索 `e2e-click-` 无残留 |
| AC-006 报告与截图不含敏感信息 | 通过 | 脱敏扫描无命中；报告只写 `online` |
| AC-007 失败报告可诊断 | 通过 | `095854`：模块 `home`、用例 `home-main`、步骤「首页有内容区」、地址 `/#/`、截图路径 |
| AC-008 连续两次不因残留失败 | 通过 | `notes` 与 `admin-knowledge` 各两次 4/4 |
| AC-009 只改测试与文档 | 通过 | `git show --stat a7345db`：29 个文件，全部在 `scripts/ego/`、`.agent/`、`.gitignore` |
| AC-010 台账按时间追加 | 通过 | `.agent/testing/runs/` 下 8 份本轮报告，旧文件未覆盖 |

## 失败与未验证项

- 最终轮次 0 失败。开发过程中出现过选择器匹配隐藏元素、账号菜单遮挡点击、SQL 编辑器焦点三处失败，已分别用 `clickFirstVisible`、每条用例前按 Esc、`focus` 修复；最终全量绿。
- 未验证：浏览器 console 错误采集（ego 默认隐藏 console，开启会暴露自动化）；管理端破坏性操作（删用户、调积分、关事件）；AI 回答质量。
- 只读模式不是零副作用：打开知识库条目会记一次浏览，这是产品既有行为。

## 剩余风险

- 线上数据：写入用例只创建带 `e2e-click-<runid>-` 前缀的数据并删除；清理失败会以退出码 2 报出，但极端情况下仍可能残留，需要按报告中的名称人工删除。
- 账号切换：ego 同一 profile 下登录态按 origin 共享；跑学习者会登出该 profile 的 admin，跑管理端会登出学习者。已固定 `Profile 2`，不影响默认 `Profile 1`。
- 界面文案或结构变化会让场景失败；这是有意的，失败会指出具体模块与步骤，修场景即可。
- 本套件是用户可见链路的冒烟级覆盖，不替代字段级契约、组件单测与性能测试。
