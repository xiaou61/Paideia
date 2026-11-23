import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { themeInitPlugin } from '@paideia/ui/vite'
import { defineConfig } from 'vite'

/**
 * 组件展览应用。
 *
 * 它**不是** apps/app 的依赖：既不在 Web 产物里，也不进桌面安装包。
 * 这是"不进产品产物"的构造性保证，不依赖打包器真的把死代码消掉。
 *
 * 不配后端代理：这一页只看组件，不调接口 —— 否则"想看组件"会变成"先起数据库"。
 * 仍在 base 与首屏脚本上与其它应用保持一致，避免同一个设计系统在不同宿主下表现不同。
 */
export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss(), themeInitPlugin()],
  server: {
    port: 5175,
    // 同 apps/app：Vite 8 默认只绑 `[::1]`，而 Playwright 探活 `http://127.0.0.1:5175`，
    // 不显式绑 IPv4 会白等 60s。
    host: process.env.PAIDEIA_UIKIT_HOST ?? '127.0.0.1',
  },
  preview: {
    port: 5175,
  },
})
