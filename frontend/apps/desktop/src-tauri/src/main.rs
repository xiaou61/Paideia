// Paideia 桌面端外壳（Tauri v2）。
//
// 页面通过 `window.paideiaDesktop` 使用原生能力（见 `@paideia/platform-desktop`）。
//
// 三个刻意的设计决定：
//
// 1. **渲染来源保持 `http://127.0.0.1:5310`**。Tauri 的 asset 协议在 Windows 上是
//    `http://tauri.localhost`，换来源就得同步改后端 CORS 白名单与服务器部署配置；
//    这里改用「进程内小静态服务 + 内嵌资产」把来源钉死在原值，后端与部署零改动。
// 2. **首次启动时从 Electron 的 userData 迁移缓存**。userData 目录会随壳换代而变，
//    不迁移等于让老用户丢主题/语言选择。只迁移一次，之后各写各的。
// 3. **外部链接仍然交给系统浏览器**，且只放行 http/https——页面里可能出现第三方内容，
//    不能让它拿到"拉起本地任意协议"的能力。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};

/// 必须与后端 CORS 白名单、OAuth 文档里写的来源一致。改这里要同时改后端。
const RENDERER_ORIGIN: &str = "http://127.0.0.1:5310";
const RENDERER_PORT: u16 = 5310;
const DEV_RENDERER: bool = use_dev_renderer(
    cfg!(feature = "dev-renderer"),
    cfg!(debug_assertions),
    !tauri::is_dev(),
);

const fn use_dev_renderer(feature_enabled: bool, debug_build: bool, bundled_build: bool) -> bool {
    feature_enabled && debug_build && !bundled_build
}

fn renderer_navigation_allowed(url: &url::Url) -> bool {
    url.scheme() == "http" && url.origin().ascii_serialization() == RENDERER_ORIGIN
}

/// 进程内缓存镜像。读走内存（首帧同步语义），写时落盘。
#[derive(Default)]
struct CacheState(Mutex<HashMap<String, String>>);

fn cache_file(app: &AppHandle) -> PathBuf {
    let dir = app
        .path()
        .app_config_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    let _ = fs::create_dir_all(&dir);
    dir.join("cache.json")
}

/// Electron 版的 userData：`%APPDATA%\@paideia\desktop\cache.json`（包名派生的目录）。
fn legacy_cache_file() -> Option<PathBuf> {
    let appdata = std::env::var_os("APPDATA")?;
    Some(
        PathBuf::from(appdata)
            .join("@paideia")
            .join("desktop")
            .join("cache.json"),
    )
}

fn read_cache_file(file: &PathBuf) -> Option<HashMap<String, String>> {
    let text = fs::read_to_string(file).ok()?;
    serde_json::from_str(&text).ok()
}

fn load_cache(app: &AppHandle) -> HashMap<String, String> {
    let file = cache_file(app);
    if let Some(data) = read_cache_file(&file) {
        return data;
    }
    // 只在新壳还没有自己的缓存文件时迁移一次；文件已存在就说明用户已经在新壳里做过选择，
    // 再用旧值覆盖会把那次选择吞掉。
    if let Some(legacy) = legacy_cache_file() {
        if let Some(data) = read_cache_file(&legacy) {
            persist(app, &data);
            return data;
        }
    }
    HashMap::new()
}

fn renderer_cache(
    dev_renderer: bool,
    load: impl FnOnce() -> HashMap<String, String>,
) -> HashMap<String, String> {
    if dev_renderer {
        HashMap::new()
    } else {
        load()
    }
}

fn persist(app: &AppHandle, data: &HashMap<String, String>) {
    if DEV_RENDERER {
        return;
    }
    let Ok(text) = serde_json::to_string(data) else {
        return;
    };
    let file = cache_file(app);
    let temp = file.with_extension("json.tmp");
    // 先写临时文件再原子替换：直接覆写时进程中途退出会截断 JSON，
    // 而截断的 JSON 解析失败又会被当成空缓存——用户的选择就这么整份丢了。
    if fs::write(&temp, text).is_ok() && fs::rename(&temp, &file).is_err() {
        let _ = fs::remove_file(&temp);
    }
}

#[tauri::command]
fn cache_get(state: State<CacheState>, key: String) -> Option<String> {
    state.0.lock().ok()?.get(&key).cloned()
}

#[tauri::command]
fn cache_set(app: AppHandle, state: State<CacheState>, key: String, value: String) {
    if let Ok(mut guard) = state.0.lock() {
        guard.insert(key, value);
        persist(&app, &guard);
    }
}

#[tauri::command]
fn cache_remove(app: AppHandle, state: State<CacheState>, key: String) {
    if let Ok(mut guard) = state.0.lock() {
        guard.remove(&key);
        persist(&app, &guard);
    }
}

/// 只放行 http/https。
///
/// <p>其余协议（file:、ms-msdt:、smb: 等）在部分平台可以拉起本地程序，而 url 来自页面，
/// 页面里可能混进第三方内容——等于把"执行本地程序"的能力交给了页面。
#[tauri::command]
fn open_external(url: String) -> Result<(), String> {
    let scheme = url.split(':').next().unwrap_or("").to_ascii_lowercase();
    if scheme != "http" && scheme != "https" {
        return Err(format!("拒绝打开非 http(s) 链接：{scheme}"));
    }
    open::that(url).map_err(|error| format!("打开外部链接失败：{error}"))
}

/// 复用 Electron 版 `oauth-protocol.cjs` 的校验：只认
/// `paideia://oauth?handoff=<url-safe base64>`。
///
/// <p>它挡的不是"伪造登录"——握手码一次性、60 秒有效，伪造也换不到令牌；
/// 挡的是"页面能把任意字符串送进主进程"这条通道。
fn handoff_from_url(url: &str) -> Option<String> {
    // 用 `url` 解析而不是手写字符串切分：Windows 经 ShellExecute 唤起时会把地址
    // 规范成 `paideia://oauth/?handoff=...`（host 后多一个斜杠）。手写解析只认 `oauth`，
    // 于是"双击协议链接没反应"，而同一串参数从命令行直接启动却又正常——这个坑实测踩过。
    let parsed = url::Url::parse(url).ok()?;
    if parsed.scheme() != "paideia" || parsed.host_str() != Some("oauth") {
        return None;
    }
    let handoff = parsed
        .query_pairs()
        .find(|(key, _)| key == "handoff")
        .map(|(_, value)| value.into_owned())?;
    if handoff.is_empty()
        || handoff.len() > 128
        || !handoff
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return None;
    }
    Some(handoff)
}

fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
            if let Ok(value) = u8::from_str_radix(hex, 16) {
                out.push(value);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(windows)]
fn renderer_listener(port: u16) -> std::io::Result<std::net::TcpListener> {
    use socket2::{Domain, Protocol, Socket, Type};
    use std::os::windows::io::AsRawSocket;
    use windows_sys::Win32::Networking::WinSock::{
        setsockopt, WSAGetLastError, SOCKET_ERROR, SOL_SOCKET, SO_EXCLUSIVEADDRUSE,
    };

    let address = std::net::SocketAddr::from(([127, 0, 0, 1], port));
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    let enabled: i32 = 1;
    // SAFETY: socket remains open and enabled is a DWORD-sized buffer valid for the call.
    let result = unsafe {
        setsockopt(
            socket.as_raw_socket() as _,
            SOL_SOCKET,
            SO_EXCLUSIVEADDRUSE,
            (&enabled as *const i32).cast(),
            std::mem::size_of_val(&enabled) as i32,
        )
    };
    if result == SOCKET_ERROR {
        return Err(std::io::Error::from_raw_os_error(unsafe {
            WSAGetLastError()
        }));
    }
    socket.bind(&address.into())?;
    socket.listen(128)?;
    Ok(socket.into())
}

#[cfg(not(windows))]
fn renderer_listener(port: u16) -> std::io::Result<std::net::TcpListener> {
    std::net::TcpListener::bind(([127, 0, 0, 1], port))
}

fn bind_renderer(port: u16) -> Result<tiny_http::Server, String> {
    let listener = renderer_listener(port)
        .map_err(|error| format!("无法独占 127.0.0.1:{port} 启动渲染服务：{error}"))?;
    tiny_http::Server::from_listener(listener, None)
        .map_err(|error| format!("无法在 127.0.0.1:{port} 启动渲染服务：{error}"))
}

/// 正式模式必须持有回环监听。任何已运行的 HTTP 服务都不能作为内嵌资产的替代。
fn serve_renderer(app: &AppHandle) -> Result<(), String> {
    // Tauri CLI 也可直接启用 tauri/custom-protocol，不经过当前 crate 的 feature 别名。
    if tauri::is_dev() || app.asset_resolver().get("index.html".into()).is_none() {
        return Err("缺少内嵌渲染资源，请通过项目的桌面构建命令生成应用。".into());
    }
    let server = bind_renderer(RENDERER_PORT)?;
    let handle = app.clone();
    std::thread::spawn(move || {
        for request in server.incoming_requests() {
            let raw_path = request.url().split(['?', '#']).next().unwrap_or("/");
            let decoded = percent_decode(raw_path);
            let lookup = if decoded.is_empty() || decoded == "/" {
                "/index.html".to_string()
            } else {
                decoded
            };
            let resolver = handle.asset_resolver();
            let asset = resolver
                .get(lookup.clone())
                // Hash 路由下正常不会走到这里；兜底回落到首页，避免刷新出现 404 白屏。
                .or_else(|| resolver.get("/index.html".to_string()));

            match asset {
                Some(asset) => {
                    let header = tiny_http::Header::from_bytes(
                        &b"Content-Type"[..],
                        asset.mime_type.as_bytes(),
                    )
                    .expect("静态服务的 content-type 头必须可构造");
                    let response =
                        tiny_http::Response::from_data(asset.bytes.to_vec()).with_header(header);
                    let _ = request.respond(response);
                }
                None => {
                    let _ = request.respond(tiny_http::Response::empty(404));
                }
            }
        }
    });
    Ok(())
}

fn bridge_script(snapshot: &HashMap<String, String>, initial_handoff: Option<&str>) -> String {
    let snapshot_json = serde_json::to_string(snapshot).unwrap_or_else(|_| "{}".to_string());
    let initial_json =
        serde_json::to_string(&initial_handoff).unwrap_or_else(|_| "null".to_string());
    // 这是 Electron preload 那份桥的等价物：形状必须完全一致，`@paideia/platform-desktop`
    // 是照着它写的。注意不要在脚本里提前捕获 `__TAURI__`——它由 Tauri 自己的脚本注入，
    // 两者的执行顺序不是约定；所有调用都延迟到方法被调用时才取。
    format!(
        r#"(function () {{
  if (window.top !== window.self || window.location.protocol !== 'http:' || window.location.origin !== '{origin}') return;
  const core = () => window.__TAURI__.core;
  window.paideiaDesktop = {{
    rendererOrigin: '{origin}',
    developmentRenderer: {development},
    cacheSnapshot: {snapshot},
    initialHandoff: {initial},
    openExternal: (url) => core().invoke('open_external', {{ url: String(url) }}),
    cache: {{
      get: (key) => core().invoke('cache_get', {{ key: String(key) }}),
      set: (key, value) => core().invoke('cache_set', {{ key: String(key), value: String(value) }}),
      remove: (key) => core().invoke('cache_remove', {{ key: String(key) }}),
    }},
    onOauthHandoff: {development} ? undefined : (callback) => {{
      if (typeof callback !== 'function') return () => {{}};
      const listening = window.__TAURI__.event.listen('paideia://oauth-handoff', (event) => {{
        if (typeof event.payload === 'string' && event.payload !== '') callback(event.payload);
      }});
      // 冷启动时握手码在页面挂监听之前就到了，这里补发一次
      const pending = window.paideiaDesktop.initialHandoff;
      if (typeof pending === 'string' && pending !== '') {{
        setTimeout(() => callback(pending), 0);
      }}
      return () => {{ listening.then((unlisten) => unlisten()); }};
    }},
  }};

  // 外链交给系统浏览器：<a target="_blank"> 在 webview 里默认会新开一个应用内窗口，
  // 而 Electron 版是 shell.openExternal。两者行为要一致，且只放行 http(s)。
  document.addEventListener('click', (event) => {{
    const anchor = event.target && event.target.closest ? event.target.closest('a[target="_blank"]') : null;
    if (!anchor || !anchor.href) return;
    event.preventDefault();
    core().invoke('open_external', {{ url: String(anchor.href) }}).catch(() => {{}});
  }}, true);
  window.open = (url) => {{
    if (url) core().invoke('open_external', {{ url: String(url) }}).catch(() => {{}});
    return null;
  }};
}})();"#,
        origin = RENDERER_ORIGIN,
        development = DEV_RENDERER,
        snapshot = snapshot_json,
        initial = initial_json
    )
}

fn main() {
    let builder = tauri::Builder::default()
        // 单实例必须最先注册：第二个进程（协议唤起）不该再开一个窗口
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            // 排查用开关（默认关闭）：协议唤起"没反应"时，先看第二实例到底带了什么参数。
            if std::env::var_os("PAIDEIA_DESKTOP_DEBUG").is_some() {
                if let Ok(dir) = app.path().app_config_dir() {
                    let _ = std::fs::create_dir_all(&dir);
                    use std::io::Write;
                    if let Ok(mut file) = std::fs::OpenOptions::new()
                        .create(true)
                        .append(true)
                        .open(dir.join("single-instance.log"))
                    {
                        let _ = writeln!(file, "{argv:?}");
                    }
                }
            }
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
            if !DEV_RENDERER {
                for argument in argv.iter() {
                    if let Some(handoff) = handoff_from_url(argument) {
                        let _ = app.emit("paideia://oauth-handoff", handoff);
                    }
                }
            }
        }));
    let builder = if DEV_RENDERER {
        builder
    } else {
        builder.plugin(tauri_plugin_deep_link::init())
    };
    builder
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            cache_get,
            cache_set,
            cache_remove,
            open_external
        ])
        .setup(|app| {
            let handle = app.handle().clone();

            if !DEV_RENDERER {
                if let Err(message) = serve_renderer(&handle) {
                    use tauri_plugin_dialog::DialogExt;
                    handle
                        .dialog()
                        .message(message)
                        .title("Paideia 启动失败")
                        .show(|_| std::process::exit(1));
                    // setup 跑在事件循环线程；绝不能等待对话框，也不能继续初始化渲染器。
                    return Ok(());
                }
                // 开发实例不能覆盖正式安装的系统协议注册。
                #[cfg(any(target_os = "windows", target_os = "linux"))]
                {
                    use tauri_plugin_deep_link::DeepLinkExt;
                    if let Err(error) = app.deep_link().register_all() {
                        eprintln!("注册 paideia:// 协议失败：{error}");
                    }
                }
            }

            let cache = renderer_cache(DEV_RENDERER, || load_cache(&handle));
            app.manage(CacheState(Mutex::new(cache.clone())));
            let initial_handoff = (!DEV_RENDERER)
                .then(|| std::env::args().find_map(|argument| handoff_from_url(&argument)))
                .flatten();
            let script = bridge_script(&cache, initial_handoff.as_deref());

            // 必须显式指向本地静态服务，而不是 `WebviewUrl::App`：
            // App 走的是 Tauri 的 asset 协议（Windows 上是 http://tauri.localhost），
            // 来源一变，后端 CORS 与 OAuth 里写死的 127.0.0.1:5310 就全对不上。
            // 开发期同一来源由 Vite 提供（见 tauri.conf.json 的 devUrl）。
            let renderer = WebviewUrl::External(
                RENDERER_ORIGIN
                    .parse()
                    .map_err(|error| format!("渲染来源不合法：{error}"))?,
            );

            WebviewWindowBuilder::new(app, "main", renderer)
                .title("Paideia")
                .inner_size(1200.0, 800.0)
                // 最小尺寸取手机竖屏（360×480）：窄于此布局已无可压缩空间
                .min_inner_size(360.0, 480.0)
                .incognito(DEV_RENDERER)
                .on_navigation(renderer_navigation_allowed)
                .on_new_window(|_, _| tauri::webview::NewWindowResponse::Deny)
                .initialization_script(&script)
                .build()?;

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("桌面端主进程异常退出");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_explicit_unbundled_debug_build_selects_dev_renderer() {
        assert!(!use_dev_renderer(true, false, false));
        assert!(!use_dev_renderer(false, true, false));
        assert!(!use_dev_renderer(true, true, true));
        assert!(use_dev_renderer(true, true, false));
    }

    #[test]
    fn occupied_listener_cannot_start_embedded_renderer() {
        let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        assert!(bind_renderer(port).is_err());
    }

    #[test]
    fn embedded_renderer_owns_its_loopback_listener() {
        let listener = renderer_listener(0).unwrap();
        let address = listener.local_addr().unwrap();
        assert_eq!(address.ip(), std::net::Ipv4Addr::LOCALHOST);
        assert!(std::net::TcpListener::bind(address).is_err());
        assert!(tiny_http::Server::from_listener(listener, None).is_ok());
    }

    #[test]
    fn renderer_navigation_only_allows_expected_origin() {
        assert!(renderer_navigation_allowed(
            &format!("{RENDERER_ORIGIN}/#/notes").parse().unwrap()
        ));
        for unrelated in [
            "https://example.invalid/",
            "http://127.0.0.1:5311/",
            "http://localhost:5310/",
            "about:blank",
            "blob:http://127.0.0.1:5310/fixture",
        ] {
            assert!(!renderer_navigation_allowed(&unrelated.parse().unwrap()));
        }
    }

    #[test]
    fn dev_renderer_uses_ephemeral_cache() {
        assert!(renderer_cache(true, || panic!("must not load the installed cache")).is_empty());
        let data = HashMap::from([("theme".to_string(), "light".to_string())]);
        assert_eq!(renderer_cache(false, || data.clone()), data);
    }

    #[test]
    fn accepts_valid_handoff() {
        assert_eq!(
            handoff_from_url("paideia://oauth?handoff=AbC_-123"),
            Some("AbC_-123".to_string())
        );
        // ShellExecute 规范化后的真实形态：host 后带斜杠
        assert_eq!(
            handoff_from_url("paideia://oauth/?handoff=AbC_-123"),
            Some("AbC_-123".to_string())
        );
    }

    #[test]
    fn rejects_wrong_host() {
        assert_eq!(handoff_from_url("paideia://other?handoff=x"), None);
    }

    #[test]
    fn rejects_non_url_safe_handoff() {
        assert_eq!(handoff_from_url("paideia://oauth?handoff=a b"), None);
        assert_eq!(handoff_from_url("paideia://oauth?handoff=a%20b"), None);
        assert_eq!(handoff_from_url("paideia://oauth?handoff=a/b"), None);
    }

    #[test]
    fn rejects_missing_or_empty_handoff() {
        assert_eq!(handoff_from_url("paideia://oauth?foo=1"), None);
        assert_eq!(handoff_from_url("paideia://oauth?handoff="), None);
    }

    #[test]
    fn rejects_overlong_handoff() {
        let long = "a".repeat(200);
        assert_eq!(
            handoff_from_url(&format!("paideia://oauth?handoff={long}")),
            None
        );
    }

    #[test]
    fn rejects_other_protocols() {
        assert_eq!(handoff_from_url("https://example.com/?handoff=x"), None);
        assert_eq!(handoff_from_url(""), None);
    }

    #[test]
    fn decodes_percent_escapes_and_keeps_plain_paths() {
        assert_eq!(
            percent_decode("/assets/index-abc.js"),
            "/assets/index-abc.js"
        );
        assert_eq!(percent_decode("/%E4%B8%AD"), "/中");
        // 畸形转义不能 panic，也不能把整段吞掉
        assert_eq!(percent_decode("/%zz"), "/%zz");
    }
}
