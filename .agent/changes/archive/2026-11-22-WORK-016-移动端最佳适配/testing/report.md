---
artifact: test-report
work_id: WORK-016
work: 移动端最佳适配
status: passed
evidence: required
verified_at: 2026-10-18
accepted_by: xiaou61
accepted_at: 2026-10-29 13:18:03 +0800
---

# 移动端最佳适配验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 机器 | 本机 Windows / Git Bash，Node 24、pnpm 12 |
| 数据库 | 本机 MySQL（`backend/config/remote-db.local.env` 未注释的三行指向 `127.0.0.1:3306`） |
| 前置 | `source backend/config/remote-db.local.env` 导出 `PAIDEIA_TEST_DB_URL / USER / PASSWORD`；后端 jar 已构建 |
| 移动设备 | Playwright 内置 `Pixel 7`（412×915） |
| 生产预览 | `vite preview` @ 5199（仅 PWA 用例使用） |

## 结论：**通过**

22 条 REQ、15 条 AC 全部落地。学习者端 60 条 e2e（45 桌面 + 15 移动）、管理端 19 条（9 桌面 + 10 移动）、240 条单测全绿。

**三个只有跑起来才看见的真缺陷，已一并修掉：**

1. **PWA 预缓存会把整个 Shiki 语言包塞进首访**。默认 `globPatterns` 收所有 JS，实测 **326 个文件 / 11 MB**（`cpp` 768 KB、`emacs-lisp` 772 KB 各占一份）。手机用户首次打开就要为永远用不到的语言包付 11 MB。改成"只预缓存壳（CSS/HTML/图标）+ JS 走 CacheFirst 运行时缓存"后降到 **10 条 / 109 KB**。
2. **`Dialog` 的窄屏改写把桌面端的调用点覆盖了**。第一版用无前缀 `max-h-*` 再在 `sm` 复位，结果把管理端条目编辑器自己的 `max-h-[88vh] overflow-y-auto` 一起干掉——对话框长过视口、底部保存按钮点不到，管理端两条 e2e 直接超时。改成全部 `max-sm:` 前缀后桌面一行不变。现已用单测钉住（断言基础类里不出现无前缀 `max-h-[`）。
3. **嵌套标题抢了正文标题的 id**（WORK-014 遗留）。`MarkdownBody` 按 DOM 顺序给 `h1..h4` 回填 id，而交互块内部的 markdown 也产出 `h1..h4`；源码解读那篇有 7 个这样的标题。后果是目录第 5 项指向代码块解释里的小标题，点下去滚到错误位置。修法是只回填"最近带 `data-testid` 的祖先是容器自己"的标题。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 无横向溢出 | `pnpm --filter @paideia/app exec playwright test --project=mobile` | 0 | 通过 | 9 条路由冒烟 + 条目详情，均 `scrollWidth - clientWidth ≤ 1` |
| AC-001（管理端） | `pnpm --filter @paideia/admin exec playwright test --project=mobile` | 0 | 通过 | 6 个页面冒烟 |
| AC-002 移动导航 | 同上（app） | 0 | 通过 | 汉堡打开、三项导航 + 抽屉内搜索可见、点击后关闭并跳转 |
| AC-003 条目页目录 | 同上 | 0 | 通过 | `entry-toc-collapsed` 可见，点开后 `open` 属性存在 |
| AC-004 交互块窄屏 | `--project=chromium`（`source-reading.spec.ts:136`） | 0 | 通过 | 360px 下无溢出、代码与解释上下堆叠 |
| AC-005 助手底部抽屉 | `--project=mobile` | 0 | 通过 | `data-narrow=true`、贴底、高度 ≤85dvh、正文列宽不变、遮罩与 Esc 均可关闭 |
| AC-006 管理端导航 | `--project=mobile`（admin） | 0 | 通过 | 侧栏 `data-open=false` 且在屏幕外；拉开后 `x ≥ -1`；选中子项后自动收起 |
| AC-007 表格转卡片 | 同上 | 0 | 通过 | 条目页/分类页窄屏表格隐藏、卡片可见、编辑与删除可达 |
| AC-008 安全区与动态视口 | `rg -n "100vh" frontend`、`rg -n "h-screen" frontend` 与 `pnpm --filter @paideia/ui test` | 0 | 通过 | 全仓仅剩注释里提到 `100vh`；`Dialog` 单测断言 `max-sm:max-h-[92dvh]` 且不含 `[Nvh]` |
| AC-009 触控目标 | `--project=mobile` | 0 | 通过 | 导航项与分类卡片命中区短边 ≥44px |
| AC-010 测试矩阵 | `playwright test --list` | 0 | 通过 | app 15 条 mobile + admin 10 条 mobile |
| AC-011 桌面不回归 | `--project=chromium`（两端） | 0 | 通过 | app **45 passed**、admin **9 passed** |
| AC-012 桌面壳 | `rg -n "minWidth" frontend/apps/desktop/electron/main.cjs` 与同文件 `minHeight` | 0 | 通过 | 命中两行：`minWidth: 360,` 与 `minHeight: 480,` |
| AC-013 共享层承担形态 | `pnpm --filter @paideia/ui test` | 0 | 通过 | `dialog.test.tsx` 4 条：窄屏形态存在、桌面形态保留、不覆盖调用点限高、不用 vh |
| AC-014 PWA 可安装 | `PWA_BASE_URL=... playwright test pwa.spec` | 0 | 通过 | manifest `display=standalone`、3 个图标、SW 注册到 `active` |
| AC-015 PWA 不碰 API | 同上 | 0 | 通过 | Cache Storage 中 `/api` 前缀条目为 **0** |
| 单测 | `pnpm -r test` | 0 | 通过 | **240 passed**（core 22 + platform-desktop 4 + ui 148 + knowledge 28 + auth 13 + assistant 25） |
| 类型检查 | `pnpm -r typecheck` | 0 | 通过 | 11 个包全 Done |

## 体量记录（AC-014 要求）

| 项 | 改前 | 改后 | 说明 |
| --- | --- | --- | --- |
| 预缓存条目 | 无（本项新增） | **10 条 / 109 KB** | 第一版为 326 条 / 11.2 MB，见上文缺陷 1 |
| SW + runtime | 无 | `sw.js` 1.7 KB + `workbox-*.js` 21.9 KB | 构建期生成 |
| 图标 | 无 | 4 个 PNG，合计 13.7 KB | Pillow 生成，品牌色块 + "P" |
| 主 chunk | 736.41 kB（WORK-014 记录） | **732 kB** | 差异来自 `overflow-auto` 拆分与 tree-shaking，非本项优化目标 |
| CSS | 未记录 | **96 kB** | 安全区工具类新增约 0.6 kB |
| 依赖 | — | `vite-plugin-pwa` 及其 54 个间接包，**全部 devDependency** | 运行时不增加任何依赖 |

## 修掉的测试缺陷（非产品行为变化）

| 用例 | 症状 | 原因 | 处理 |
| --- | --- | --- | --- |
| `assistant.spec` 两条 | 连跑第二轮起必红：`conversation-message-user` 匹配到 4 条 | 面板会恢复服务端最近一段会话，而测试库的这段跨轮次累积消息；用例隐含假设"库里只有一条" | 用例内先「新开对话」再发 |
| `knowledge.spec` 目录一条 | `count<2` 却存在 `entry-toc`（自相矛盾） | 正文增量渲染，数标题时只挂了第一个；而目录是从 markdown 文本一次性算出的 | 先等 `markdown-loading` 消失再数 |
| `session.ts` 登录锚点 | 移动 project 下每条都以为"没登录" | 锚点用的是主导航，而它在 `<md` 收进了抽屉 | 改用页头账号菜单做锚点 |
| 移动溢出断言 | 偶发 59px 假红 | Chromium 移动模拟在导航瞬间有两三帧 `innerWidth`(471) > `clientWidth`(412)，`fixed` 元素按更宽视口定位 | 改 `expect.poll`；真实溢出会一直存在，仍会红 |

## 不覆盖与风险

| 项 | 状态 |
| --- | --- |
| iOS Safari 真机安全区 | **未做**。Chromium 模拟不实现 `env(safe-area-inset-*)`，`viewport-fit=cover` 与 `.safe-*` 的实际效果只能在真机确认。这是本项最大的未验项，如实记录 |
| 软键盘遮挡 | 未在真机验。实现上用了 `visualViewport.height` 与 `dvh`，但模拟器没有软键盘 |
| 安装到主屏的完整流程 | 未验。Chrome 的安装 UI 不在 Playwright 控制面内；iOS 无 `beforeinstallprompt` |
| 平板/横屏 | 按用户裁决不专门做 |
| 部署 | 未部署。PWA 需要 HTTPS 或 localhost 才注册 SW，线上是明文 HTTP + 端口——**需要确认部署形态后再验一次** |
| 管理端 PWA | 明确不做（REQ-028） |

## 部署后追加（2026-10-18 11:00）

首次部署后发现并修复两处只有上线上才暴露的问题，均已提交：

| 问题 | 现象 | 根因 | 修复 |
| --- | --- | --- | --- |
| PWA 入口被安全链拦成 401 | `GET /manifest.webmanifest` 与 `/sw.js` 返回 401，而同批的 `/` 与 `/admin/index.html` 是 200 | 浏览器取 manifest 与注册 SW 都发生在**登录之前**，是无令牌的普通 GET；它们落在 `anyRequest().authenticated()` 之后 | `SecurityConfiguration` 的静态白名单补 PWA 入口（`848d661`）；补 `PwaEntryPointsTest` 三条，并**反向验证**过：去掉白名单两条立刻红 |
| 管理端窄屏 e2e 在线上全红 | 3 条用例找不到任何元素 | 用例把 hash 路由写死成 `/#/…`；线上管理端在 `/admin/`，于是跳到了学习者端 | 改按 `ADMIN_ENTRY` 拼接（`1b6aaff`）；本地在站点根，所以此前一直没暴露 |

**线上复验**（`E2E_BASE_URL=http://<服务器>:8081`）：

| 对象 | 结果 |
| --- | --- |
| PWA 入口 | `/`、`/manifest.webmanifest`、`/sw.js`、`/registerSW.js`、`/pwa-192.png`、`/apple-touch-icon.png`、`/admin/index.html` 全部 **200** |
| 授权未被放宽 | `/api/v1/me` 仍是 **401** |
| 产物版本 | 线上 admin bundle 与本地 md5 完全一致；`index.html` 含 `viewport-fit=cover`；manifest 内容与本地一致 |
| 学习者端移动 e2e | **15 passed** |
| 管理端移动 e2e | **10 passed** |
| 管理端全量（本地） | **19 passed** |

未通过但**与本项无关**的 7 条学习者端桌面用例：它们断言的是种子数据（"余额为 0""没有任何消耗""条目正文有表格与代码块"），而线上 `e2e-learner` 已有 99982 积分、内容也不同。属于"这套用例本来就是为种子库写的"，不是部署回归。

## 需要用户注意的一条

PWA 在**明文 HTTP 的非 localhost 来源**下不会注册 Service Worker（浏览器安全上下文限制）。线上当前是 `http://<ip>:<port>`，因此：

- manifest 仍可被解析、图标仍可出现，但 **"安装"与离线壳在那个来源下不会生效**；
- 若要让 PWA 真正可用，需要 HTTPS 或一个受信任的来源（如 localhost、或配置反代 TLS）。

这不是代码缺陷，是平台约束。已记入本报告，未擅自改变部署形态。
