import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { themeInitPlugin } from '@paideia/ui/vite'
import { defineConfig } from 'vite'

// 端口可覆写，理由同学习者端：并发会话各用一组端口。
const BACKEND = `http://127.0.0.1:${process.env.PAIDEIA_BACKEND_PORT ?? '8080'}`
const PORT = Number(process.env.PAIDEIA_ADMIN_PORT ?? 5174)

/**
 * 管理端应用。仅 Web：桌面壳只消费 apps/app 的产物，因此本应用**按构造**不进安装包。
 *
 * 端口与 apps/app（5173）、apps/ui-kit（5175）错开，便于同时起多个面互相核对。
 * 其余（相对基址、首屏主题脚本、开发期代理）与学习者端保持一致——同一个设计系统
 * 在不同宿主下不应表现不同。
 */
export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss(), themeInitPlugin()],
  server: {
    port: PORT,
    // 显式绑 IPv4 回环：Vite 8 的默认 `localhost` 在 Windows 上只解析到 `[::1]`，
    // 而 Playwright 的 webServer.url / baseURL 都是 `http://127.0.0.1:<port>`，
    // 不绑就会等到 60s 超时。可覆写以便手机/局域网调试。
    host: process.env.PAIDEIA_ADMIN_HOST ?? '127.0.0.1',
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/actuator': { target: BACKEND, changeOrigin: true },
    },
  },
  preview: {
    port: PORT,
  },
})
