# 桌面端换壳评估：Electron → Wails / Tauri

> 评估稿，非承诺实施。基于当前仓库实测数据，结论以「少改业务代码 + 降低体积/内存」为目标。
> 生成日期：2026-11-06。

## 0. 一句话结论

现有桌面壳把业务代码与 Electron **完全解耦**，换壳只需重写约 360 行 CJS 壳 + 打包配置，React 页面 / 后端 / `@paideia/core` / `@paideia/platform-desktop` 一行不用改。
- **只 Windows、想最快落地** → **Wails**（本机已装 Go 1.26.2，零新工具链）。
- **要长期维护、生态省心、深链少写代码** → **Tauri v2**（一次性装 Rust，插件齐全）。
- 两者都**不会让页面渲染变快**（Windows 上底层仍是 WebView2/Chromium），省的是磁盘与内存。

## 1. 现状基线（实测）

| 项 | 值 |
|---|---|
| Electron / electron-builder | 44.7.0 / 26.15.3 |
| 解包体积 `release/win-unpacked` | **368.4 MB** |
| 安装包 `Paideia Setup 0.0.1.exe` | **106.4 MB** |
| 壳代码 | 主进程 255 行 + preload 68 行 + oauth 协议解析 40 行 |
| 打包配置 | `package.json` 内置 electron-builder 配置 + NSIS + `paideia://` 协议 |

架构链路：

```
React SPA (Vite)  ──http──▶  自建静态服务 http://127.0.0.1:5310  ──▶  Chromium 内核(BrowserWindow)
        │                                                                      ▲
        │  只依赖 Platform 端口(kind + cache)                                   │
        └── window.paideiaDesktop 桥（preload 注入） ──IPC──▶ 主进程 cache.json / oauth / shell
```

关键契约（换壳时**必须保留**的对外形状，业务代码才知道怎么调）：

- `window.paideiaDesktop = {`
  - `cache.get/set/remove(key)` → `Promise`（异步，经 IPC）
  - `cacheSnapshot: Record<string,string>` → **同步镜像**，启动时由主进程读盘注入（首屏主题脚本要用，见下）
  - `rendererOrigin: string`
  - `onOauthHandoff(cb) => unsubscribe`
- `Platform { kind, cache }`（`@paideia/core`）
- 后端 CORS 必须显式放行 `http://127.0.0.1:5310`（`paideia.web.cors.allowed-origins`，生产默认空，部署时注入）。

代码位置：
- [main.cjs](../frontend/apps/desktop/electron/main.cjs)（开窗 / 静态服务 / 单实例 / 深链 / cache / 外部链接）
- [preload.cjs](../frontend/apps/desktop/electron/preload.cjs)（暴露 `window.paideiaDesktop`）
- [oauth-protocol.cjs](../frontend/apps/desktop/electron/oauth-protocol.cjs)（`paideia://oauth?handoff=` 解析）
- [platform-desktop/index.ts](../frontend/packages/platform-desktop/src/index.ts)
- [core/platform.ts](../frontend/packages/core/src/platform.ts)

## 2. 两个候选的工作方式

### Tauri v2（Rust）
- Rust 主进程 + 系统 WebView2（Windows）。安装包约 **3–10 MB**，内存显著低于 Electron。
- 能力有官方插件，和现有壳 1:1 对应：

| 现有能力 | Tauri 方案 |
|---|---|
| 单实例锁 | `tauri-plugin-single-instance` |
| `paideia://` 深链 | `tauri-plugin-deep-link`（含冷启动 argv 与已运行转发） |
| `cache.json` + IPC | `tauri-plugin-store`（可直接替代自定义缓存） |
| 外部链接开系统浏览器 | `tauri-plugin-opener` |
| 本地静态服务 | 内置 asset 协议，或自建 localhost server |

### Wails v2（Go）
- Go 后端 + 系统 WebView2。exe 约 **10–15 MB**，NSIS 安装包约 **4–8 MB**。
- Go 结构体方法自动生成前端绑定 `window.go.main.App.*`；事件用 `runtime.EventsOn/EventsEmit`。
- 深链**没有官方 turnkey 插件**，需自己接 `os.Args` + Windows 注册表（`oauth-protocol.cjs` 的解析逻辑可直接翻译）。
- 生产资产走自定义 scheme（Windows 上近似 `http://wails.localhost`），同样会改变 origin。

## 3. 多维度对比

| 维度 | Tauri v2 | Wails v2 | 说明 |
|---|---|---|---|
| 安装包 | ~3–10 MB | ~4–8 MB | 都不打包 Chromium |
| 常驻内存 | 低 | 低 | 两者相当，取决于 WebView2 进程 |
| 启动速度 | 快 | 快 | 略优于 Electron |
| 新工具链 | Rust + MSVC | **无（Go 已装）** | 本机 Go 1.26.2 可直接用 |
| 深链 | 官方插件，省事 | 自己接，稍多代码 | 你已实现深链，Tauri 迁移更省 |
| 单实例 | 官方插件 | 内置 SingleInstance | 都不难 |
| 本地存储 | 官方 store 插件 | 自写文件/绑定 | Tauri 更省 |
| 事件/回调 | `emit/listen` | `EventsOn/EventsEmit` | 都能承载 oauth handoff |
| 跨平台 WebView 一致性 | Win:WebView2 / mac:WKWebView / Linux:WebKitGTK | 同左 | 只发 Windows 时风险最低 |
| 社区/生态 | 更大、更新 | 较活跃但小 | 长期维护看 Tauri |
| 迁移量（估） | ~2–4 人天（含环境） | ~1.5–3 人天 | 见第 4 节拆解 |
| 安全模型 | capability/权限白名单 | 显式绑定 + 选项 | 都需保持「不暴露 Node/FS」 |

> 体积/工时为参考区间，实际以 spike 实测为准。

## 4. 迁移工作量拆解（两者通用）

**不改（重点）：**
- 所有 React 页面与业务逻辑
- `@paideia/core` 的 `Platform` 端口
- `@paideia/platform-desktop` 包（外面套 shim 造出 `window.paideiaDesktop` 即可）
- 后端（前提：新壳仍自建 `127.0.0.1:5310` 静态服务，见坑 1）

**要改：**
1. 新建壳工程（`src-tauri/` 或 Wails 项目）
2. 重写 main 的五件事：开窗 / 静态服务(5310) / 单实例 / `paideia://` 深链 / cache 读写
3. 用 WebView 的 **initialization script** 在页面脚本前注入 `window.paideiaDesktop` shim（`cacheSnapshot` 同步注入是关键）
4. 打包器替换（electron-builder → tauri/wails），保留 NSIS、协议注册、图标

**接口翻译参考（preload → shim）：**

```js
// 目标：让 platform-desktop 包完全不感知换壳
window.paideiaDesktop = {
  rendererOrigin: 'http://127.0.0.1:5310',
  cacheSnapshot: JSON.parse('__INJECTED_SNAPSHOT__'),
  cache: {
    get: (k) => invoke('cache_get', { k }),
    set: (k, v) => invoke('cache_set', { k, v }),
    remove: (k) => invoke('cache_remove', { k }),
  },
  onOauthHandoff: (cb) => {
    const un = listen('oauth:handoff', (e) => cb(e.payload))
    return () => un.then((f) => f())
  },
}
```

## 5. 关键坑与对策

1. **来源 / CORS（最关键）**
   `main.cjs` 特意用 `http://127.0.0.1:5310` 真 origin，让后端按来源放行（而不是 `null`）。两条路：
   - **A（推荐，后端零改）**：新壳照样在进程内起 `127.0.0.1:5310` 静态服务，origin 不变，后端 `allowed-origins` 不用动，`rendererOrigin` 语义也不变。
   - **B**：用 Tauri/Wails 内置 asset 协议 → origin 变成 `http://tauri.localhost` / wails scheme，必须把新 origin 加进 `paideia.web.cors.allowed-origins`（[application.yml](../backend/paideia-app/src/main/resources/application.yml)）。
2. **同步 `cacheSnapshot`**：`Platform.cache.get` 是同步语义，首屏主题脚本执行时就要拿到上次选择。必须用 initialization script 在页面脚本前注入快照，不能只靠异步 IPC。
3. **深链两路径**：冷启动（参数在 argv）+ 已运行（second-instance 转发），两条都要接。Tauri deep-link 插件已覆盖；Wails 自己写，直接翻译 `oauth-protocol.cjs` 的校验逻辑（保持「只订阅不发送」）。
4. **单实例**：避免第二个进程另开窗口；协议唤起要转交给已运行实例。
5. **WebView2 运行时**：Win10/11 一般自带；老机器安装器需带 bootstrapper 或检测引导。
6. **安全**：保持与 `contextIsolation:true / nodeIntegration:false / sandbox:true` 等价的隔离——不向页面暴露 Node、文件系统；Tauri 用 capability 白名单，Wails 只绑必要方法。
7. **性能真相**：Windows 上 WebView2 = Chromium(Edge)，**页面渲染性能与 Electron 基本相同**。换壳降的是磁盘占用与内存，不是帧率。前端卡顿要单独优化。
8. **协议注册冲突**：过渡期新旧两壳若同时装，会争抢 `paideia://`，需明确卸载旧壳或换 scheme。

## 6. 推荐决策树

```
只发 Windows？ ──是──▶ 不想装 Rust？ ──是──▶ Wails（Go 已装，最快落地）
        │                              └─否──▶ Tauri（深链/store 插件更省代码，长期更优）
        └─否（要 mac/Linux 一致）──────────────▶ Tauri（跨平台插件与文档更成熟）
```

补充：
- 若只是想尽快看到「体积降下来」，**Wails 半天出原型**最容易验证。
- 若把桌面端当长期产品维护（自动更新、多平台、生态），**Tauri** 回报更高。
- 两者都可与 Electron **并行共存**（独立 appId/可执行名），跑通再切，回滚成本低。

## 7. 验证清单（spike 或正式迁移后逐条过）

- [ ] 冷启动到首屏；主题/语言跟随上次选择（验证 `cacheSnapshot` 同步注入）
- [ ] 缓存写入后重启仍在；写盘失败只告警不崩
- [ ] 深链登录：①应用未开时点链接 ②应用已开时点链接，均能完成握手
- [ ] 外部链接走系统浏览器，拒绝非 http(s)
- [ ] 单实例：重复启动不新开窗口，聚焦已有窗口
- [ ] 安装/卸载正常；协议注册生效；端口占用时给出可视错误而非静默退出
- [ ] 实测安装包体积、冷启动耗时、空闲内存，与 Electron 基线对比
