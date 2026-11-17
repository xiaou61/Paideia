# Tauri v2 桌面壳 Spike 报告

> 目的：验证用 Tauri v2 替换 Electron 壳的可行性、复用成本与真实收益。
> 代码在 worktree `.worktrees/tauri-spike`（分支 `tauri-spike`），**业务代码零改动**。
> 日期：2026-11-06。

## 一、结论（一句话）

**可行，且换壳几乎不动业务代码；但真正省的是「磁盘」，不是「内存」。**

| 维度 | Electron（现状） | Tauri v2（本 spike 实测） | 结论 |
|---|---|---|---|
| 可执行文件 | — | **3.18 MB**（release）/ 13.1 MB（debug） | 极大缩小 |
| 解包/安装 | 368.4 MB 解包 / **106.4 MB 安装包** | exe 3.18 MB / **4.95 MB 安装包（NSIS 实测）** | 约 21 倍下降 |
| 运行内存（进程树） | 同量级（Chromium） | **301.3 MB**（WebView2 6 进程 279.7 + 主进程 21.6） | **基本持平，未变好** |
| 启动到窗口 | — | **~147 ms** | 较快 |
| React 页面 / `@paideia/platform-desktop` / 后端 | — | **零改动** | 复用成本极低 |
| `window.paideiaDesktop` 桥 | IPC | Tauri 命令 + init script | 形状完全一致 |

> 关键认知：Tauri 在 Windows 上用系统 **WebView2（本质仍是 Chromium/Edge）**，所以**页面渲染性能与内存跟 Electron 差不多**；它的收益是「不打包 Chromium」→ **磁盘占用和冷启动**，不是「更省内存 / 页面更快」。

## 二、环境

- Rust：`rustup` stable-x86_64-pc-windows-msvc，cargo/rustc **1.95.0**
- 链接器：VS Build Tools **14.44**（`F:\VSBuildTools`，经 vcvars64）
- WebView2 Runtime：128.x（系统已装）
- Tauri：tauri 2.12.5 / tauri-plugin-deep-link 2.6.3 / tauri-plugin-single-instance 2.5.5
- 依赖镜像：rsproxy 稀疏索引（国内加速），独立 `CARGO_HOME` 隔离

## 三、复刻的 `window.paideiaDesktop` 桥（与 Electron 版对齐）

- `rendererOrigin: 'http://127.0.0.1:5310'` —— 自建静态服务，**origin 与 Electron 完全一致**，后端 CORS / OAuth state 零改动
- `cacheSnapshot` —— 启动读 `cache.json`，经 **initialization_script 在页面脚本前同步注入**
- `cache.get/set/remove` —— Tauri `#[command]` + 原子落盘 `cache.json`
- `onOauthHandoff` —— 单实例 + argv 解析 `paideia://oauth?handoff=`，经事件下发（只订阅不发送）

## 四、实测验证结果

| 验证项 | 结果 |
|---|---|
| 加载真实 SPA 的 origin | `http://127.0.0.1:5310` ✅ |
| `typeof window.paideiaDesktop` | `object` ✅ |
| 缓存读写往返 | `set('spike_key','v123')` → `get()='v123'` ✅ |
| 落盘 | `%APPDATA%\io.github.xiaou61.paideia.tauri\cache.json` = `{"spike_key":"v123"}` ✅ |
| 单实例 | 第二个实例自动退出，仅剩 1 个进程 ✅ |
| `paideia://` 解析（rust 单测） | 6/6 通过（合法/错误host/非法字符/缺失/超长/非协议）✅ |
| 内存（release，进程树） | 301.3 MB（WebView2 6 进程 279.7 + 主进程 21.6） |
| 启动到窗口 | ~147 ms |
| NSIS 安装包体积 | **4.95 MiB**（`PaideiaTauri_0.1.0_x64-setup.exe`）✅ |
| 系统注册 `paideia://` | 注册表 `HKCU\Software\Classes\paideia\shell\open\command` 已写入、指向 exe `"%1"` ✅ |
| warm 深链（已运行转发） | 第 2 实例自动退出，第 1 实例收到 handoff `AbC123` ✅ |
| cold 深链（冷启动注入重放） | `initialHandoff="Cold999"` 注入并由 `onOauthHandoff` 重放 ✅ |

## 五、改动范围（证明「少改代码」）

**完全没改**：所有 React 页面、`@paideia/core`、`@paideia/platform-desktop`、后端。

**新增（均在 `frontend/apps/desktop-tauri/`）**：
- `src-tauri/src/main.rs`（~200 行：命令 + init script + 单实例 + 深链）
- `src-tauri/Cargo.toml`、`tauri.conf.json`、`build.rs`、`capabilities/default.json`
- `serve-renderer.mjs`（开发期 5310 静态服务，复刻 main.cjs 逻辑）
- `renderer/`（= `frontend/apps/app/dist` 直接复制）

**将来切换时**只换 `frontend/apps/desktop` 的壳，SPA / 后端 / APP 构建不动。

## 六、踩过的坑（对正式迁移有用）

1. **共享 `~/.cargo` 包缓存锁被别的会话抢占**：本机另一会话在 `cargo check -p ide-core`，锁住全局 package-cache，构建一直「Blocking waiting for file lock」。→ 给 spike 用**独立 `CARGO_HOME`**（`.cargo-home/`）隔离。
2. **16 核并行编译 OOM**：默认满并发编 Tauri 几百个 crate，16GB 内存被我吃到只剩 6GB 时进程被杀（`CARGO_EXIT=-1`）。→ `cargo build -j 4` 解决。
3. **`.bat` 必须纯 ASCII**：cmd 默认 GBK 代码页，UTF-8 中文注释被读坏导致脚本执行失败。
4. **rustup 初次安装可能被工具判 idle 中断**（toolchain bin 为空），需补 `rustup toolchain install`。
5. **WebView2 = Chromium**：内存不会比 Electron 低，别把它当省内存方案。

## 七、正式迁移建议

1. ~~用 `npx @tauri-apps/cli build` 产 NSIS 安装包，补测安装包体积与深链注册 E2E~~ —— 已完成：安装包 4.95MiB、深链注册/warm/cold 均验证通过。剩余：正式安装一次（NSIS 写正式路径）后走真实 OS 唤起回归。
2. 若要保留 5310 origin：spike 已证明「Rust 侧自建静态服务」可行；否则改用 `http://tauri.localhost` 并把新 origin 加进后端 `paideia.web.cors.allowed-origins`。
3. capability 权限最小化 + CSP 收紧 + 接入 `tauri-plugin-updater`（自动更新）。
4. **若目标是「页面更快/更省内存」——换壳解决不了**，应优化前端本身（体积拆分、长列表虚拟化、首屏）。
5. worktree 用完按规范清理：`git worktree remove .worktrees/tauri-spike` + `git branch -d tauri-spike`（含 `.cargo-home`、`target/` 会一并删除）。

## 附：构建命令（在 worktree 内）

```bat
:: 需先装 rustup + VS Build Tools
frontend\apps\desktop-tauri\build-release-iso.bat   :: 产出 src-tauri\target\release\paideia-desktop-tauri.exe
```

## 正式迁移结果（2026-11-09）

分支 `tauri-spike`，两笔提交（`feat(desktop)` + `docs`）。**业务代码零改动**：React 页面、`@paideia/platform-desktop`、后端都没动。

| 项 | Electron（旧） | Tauri 2（新，实测） |
|---|---|---|
| 安装包 | 106.4 MB | **5.31 MB** |
| 安装后占用 | 368 MB（解包） | **8.4 MB**（安装目录） |
| 可执行文件 | — | 3.2 MB |
| 运行内存（进程树） | 同量级 | ~300 MB（WebView2 仍是 Chromium） |
| 启动到窗口 | — | ~147 ms |

迁移中修掉的两个真问题（都写进了代码注释）：

1. **`tauri::is_dev()` 不可靠**：直接用 `cargo build --release` 打出的包上它仍可能为真，据此跳过本地静态服务就是白屏。改为无条件启动服务，端口被占用时探测占用者是不是自家页面。
2. **Windows 会规范化协议地址**：ShellExecute 唤起时变成 `paideia://oauth/?handoff=…`（host 后多一个斜杠），手写字符串解析会漏。改用 `url` crate 按 URL 语义解析。

验收方式也换了：`pnpm --filter @paideia/desktop smoke` 走 WebView2 调试端口断言（来源 / 桥 / 缓存 / 登录门），不再依赖 Playwright 的 Electron 支持。安装版另验了「真实 ShellExecute 唤起」的 warm 与 cold 两条路径。

留待合并前处理：分支基于 `90fefadc`，落后 master；合并/变基后 `gen-arch-diagram --check` 的 05/06 与 README 计数漂移会自动消失（那是分支落后造成的，不是本次改动引入）。
