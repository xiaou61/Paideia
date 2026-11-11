import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { themeInitPlugin } from '@paideia/ui/vite'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

// 端口可覆写：同一台机器上并发跑多个会话时各挑一组，否则 e2e 的 reuseExistingServer
// 会静默复用别人的后端与前端。默认值保持原样。
const BACKEND = `http://127.0.0.1:${process.env.PAIDEIA_BACKEND_PORT ?? '8080'}`
const PORT = Number(process.env.PAIDEIA_APP_PORT ?? 5173)

export default defineConfig(({ mode }) => ({
  /**
   * 相对基址：桌面壳从自定义协议加载产物，绝对路径（/assets/...）会全部 404。
   * 这个值不能改成 '/'。
   */
  base: './',
  plugins: [
    react(),
    tailwindcss(),
    themeInitPlugin(),
    /**
     * PWA（WORK-016）。学习者端可"安装"到主屏 / 桌面，并在断网时启动应用壳。
     *
     * <p><b>只加在学习者端</b>：管理端是内部工具，装到主屏没有收益，还会把后台页面缓存进
     * 用户设备；公开页是给爬虫与匿名访问者的静态页，不需要离线壳。
     *
     * <p><b>为什么不缓存 API</b>：业务数据都在 `/api` 下且随时在变。这里刻意**不配
     * `runtimeCaching`**——没有规则就等同于"从不进 Cache Storage"，预缓存清单也只列构建产物。
     * 缓存 API 会带来"看到的是上一版数据"这种最难查的问题。
     *
     * <p><b>`registerType: 'autoUpdate'`</b>：新版本部署后用户下次打开自动换新壳；
     * 配合 `cleanupOutdatedCaches` 清掉旧缓存，避免"旧 JS 配新接口"。
     *
     * <p><b>`base: './'` 与 `start_url: '.'` 必须成对</b>：桌面壳用自定义协议加载，
     * 绝对路径会 404；HTTP 下 `.` 解析为当前目录，两边都对。
     *
     * <p><b>`--mode desktop` 时完全不挂这个插件</b>：壳从 `http://127.0.0.1:<port>` 加载，
     * 那是安全上下文，SW 会真的注册并缓存产物；而桌面版每次升级复用同一个来源与端口，
     * 旧 SW 会把**上一版 bundle** 继续喂给新进程——用户装上新版、界面还是旧的，且没有报错。
     * 桌面壳自己的产物替换机制已经覆盖这件事。
     */
    (mode === 'desktop' ? null : VitePWA({
      registerType: 'autoUpdate',
      // 开发期不注册 SW：否则 `pnpm dev` 下会缓存旧产物，改代码不生效
      devOptions: { enabled: false },
      manifest: {
        name: 'Paideia · 千人千面的 AI 个性化学习平台',
        short_name: 'Paideia',
        description: '按分类浏览知识、写笔记、自评掌握度，并随时向 AI 助手提问。',
        lang: 'zh-CN',
        display: 'standalone',
        start_url: '.',
        scope: '.',
        theme_color: '#111418',
        background_color: '#f8f9fb',
        icons: [
          { src: 'pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'pwa-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        /**
         * **只预缓存应用壳**（HTML/CSS/图标），不预缓存 JS chunk。
         *
         * <p>实测原因：`@streamdown/code`（Shiki）的语言包是按语言动态 import 的独立 chunk，
         * 光 cpp 与 emacs-lisp 就各 770 KB，全量 326 个文件、**11 MB**。把它们放进预缓存清单，
         * 等于每个手机用户首次访问就白付 11 MB 流量——"移动端最佳适配"里最不该出现的东西。
         *
         * <p>JS 改走下面 `runtimeCaching` 的 CacheFirst：chunk 名带内容哈希、永不变，
         * 所以"访问过就永久缓存"是安全的，而且只有真正用到的语言包才会被下载与缓存。
         * 代价是**首次访问后立刻断网**时，未加载过的 chunk 不在缓存里——这是刻意的取舍，
         * 离线壳的语义是"回访可用"，不是"首次即离线"。
         */
        globPatterns: ['**/*.{css,html,svg,png,woff2}'],
        navigateFallback: 'index.html',
        // 这两个前缀是接口，任何时候都不该被导航回退吞掉
        navigateFallbackDenylist: [/^\/api\//, /^\/actuator\//],
        cleanupOutdatedCaches: true,
        runtimeCaching: [
          {
            // 只有 /assets/ 下的构建产物进缓存；`/api`、`/actuator` 不匹配，天然不进
            urlPattern: ({ url, sameOrigin }) => sameOrigin && url.pathname.startsWith('/assets/'),
            handler: 'CacheFirst',
            options: {
              cacheName: 'paideia-assets',
              // 内容哈希保证唯一性，过期策略只剩"别无限增长"
              expiration: { maxEntries: 200, maxAgeSeconds: 60 * 60 * 24 * 30 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
      },
    })),
  ].filter(Boolean),
  server: {
    port: PORT,
    // 开发期由 Vite 代理转发到后端，省去跨域配置。
    // 桌面壳没有代理，那时必须把它的来源加入后端的 CORS 允许列表。
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/actuator': { target: BACKEND, changeOrigin: true },
    },
  },
}))

