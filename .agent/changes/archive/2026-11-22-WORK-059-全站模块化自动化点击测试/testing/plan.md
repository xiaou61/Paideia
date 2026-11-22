---
artifact: test-plan
work_id: WORK-059
work: 全站模块化自动化点击测试（ego-browser）
status: ready
created: 2026-11-02
updated: 2026-11-02
---

# 全站模块化自动化点击测试计划

## 测试范围

- 18 个模块（学习者端 12 + 管理端 6）、57 条路由。
- 58 条点击用例：56 条只读 + 2 条写入（笔记、管理端分类）。
- 覆盖闸门：路由登记、重复归属、场景存在、最低用例数。
- 不覆盖：字段级接口契约、组件单测、性能、视觉像素比对（仍由既有 Playwright / JUnit / Vitest 负责）。

## 环境与前置条件

- 线上实例：学习者端 `/`、管理端 `/admin/`。
- ego-browser v2.2.0（Windows），固定 `Profile 2`；学习者 `e2e-learner`、管理员 `admin`。
- 凭据只在被忽略的 `.secrets/ego.local.json`。
- 每次运行生成独占 TaskSpace；报告写 `.agent/testing/runs/`，截图写忽略目录 `.agent/testing/.artifacts/`。

## 验收矩阵

| 验收 | 检查 | 命令 / 证据 |
| --- | --- | --- |
| AC-001 | 路由全覆盖；加一条未登记路由必须失败 | `node scripts/ego/check-map.mjs`；临时 router 加 `/ghost-route` 后退出码 1 |
| AC-002 | 单模块线上跑通并产出报告 | `node scripts/ego/cli.mjs --module knowledge --read-only` |
| AC-003 | 18 个模块、每模块至少 3 条用例 | `check-map` 输出 18 模块 / 57 用例 |
| AC-004 | 全量只读完整跑完 | `node scripts/ego/cli.mjs --all --read-only` |
| AC-005 | 写入数据带前缀并清理 | `--module notes --write`、`--module admin-knowledge --write`；页面搜索 `e2e-click-` 无残留 |
| AC-006 | 报告与截图不含主机/凭据 | `grep -RniE "https?://|[0-9]{1,3}(\.[0-9]{1,3}){3}" .agent/testing/runs/*ego-online.md` 无命中 |
| AC-007 | 失败报告含模块/用例/步骤/URL/截图 | 故意改错断言后跑 `--module home --read-only`，检查报告 |
| AC-008 | 同一模块连续跑两次不因残留失败 | 两个写入模块各跑两次 |
| AC-009 | 只改测试与文档，不改产品逻辑 | `git show --stat a7345db` |
| AC-010 | 每次运行追加台账 | `ls .agent/testing/runs/*ego-online.md` |

## 自动化检查

1. `node --test scripts/ego/check-map.test.mjs`：4 条自测（通过 / 漏登记 / 重复归属 / 用例不足）。
2. `node scripts/ego/check-map.mjs`：真实两个 router 与模块清单对账。
3. `node scripts/ego/cli.mjs --all --read-only`：18 模块全量只读。
4. `node scripts/ego/cli.mjs --all --write`：含两条写入用例的全量。
5. 反向验证：临时 router 多一条 `/ghost-route`，`check-map` 必须退出 1；临时改错 `home` 断言，runner 必须写失败报告。

## 人工检查

- 跑完后在笔记页与管理端分类页搜索 `e2e-click-`，确认无残留。
- 提交前扫描报告与 diff，确认没有主机名、IP、端口、账号、令牌。
- 看一次失败截图，确认能定位到具体页面与步骤。

## 回归范围

本次只新增 `scripts/ego/` 与 `.agent/` 文档，不改产品代码，因此不重跑后端 667 条与 Playwright 61 条。
若后续为了稳定选择器而给产品加 `data-testid`，需要按改动页面补跑对应 Playwright 文件。

## 已知缺口

- 不采集浏览器 console（ego 默认隐藏，开启会暴露自动化），用可见错误文案与白屏断言替代。
- 只读模式仍会因打开条目而记一次浏览，不是零副作用。
- AI 相关用例只断言「成功 / 余额不足 / 未配置」三选一的确定状态，不判断回答质量。
- 管理端破坏性操作（删用户、调积分、关事件）不在场景内。
- 场景是用户可见链路的冒烟级覆盖，不替代字段级契约与组件单测。
