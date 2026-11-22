---
artifact: test-plan
work_id: WORK-016
work: 移动端最佳适配
created: 2026-10-18
updated: 2026-10-18
---

# 移动端最佳适配 · 测试计划

## 策略

"最佳适配"在本项被定义成四条可测标准（需求文里写明），因此测试也只围绕这四条组织，不做主观走查替代：

| 标准 | 主判据 | 落在哪一层 |
| --- | --- | --- |
| 无横向溢出 | `documentElement.scrollWidth - clientWidth ≤ 1` | app/admin e2e（mobile project） |
| 主要动作可触达 | 命中区短边 ≥ 44 CSS px | app e2e + `Dialog` 单测 |
| 不被系统 UI 遮挡 | 全高容器用 `dvh`；固定元素带安全区 | 全仓检索 + 单测（类名断言） |
| 形态随宽度换而非等比缩小 | 窄屏下导航/弹窗/表格/助手各只有一种实现 | app/admin e2e + `Dialog` 单测 |

## 设备与 project

| project | 设备 | 跑什么 |
| --- | --- | --- |
| `chromium` | Desktop Chrome | 除 `*.mobile.spec.ts` 之外的既有全部用例（回归基线） |
| `mobile` | Pixel 7（412×915） | 只跑 `*.mobile.spec.ts` |

不为每一条桌面用例在手机下重跑：后端是有状态单进程（`workers: 1`），条数翻倍只让时间翻倍，换不到新信息。移动端要验的是"形态换了、没溢出、点得到"，那是专门的用例。

## 用例映射

| AC | 用例 |
| --- | --- |
| AC-001 无横向溢出 | `layout.mobile.spec.ts` 的 9 条路由冒烟 + 条目详情；管理端 6 页冒烟 |
| AC-002 移动导航可用 | 汉堡打开抽屉、三项导航与搜索可见、选中后关闭 |
| AC-003 条目页目录 | 折叠面板可见且可展开 |
| AC-004 交互块窄屏 | 既有 `source-reading.spec.ts` 的 360px 用例（桌面 project 内） + 命中区断言 |
| AC-005 助手底部抽屉 | `data-narrow=true`、贴底、高度 ≤85dvh、正文列宽不变、遮罩与 Esc 均可关 |
| AC-006 管理端导航 | 侧栏默认 `data-open=false` 且在屏幕外、汉堡拉开后到位、选中后自动收起 |
| AC-007 表格转卡片 | 条目页与分类页在窄屏显示卡片、表格隐藏、编辑与删除可见 |
| AC-008 安全区与动态视口 | 全仓检索无 `100vh`/`h-screen`；`Dialog` 单测断言 `max-sm:max-h-[92dvh]` |
| AC-009 触控目标 | 导航项与分类卡片命中区 ≥44px |
| AC-010 测试矩阵 | 两端 config 各有 `mobile` project；`--list` 能看到用例 |
| AC-011 桌面不回归 | `chromium` project 全量 |
| AC-012 桌面壳 | `main.cjs` 的 `minWidth/minHeight` 配置 |
| AC-013 共享层承担形态 | `dialog.test.tsx` 4 条：窄屏形态存在、桌面形态保留、不覆盖调用点限高、不用 vh |
| AC-014 PWA 可安装与可离线 | `pwa.spec.ts`（`PWA_BASE_URL` 下）：manifest 字段、SW 注册成功 |
| AC-015 PWA 不碰 API | `pwa.spec.ts`：Cache Storage 里 `/api` 条目为空 |

## 不覆盖 / 只人工

| 项 | 原因 |
| --- | --- |
| iOS Safari 真实安全区 | Playwright 的 Chromium 模拟不实现 `env(safe-area-inset-*)`；真机走查结论写进报告 |
| 软键盘遮挡 | 模拟器没有软键盘；靠 `visualViewport` + `dvh` 的实现保证 |
| "安装到主屏"完整流程 | iOS 无 `beforeinstallprompt`；Chrome 的安装 UI 不在 Playwright 控制面内 |
| 平板/横屏 | 明确不做专门设计（用户裁决） |

## 执行的命令

```bash
source backend/config/remote-db.local.env   # 提供 PAIDEIA_TEST_DB_URL / USER / PASSWORD

cd frontend
pnpm -r typecheck
pnpm -r test
pnpm --filter @paideia/app test:e2e
pnpm --filter @paideia/admin test:e2e

# PWA（生产预览）
pnpm --filter @paideia/app build
cd apps/app && npx vite preview --port 5199 --strictPort
cd ../.. && PWA_BASE_URL=http://127.0.0.1:5199 pnpm --filter @paideia/app exec playwright test --project=chromium pwa.spec
```
