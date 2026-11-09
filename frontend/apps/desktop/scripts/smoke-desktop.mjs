/**
 * 桌面壳的运行时验收（替代原来的 Playwright-Electron 用例）。
 *
 * <p>为什么不用 Playwright 的 Electron 支持：壳已经换成 Tauri，`_electron.launch` 不再适用。
 * 这里走 WebView2 的远程调试端口，直接断言**页面里真实存在的三件事**：
 *   ① 渲染来源仍是 `http://127.0.0.1:5310`（后端 CORS 与 OAuth 都按它写死的）；
 *   ② `window.paideiaDesktop` 桥已注入，且缓存能读能写；
 *   ③ 宿主标识是 `desktop`，页面停在登录门而不是白屏。
 *
 * <p>用法：先构建（`pnpm build`），再 `pnpm smoke`。
 */
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const appRoot = path.resolve(here, '..')
const DEBUG_PORT = Number(process.env['PAIDEIA_DESKTOP_DEBUG_PORT'] ?? 9223)

function resolveExe() {
  const override = process.env['PAIDEIA_DESKTOP_EXE']
  if (override) return override
  const candidates = [
    path.join(appRoot, 'src-tauri', 'target', 'release', 'paideia-desktop.exe'),
    path.join(appRoot, 'src-tauri', 'target', 'debug', 'paideia-desktop.exe'),
  ]
  return candidates.find((candidate) => existsSync(candidate)) ?? candidates[0]
}

function fail(message) {
  console.error(`✗ ${message}`)
  process.exitCode = 1
}

async function waitForTarget(timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json`)
      const targets = await response.json()
      const page = targets.find((target) => target.type === 'page')
      if (page) return page
    } catch {
      // 还没起来
    }
    await new Promise((resolve) => setTimeout(resolve, 300))
  }
  throw new Error(`等待 WebView2 调试端口 ${DEBUG_PORT} 超时`)
}

async function connect(page) {
  const ws = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', () => reject(new Error('连接调试端口失败')), { once: true })
  })
  let nextId = 0
  const pending = new Map()
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message)
      pending.delete(message.id)
    }
  })
  const send = (method, params = {}) =>
    new Promise((resolve) => {
      const id = ++nextId
      pending.set(id, resolve)
      ws.send(JSON.stringify({ id, method, params }))
    })
  await send('Runtime.enable')
  const evaluate = async (expression) => {
    const response = await send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    })
    if (response.result?.exceptionDetails) {
      const detail =
        response.result.exceptionDetails.exception?.description ??
        response.result.exceptionDetails.text
      throw new Error(`页面表达式失败：${expression}
  ${detail}`)
    }
    return response.result?.result?.value
  }
  return { evaluate, close: () => ws.close() }
}

const exe = resolveExe()
if (!existsSync(exe)) {
  console.error(`✗ 找不到桌面可执行文件：${exe}\n  先执行 pnpm build`)
  process.exit(1)
}

const child = spawn(exe, [], {
  env: {
    ...process.env,
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${DEBUG_PORT}`,
  },
  stdio: 'ignore',
})

let client
try {
  const page = await waitForTarget()
  client = await connect(page)

  // 调试端口在导航开始前就有 page target（此时还是 about:blank），
  // 直接取值会读到 null。等页面真正落到本地静态服务上再断言。
  const deadline = Date.now() + 30_000
  let origin = null
  while (Date.now() < deadline) {
    try {
      origin = await client.evaluate('location.origin')
    } catch {
      origin = null
    }
    if (origin === 'http://127.0.0.1:5310') break
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  if (origin !== 'http://127.0.0.1:5310') {
    fail(`渲染来源不是 127.0.0.1:5310，而是 ${origin}（后端 CORS 会对不上）`)
  } else {
    console.log(`✓ 渲染来源 ${origin}`)
  }

  const bridge = await client.evaluate('typeof window.paideiaDesktop')
  if (bridge !== 'object') fail(`window.paideiaDesktop 未注入（${bridge}）`)
  else console.log('✓ 桌面桥已注入')

  // 还等一次挂载：路由与 ThemeProvider 是异步起来的，太早读会读到空快照
  await new Promise((resolve) => setTimeout(resolve, 1500))
  const platform = await client.evaluate('document.documentElement.dataset.platform')
  if (platform !== 'desktop') fail(`宿主标识不是 desktop（${platform}）`)
  else console.log('✓ 宿主标识 desktop')

  const roundtrip = await client.evaluate(
    "(async () => { await window.paideiaDesktop.cache.set('smoke_key', 'ok'); return await window.paideiaDesktop.cache.get('smoke_key') })()",
  )
  if (roundtrip !== 'ok') fail(`缓存读写往返失败（${roundtrip}）`)
  else console.log('✓ 缓存读写往返')

  const loginVisible = await client.evaluate(
    "document.querySelector('[data-testid=\"login-form\"]') !== null",
  )
  if (!loginVisible) fail('没有停在登录门（页面可能白屏或未渲染）')
  else console.log('✓ 停在登录门')
} catch (error) {
  fail(error instanceof Error ? error.message : String(error))
} finally {
  client?.close()
  child.kill()
}
